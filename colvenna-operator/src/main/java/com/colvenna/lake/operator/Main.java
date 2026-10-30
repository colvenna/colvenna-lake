package com.colvenna.lake.operator;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.dashboard.BiCredentials;
import com.colvenna.lake.operator.dashboard.DorisClient;
import com.colvenna.lake.operator.dashboard.DorisSettings;
import com.colvenna.lake.operator.dashboard.LakeDashboardReconciler;
import com.colvenna.lake.operator.flink.FlinkRest;
import com.colvenna.lake.operator.governance.LakeErasureReconciler;
import com.colvenna.lake.operator.iceberg.IcebergTables;
import com.colvenna.lake.operator.iceberg.LakeIcebergPublicationReconciler;
import com.colvenna.lake.operator.iceberg.PolarisClient;
import com.colvenna.lake.operator.lake.LakeReader;
import com.colvenna.lake.operator.lineage.ConfigMapLineageStore;
import com.colvenna.lake.operator.lineage.HttpLineageSink;
import com.colvenna.lake.operator.lineage.LineageGraph;
import com.colvenna.lake.operator.lineage.LineagePublisher;
import com.colvenna.lake.operator.lineage.LineageSettings;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.LakeQualityReconciler;
import com.colvenna.lake.operator.quality.PaimonQualityStore;
import com.colvenna.lake.operator.quality.QualityStore;
import com.colvenna.lake.operator.source.LakeSourceReconciler;
import com.colvenna.lake.operator.source.MongoProbe;
import com.colvenna.lake.operator.source.MySqlProbe;
import com.colvenna.lake.operator.source.PostgresProbe;
import com.colvenna.lake.operator.source.SourceProbe;
import com.colvenna.lake.operator.table.LakeTableReconciler;
import com.colvenna.lake.operator.task.LakeTaskReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the operator: one leader at a time, metrics and health on their own port. */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        try {
            start();
        } catch (Throwable e) {
            // Threads already started (metrics, informers) would keep a half-started operator
            // alive and Running; exiting lets Kubernetes restart it and show why.
            LoggerFactory.getLogger(Main.class).error("colvenna-operator failed to start", e);
            System.exit(1);
        }
    }

    private static void start() throws Exception {
        OperatorSettings settings = OperatorSettings.fromEnvironment(System::getenv);
        trustCatalogCa(settings.catalogCaFile());
        KubernetesClient kube = new KubernetesClientBuilder().build();
        CatalogClient catalog =
                CatalogClient.create(
                        settings.catalogUri(),
                        settings.catalogTokenFile(),
                        settings.catalogCaFile());
        LakeReader lake = new LakeReader(settings.catalogUri(), settings.catalogTokenFile());
        QualityStore quality = new PaimonQualityStore(catalog, lake);
        Metrics metrics = new Metrics();
        metrics.serve(settings.metricsPort());

        String namespace = System.getenv().getOrDefault("POD_NAMESPACE", "colvenna-system");
        // Only the namespaces the chart prepared (service account, CA, RBAC): the operator has no
        // rights anywhere else.
        Set<String> watched =
                Arrays.stream(
                                System.getenv()
                                        .getOrDefault("COLVENNA_WATCH_NAMESPACES", "")
                                        .split(","))
                        .map(String::trim)
                        .filter(n -> !n.isEmpty())
                        .collect(Collectors.toSet());
        if (watched.isEmpty()) {
            throw new IllegalArgumentException("Set COLVENNA_WATCH_NAMESPACES");
        }
        Optional<DorisSettings> doris =
                DorisSettings.fromEnvironment(System::getenv, settings.catalogUri());
        Optional<LineagePublisher> lineage =
                LineageSettings.fromEnvironment(System::getenv)
                        .map(
                                l ->
                                        lineage(
                                                l, settings, kube, catalog, watched, doris,
                                                namespace, metrics));
        Operator operator =
                new Operator(
                        o ->
                                o.withKubernetesClient(kube)
                                        .withLeaderElectionConfiguration(
                                                leaderElection(namespace, lineage)));
        Map<String, SourceProbe> probes =
                Map.of(
                        "mysql",
                        new MySqlProbe(),
                        "postgres",
                        new PostgresProbe(),
                        "mongodb",
                        new MongoProbe());
        operator.register(
                new LakeSourceReconciler(
                        settings,
                        kube,
                        catalog,
                        lake,
                        probes,
                        new FlinkRest(),
                        metrics,
                        Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeTableReconciler(
                        settings, kube, catalog, lake, new FlinkRest(), metrics, Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeTaskReconciler(settings, kube, catalog, quality, metrics, Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeQualityReconciler(kube, catalog, quality, metrics, Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeErasureReconciler(
                        settings,
                        kube,
                        catalog,
                        probes,
                        // What the installer verified of the warehouse bucket, if it did.
                        System.getenv("COLVENNA_STORAGE_EVIDENCE"),
                        doris.isPresent(),
                        metrics,
                        Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeDashboardReconciler(
                        kube,
                        catalog,
                        doris.map(d -> new DorisClient(d, kube)),
                        BiCredentials.Settings.fromEnvironment(System::getenv)
                                .map(b -> new BiCredentials(kube, b)),
                        metrics,
                        Instant::now),
                o -> o.settingNamespaces(watched));
        operator.register(
                new LakeIcebergPublicationReconciler(
                        kube,
                        catalog,
                        IcebergTables.of(lake),
                        polaris(settings),
                        metrics,
                        Instant::now),
                o -> o.settingNamespaces(watched));
        operator.installShutdownHook();
        operator.start();
        LOG.info("colvenna-operator started");
    }

    /**
     * One leader at a time, in the operator's namespace; the leader also reports lineage, from when
     * it leads until it stops.
     */
    static LeaderElectionConfiguration leaderElection(
            String namespace, Optional<LineagePublisher> lineage) {
        LeaderCallbacks callbacks =
                new LeaderCallbacks(
                        () -> lineage.ifPresent(LineagePublisher::start),
                        () -> lineage.ifPresent(LineagePublisher::stop),
                        leader -> {});
        return new LeaderElectionConfiguration(
                "colvenna-operator",
                namespace,
                LeaderElectionConfiguration.LEASE_DURATION_DEFAULT_VALUE,
                LeaderElectionConfiguration.RENEW_DEADLINE_DEFAULT_VALUE,
                LeaderElectionConfiguration.RETRY_PERIOD_DEFAULT_VALUE,
                null,
                callbacks,
                true);
    }

    /**
     * The lineage publisher: datasets are named by colvenna-catalog's and Doris's addresses, and
     * what was reported is kept in a ConfigMap of the operator's namespace.
     */
    static LineagePublisher lineage(
            LineageSettings lineage,
            OperatorSettings settings,
            KubernetesClient kube,
            CatalogClient catalog,
            Set<String> watched,
            Optional<DorisSettings> doris,
            String namespace,
            Metrics metrics) {
        URI catalogUri = URI.create(settings.catalogUri());
        LineageGraph.Naming naming =
                new LineageGraph.Naming(
                        "paimon://" + catalogUri.getHost() + ":" + catalogUri.getPort(),
                        settings.catalogUri(),
                        doris.map(d -> "doris://" + d.host() + ":" + d.port())
                                .orElse("doris://none"));
        return new LineagePublisher(
                watched,
                LineagePublisher.lister(kube),
                catalog::table,
                naming,
                new HttpLineageSink(lineage.endpoint(), lineage.apiKeyFile()),
                new ConfigMapLineageStore(kube, namespace, lineage.state()),
                metrics,
                Instant::now,
                lineage.every());
    }

    /**
     * The open layer's Iceberg catalog, when the chart names one: its certificate is signed by
     * colvenna-catalog's CA; the operator principal's credentials are read when a token is needed.
     */
    static Optional<PolarisClient> polaris(OperatorSettings settings) {
        String uri = System.getenv("COLVENNA_POLARIS_URI");
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        Path dir =
                Path.of(
                        System.getenv()
                                .getOrDefault("COLVENNA_POLARIS_CREDENTIALS_DIR", "/polaris"));
        return Optional.of(
                new PolarisClient(
                        URI.create(uri),
                        System.getenv().getOrDefault("COLVENNA_POLARIS_CATALOG", "colvenna_open"),
                        () -> {
                            try {
                                return new PolarisClient.Credentials(
                                        Files.readString(dir.resolve("client-id")).trim(),
                                        Files.readString(dir.resolve("client-secret")).trim());
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException(
                                        "Cannot read the Polaris credentials", e);
                            }
                        },
                        CatalogClient.tls(settings.catalogCaFile())));
    }

    /**
     * The Paimon REST client uses the JVM's default trust store: make it the JDK's CAs plus
     * colvenna-catalog's private CA, as the jobs' init container does with keytool.
     */
    static void trustCatalogCa(Path caFile) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        try (InputStream in = Files.newInputStream(cacerts)) {
            store.load(in, "changeit".toCharArray());
        }
        try (InputStream in = Files.newInputStream(caFile)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("colvenna-catalog-" + i++, cert);
            }
        }
        Path out = Files.createTempFile("colvenna-trust", ".p12");
        try (OutputStream o = Files.newOutputStream(out)) {
            store.store(o, "changeit".toCharArray());
        }
        System.setProperty("javax.net.ssl.trustStore", out.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    }
}
