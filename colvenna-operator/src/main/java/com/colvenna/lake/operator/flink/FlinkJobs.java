package com.colvenna.lake.operator.flink;

import com.colvenna.lake.operator.Names;
import com.colvenna.lake.operator.OperatorSettings;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.api.spec.FlinkVersion;
import org.apache.flink.kubernetes.operator.api.spec.JobManagerSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.spec.Resource;
import org.apache.flink.kubernetes.operator.api.spec.TaskManagerSpec;
import org.apache.flink.kubernetes.operator.api.spec.UpgradeMode;

/**
 * The FlinkDeployment every operator-managed job uses: application mode, Kubernetes HA, stateful
 * upgrades (last-state for CDC, savepoint for SQL), checkpoints under the job's own prefix, the
 * catalog CA in the JVM trust store, the job's bearer token mounted, restricted pod security, and
 * no Web UI writes.
 */
public final class FlinkJobs {
    public static final String SPEC_HASH = "colvenna-lake.io/spec-hash";
    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";

    /** Flink names its REST service and pods after the deployment; Kubernetes caps labels at 63. */
    static final int MAX_NAME = 45;

    private final OperatorSettings settings;

    public FlinkJobs(OperatorSettings settings) {
        this.settings = settings;
    }

    /** What one job runs. */
    public record Job(
            String name,
            String image,
            String entryClass,
            List<String> args,
            int parallelism,
            String checkpointInterval,
            boolean suspended,
            Resource jobManager,
            Resource taskManager,
            List<EnvVar> env,
            String tokenSecret,
            Map<String, String> podAnnotations,
            Map<String, String> labels,
            Map<String, String> logConfiguration,
            String sourceCaConfigMap,
            StatePolicy state) {}

    /**
     * How the job carries its state across changes: the upgrade mode, and a one-off redeploy from a
     * given savepoint (the Flink operator redeploys when the nonce changes).
     */
    public record StatePolicy(UpgradeMode mode, String redeploySavepoint, Long redeployNonce) {
        public static final StatePolicy LAST_STATE =
                new StatePolicy(UpgradeMode.LAST_STATE, null, null);
    }

    /** Where a source's CA bundle is mounted in job pods, for PostgreSQL's sslrootcert. */
    public static final String SOURCE_CA_FILE = "/source-ca/ca.pem";

    /** A deployment name from parts, shortened with a hash when too long for Flink's names. */
    public static String name(String... parts) {
        String joined =
                String.join("-", parts).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return Names.fit(joined, MAX_NAME, '-');
    }

    public FlinkDeployment build(HasMetadata owner, Job job) {
        FlinkDeployment fd = new FlinkDeployment();
        fd.getMetadata().setName(job.name());
        fd.getMetadata().setNamespace(owner.getMetadata().getNamespace());
        Map<String, String> labels = new LinkedHashMap<>(job.labels());
        labels.put(MANAGED_BY, "colvenna-operator");
        fd.getMetadata().setLabels(labels);
        fd.getMetadata()
                .setOwnerReferences(
                        List.of(
                                new OwnerReferenceBuilder()
                                        .withApiVersion(owner.getApiVersion())
                                        .withKind(owner.getKind())
                                        .withName(owner.getMetadata().getName())
                                        .withUid(owner.getMetadata().getUid())
                                        .withController(true)
                                        .withBlockOwnerDeletion(true)
                                        .build()));

        String state =
                settings.checkpointRoot()
                        + "/"
                        + owner.getMetadata().getNamespace()
                        + "/"
                        + job.name()
                        + "-"
                        + owner.getMetadata().getUid().substring(0, 8);
        Map<String, String> conf = new LinkedHashMap<>();
        conf.put("taskmanager.numberOfTaskSlots", "1");
        conf.put("execution.checkpointing.interval", job.checkpointInterval());
        conf.put(
                "execution.checkpointing.externalized-checkpoint-retention",
                "RETAIN_ON_CANCELLATION");
        conf.put("state.checkpoints.num-retained", "3");
        // Old state may hold erased personal data: savepoints are kept a week at most (HLD 12.4).
        conf.put("kubernetes.operator.savepoint.history.max.age", "7 d");
        conf.put("state.checkpoints.dir", state + "/checkpoints");
        conf.put("state.savepoints.dir", state + "/savepoints");
        conf.put("high-availability.type", "kubernetes");
        conf.put("high-availability.storageDir", state + "/ha");
        conf.put(
                "s3.aws.credentials.provider",
                "com.amazonaws.auth.WebIdentityTokenCredentialsProvider");
        // Never reach a terminal FAILED state: that deletes the HA metadata a last-state upgrade
        // needs, and the operator removes a failing table by upgrading.
        conf.put("restart-strategy.type", "exponential-delay");
        conf.put("restart-strategy.exponential-delay.initial-backoff", "10s");
        conf.put("restart-strategy.exponential-delay.max-backoff", "5min");
        // Requests as declared; idle CPU on the node may be used up to twice that.
        conf.put("kubernetes.jobmanager.cpu.limit-factor", "2");
        conf.put("kubernetes.taskmanager.cpu.limit-factor", "2");
        conf.put("kubernetes.rest-service.exposed.type", "ClusterIP");
        conf.put("web.submit.enable", "false");
        conf.put("web.cancel.enable", "false");
        conf.put("web.rescale.enable", "false");
        conf.put("classloader.check-leaked-classloader", "false");
        conf.put(
                "env.java.opts.all",
                "-Djavax.net.ssl.trustStore=/trust/cacerts"
                        + " -Djavax.net.ssl.trustStorePassword=changeit");

        FlinkDeploymentSpec spec = new FlinkDeploymentSpec();
        spec.setImage(job.image());
        spec.setFlinkVersion(FlinkVersion.v1_20);
        spec.setServiceAccount(settings.flinkServiceAccount());
        spec.setFlinkConfiguration(conf);
        JobManagerSpec jm = new JobManagerSpec();
        jm.setResource(job.jobManager());
        spec.setJobManager(jm);
        TaskManagerSpec tm = new TaskManagerSpec();
        tm.setResource(job.taskManager());
        spec.setTaskManager(tm);
        if (job.logConfiguration() != null) {
            spec.setLogConfiguration(job.logConfiguration());
        }
        JobSpec js = new JobSpec();
        js.setJarURI("local:///opt/flink/usrlib/colvenna-jobs.jar");
        js.setEntryClass(job.entryClass());
        js.setArgs(job.args().toArray(new String[0]));
        js.setParallelism(job.parallelism());
        js.setUpgradeMode(job.state().mode());
        if (job.state().redeploySavepoint() != null) {
            js.setInitialSavepointPath(job.state().redeploySavepoint());
            js.setSavepointRedeployNonce(job.state().redeployNonce());
        }
        js.setState(job.suspended() ? JobState.SUSPENDED : JobState.RUNNING);
        spec.setJob(js);
        spec.setPodTemplate(podTemplate(job));
        fd.setSpec(spec);
        fd.getMetadata().setAnnotations(Map.of(SPEC_HASH, hash(spec)));
        return fd;
    }

    /**
     * The catalog endpoint and the mounted token, as {@code com.colvenna.lake.jobs.Catalogs} reads.
     */
    public List<EnvVar> catalogEnv() {
        return List.of(
                new EnvVarBuilder()
                        .withName("COLVENNA_CATALOG_URI")
                        .withValue(settings.catalogUri())
                        .build(),
                new EnvVarBuilder()
                        .withName("COLVENNA_CATALOG_TOKEN_FILE")
                        .withValue("/token/token")
                        .build());
    }

    /** No privilege escalation and no capabilities, as restricted Pod Security requires. */
    public static SecurityContext restricted() {
        return new SecurityContextBuilder()
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .build();
    }

    /** Copies the JDK's CAs plus colvenna-catalog's into {@code /trust/cacerts}. */
    public static Container truststore(String image) {
        return new ContainerBuilder()
                .withName("truststore")
                .withImage(image)
                .withSecurityContext(restricted())
                .withCommand("sh", "-ec")
                .withArgs(
                        "cp \"$JAVA_HOME/lib/security/cacerts\" /trust/cacerts && chmod 600"
                                + " /trust/cacerts && keytool -importcert -noprompt -alias"
                                + " colvenna-catalog -file /ca/ca.crt -keystore /trust/cacerts"
                                + " -storepass changeit")
                .addNewVolumeMount()
                .withName("ca")
                .withMountPath("/ca")
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("trust")
                .withMountPath("/trust")
                .endVolumeMount()
                .build();
    }

    /** The token Secret, the catalog CA and the trust store's emptyDir. */
    public static List<Volume> catalogVolumes(String tokenSecret) {
        return List.of(
                new VolumeBuilder()
                        .withName("token")
                        .withNewSecret()
                        .withSecretName(tokenSecret)
                        .endSecret()
                        .build(),
                new VolumeBuilder()
                        .withName("ca")
                        .withNewConfigMap()
                        .withName("colvenna-catalog-ca")
                        .endConfigMap()
                        .build(),
                new VolumeBuilder().withName("trust").withNewEmptyDir().endEmptyDir().build());
    }

    /** Where the main container sees the token and the trust store. */
    public static List<VolumeMount> catalogMounts() {
        return List.of(
                new VolumeMountBuilder()
                        .withName("token")
                        .withMountPath("/token")
                        .withReadOnly(true)
                        .build(),
                new VolumeMountBuilder()
                        .withName("trust")
                        .withMountPath("/trust")
                        .withReadOnly(true)
                        .build());
    }

    /** Non-root with the runtime's default seccomp profile. */
    public static PodSecurityContext podSecurity() {
        return new PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                .withRunAsUser(9999L)
                .withRunAsGroup(9999L)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .build();
    }

    private PodTemplateSpec podTemplate(Job job) {
        List<EnvVar> env = new ArrayList<>(catalogEnv());
        env.addAll(job.env());
        PodTemplateSpec template =
                new PodTemplateSpecBuilder()
                        .withNewMetadata()
                        .withAnnotations(job.podAnnotations())
                        .endMetadata()
                        .withNewSpec()
                        .withNodeSelector(settings.jobNodeSelector())
                        .withTolerations(settings.jobTolerations())
                        .withSecurityContext(podSecurity())
                        .addToInitContainers(truststore(job.image()))
                        .addNewContainer()
                        .withName("flink-main-container")
                        .withSecurityContext(restricted())
                        .withEnv(env)
                        .withVolumeMounts(catalogMounts())
                        .endContainer()
                        .withVolumes(catalogVolumes(job.tokenSecret()))
                        .endSpec()
                        .build();
        if (job.sourceCaConfigMap() != null) {
            addSourceCa(template, job.sourceCaConfigMap());
        }
        return template;
    }

    /**
     * The source's CA bundle, mounted for PostgreSQL and added cert by cert to the JVM trust store
     * for MySQL (keytool imports only the first certificate of a bundle file).
     */
    private static void addSourceCa(PodTemplateSpec template, String configMap) {
        var spec = template.getSpec();
        spec.getVolumes()
                .add(
                        new VolumeBuilder()
                                .withName("source-ca")
                                .withNewConfigMap()
                                .withName(configMap)
                                .endConfigMap()
                                .build());
        var mount =
                new VolumeMountBuilder()
                        .withName("source-ca")
                        .withMountPath("/source-ca")
                        .withReadOnly(true)
                        .build();
        var init = spec.getInitContainers().get(0);
        init.getVolumeMounts().add(mount);
        spec.getContainers().get(0).getVolumeMounts().add(mount);
        init.setArgs(
                List.of(
                        init.getArgs().get(0)
                                + " && awk '/BEGIN CERTIFICATE/ {n++; f = \"/trust/source-\" n"
                                + " \".pem\"} f {print > f} /END CERTIFICATE/ {close(f); f ="
                                + " \"\"}' "
                                + SOURCE_CA_FILE
                                + " && for c in /trust/source-*.pem; do keytool -importcert"
                                + " -noprompt -alias \"$(basename \"$c\" .pem)\" -file \"$c\""
                                + " -keystore /trust/cacerts -storepass changeit >/dev/null;"
                                + " done && rm -f /trust/source-*.pem"));
    }

    public static Resource resource(
            Double cpu, String memory, double defaultCpu, String defaultMemory) {
        Resource r = new Resource();
        r.setCpu(cpu == null ? defaultCpu : cpu);
        r.setMemory(memory == null ? defaultMemory : memory);
        return r;
    }

    public static EnvVar secretEnv(String name, String secret, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secret)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    /** Whether the deployed spec is the one the operator would build now. */
    public static boolean current(FlinkDeployment existing, FlinkDeployment desired) {
        Map<String, String> annotations = existing.getMetadata().getAnnotations();
        return annotations != null
                && desired.getMetadata()
                        .getAnnotations()
                        .get(SPEC_HASH)
                        .equals(annotations.get(SPEC_HASH));
    }

    /** The catalog token id the deployment's pods were started with, or null. */
    public static String tokenIdOf(FlinkDeployment fd) {
        var template = fd.getSpec().getPodTemplate();
        if (template == null
                || template.getMetadata() == null
                || template.getMetadata().getAnnotations() == null) {
            return null;
        }
        return template.getMetadata().getAnnotations().get("colvenna-lake.io/token-id");
    }

    /** The spec hash a deployment was written with, or null. */
    public static String hashOf(FlinkDeployment fd) {
        Map<String, String> annotations = fd.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(SPEC_HASH);
    }

    /**
     * The declaration's hash. A redeploy savepoint is not part of it: a rollback sets one, and that
     * must not make the deployment look out of date.
     */
    static String hash(FlinkDeploymentSpec spec) {
        String json = io.fabric8.kubernetes.client.utils.Serialization.asJson(spec);
        if (spec.getJob() != null && spec.getJob().getSavepointRedeployNonce() != null) {
            FlinkDeploymentSpec copy =
                    io.fabric8.kubernetes.client.utils.Serialization.unmarshal(
                            json, FlinkDeploymentSpec.class);
            copy.getJob().setInitialSavepointPath(null);
            copy.getJob().setSavepointRedeployNonce(null);
            json = io.fabric8.kubernetes.client.utils.Serialization.asJson(copy);
        }
        return sha256(json).substring(0, 16);
    }

    public static String sha256(String value) {
        return Names.sha256(value);
    }

    /**
     * Flink's console logging with CDC and Debezium kept at WARN (both log passwords at INFO), and
     * Kafka and Hadoop quieter; every job uses it.
     */
    public static Map<String, String> quietLogging() {
        return Map.of(
                "log4j-console.properties",
                String.join(
                        "\n",
                        "rootLogger.level = INFO",
                        "rootLogger.appenderRef.console.ref = ConsoleAppender",
                        "logger.flinkCdc.name = org.apache.flink.cdc",
                        "logger.flinkCdc.level = WARN",
                        "logger.debezium.name = io.debezium",
                        "logger.debezium.level = WARN",
                        "logger.kafka.name = org.apache.kafka",
                        "logger.kafka.level = WARN",
                        "logger.hadoop.name = org.apache.hadoop",
                        "logger.hadoop.level = WARN",
                        "appender.console.name = ConsoleAppender",
                        "appender.console.type = CONSOLE",
                        "appender.console.layout.type = PatternLayout",
                        "appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss,SSS} %-5p %-60c"
                                + " %x - %m%n",
                        ""));
    }
}
