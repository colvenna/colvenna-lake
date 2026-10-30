package com.colvenna.lake.operator.dashboard;

import com.colvenna.lake.operator.catalog.CatalogClient;
import com.colvenna.lake.operator.catalog.CatalogException;
import com.colvenna.lake.operator.catalog.CatalogIdentities;
import com.colvenna.lake.operator.catalog.Grant;
import com.colvenna.lake.operator.crd.Governance;
import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.StatusCondition;
import com.colvenna.lake.operator.flink.JobObservation;
import com.colvenna.lake.operator.governance.Exposure;
import com.colvenna.lake.operator.governance.GovernancePolicy;
import com.colvenna.lake.operator.kube.Events;
import com.colvenna.lake.operator.metrics.Metrics;
import com.colvenna.lake.operator.quality.QualityGates;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves a dashboard's queries from the platform's Doris (HLD 10.1, LLD "看板服务"): a catalog over the
 * lake read with the dashboard's own colvenna-catalog identity, granted the declared tables alone;
 * per query a view (external), an asynchronous materialized view, or a Unique Key table the
 * operator rewrites on schedule (internal); and Doris users granted the queries they may run, never
 * the catalog (whose properties hold the token).
 */
@ControllerConfiguration(name = "lakedashboard")
public final class LakeDashboardReconciler
        implements Reconciler<LakeDashboard>, Cleaner<LakeDashboard> {
    private static final Logger LOG = LoggerFactory.getLogger(LakeDashboardReconciler.class);
    public static final String KIND = "lakedashboard";
    static final Duration RESYNC = Duration.ofSeconds(30);
    static final Duration NO_DORIS = Duration.ofMinutes(5);

    private final KubernetesClient kube;
    private final CatalogClient catalog;
    private final CatalogIdentities identities;
    private final Optional<DorisClient> doris;
    private final Optional<BiCredentials> bi;
    private final Events events;
    private final Metrics metrics;
    private final Supplier<Instant> clock;

    public LakeDashboardReconciler(
            KubernetesClient kube,
            CatalogClient catalog,
            Optional<DorisClient> doris,
            Optional<BiCredentials> bi,
            Metrics metrics,
            Supplier<Instant> clock) {
        this.kube = kube;
        this.catalog = catalog;
        this.identities = new CatalogIdentities(catalog, kube);
        this.doris = doris;
        this.bi = bi;
        this.events = new Events(kube);
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What one round knows. */
    private record Round(
            LakeDashboard dashboard,
            LakeDashboardSpec spec,
            LakeDashboardStatus status,
            Instant now) {
        String namespace() {
            return dashboard.getMetadata().getNamespace();
        }

        boolean condition(String type, boolean ok, String reason, String message) {
            return StatusCondition.set(
                    status.conditions,
                    type,
                    ok,
                    reason,
                    message,
                    Objects.requireNonNullElse(dashboard.getMetadata().getGeneration(), 0L),
                    now);
        }
    }

    /** The Doris names of one dashboard, all derived from it. */
    record Names(String catalog, String database, String owner, String group) {
        static Names of(LakeDashboard d) {
            return new Names(
                    DashboardSql.catalog(d),
                    DashboardSql.database(d),
                    DashboardSql.owner(d),
                    DashboardSql.workloadGroup(d));
        }
    }

    @Override
    public UpdateControl<LakeDashboard> reconcile(
            LakeDashboard dashboard, Context<LakeDashboard> context) {
        LakeDashboardStatus status =
                dashboard.getStatus() == null ? new LakeDashboardStatus() : dashboard.getStatus();
        dashboard.setStatus(status);
        Round r = new Round(dashboard, dashboard.getSpec(), status, clock.get());
        if (!Objects.equals(status.observedGeneration, dashboard.getMetadata().getGeneration())) {
            // A changed declaration: its users, grants and workload are made again this round.
            status.grantedAt = null;
        }
        status.observedGeneration = dashboard.getMetadata().getGeneration();
        String invalid = validate(r.spec(), Names.of(dashboard).catalog());
        if (invalid != null) {
            return failed(r, "SpecInvalid", invalid);
        }
        r.condition("SpecValid", true, "Valid", "the declaration is valid");
        GovernancePolicy.Declared declared =
                GovernancePolicy.Declared.of(
                        kube.resources(LakeSource.class)
                                .inNamespace(r.namespace())
                                .list()
                                .getItems(),
                        kube.resources(LakeTable.class)
                                .inNamespace(r.namespace())
                                .list()
                                .getItems());
        String refused = govern(r, declared);
        Map<String, List<Exposure.Shown>> masked = new LinkedHashMap<>();
        if (refused == null) {
            try {
                refused = expose(r, declared, masked);
            } catch (CatalogException e) {
                // What the users may see is not known now (a label may have changed since):
                // nobody is granted until it is. The objects stay.
                withdrawGrants(r);
                return catalogUnavailable(r, e);
            }
        }
        if (refused != null) {
            return failed(r, "GovernanceRefused", refused);
        }
        if (doris.isEmpty()) {
            status.phase = "Pending";
            status.message = "no Doris configured: install-doris.sh, then the chart's doris.host";
            r.condition("DorisReady", false, "NoDoris", status.message);
            publish(dashboard, status);
            return UpdateControl.patchStatus(dashboard).rescheduleAfter(NO_DORIS);
        }
        try {
            status.identity =
                    identities.ensure(
                            dashboard,
                            KIND,
                            "LakeDashboard "
                                    + r.namespace()
                                    + "/"
                                    + dashboard.getMetadata().getName(),
                            grants(r),
                            status.identity,
                            r.now());
            r.condition("CatalogReady", true, "Ready", "identity, token and grants are current");
        } catch (CatalogException e) {
            return catalogUnavailable(r, e);
        }
        try {
            serve(r, this.doris.get(), masked);
            r.condition("DorisReady", true, "Ready", "the dashboard's objects are in Doris");
        } catch (SQLException | KubernetesClientException e) {
            // Whatever went missing (a user, a grant, the catalog) is made again next round.
            status.grantedAt = null;
            String message = "Doris: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            if (r.condition("DorisReady", false, "DorisUnavailable", message)) {
                events.warning(dashboard, "DorisUnavailable", message);
            }
            status.phase = "Degraded";
            status.message = message;
            publish(dashboard, status);
            return UpdateControl.patchStatus(dashboard).rescheduleAfter(RESYNC);
        }
        try {
            publishSignIns(r);
        } catch (KubernetesClientException e) {
            // Doris is served; the BI interface's Secret is tried again next round.
            String message =
                    "BI credentials: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            if (r.condition("BiPublished", false, "BiUnavailable", message)) {
                events.warning(dashboard, "BiUnavailable", message);
            }
        }
        summarize(r);
        status.measuredAt = r.now().toString();
        publish(dashboard, status);
        return UpdateControl.patchStatus(dashboard).rescheduleAfter(RESYNC);
    }

    private UpdateControl<LakeDashboard> catalogUnavailable(Round r, CatalogException e) {
        if (r.condition("CatalogReady", false, "CatalogUnavailable", e.getMessage())) {
            events.warning(r.dashboard(), "CatalogUnavailable", e.getMessage());
        }
        r.status().phase = "Degraded";
        r.status().message = "colvenna-catalog: " + e.getMessage();
        publish(r.dashboard(), r.status());
        return UpdateControl.patchStatus(r.dashboard()).rescheduleAfter(RESYNC);
    }

    /**
     * Each user's grants on each query object, made or not, revoked; they are made again with the
     * next grants (the status no longer records any).
     */
    private void withdrawGrants(Round r) {
        LakeDashboardStatus status = r.status();
        if (doris.isEmpty() || status.database == null) {
            return;
        }
        Set<String> users = new LinkedHashSet<>(status.users.keySet());
        r.spec().users.forEach(u -> users.add(DashboardSql.user(r.dashboard(), u.name)));
        Set<String> objects = new LinkedHashSet<>();
        status.queries.forEach(q -> objects.add(q.name));
        r.spec().queries.forEach(q -> objects.add(q.name));
        try (DorisClient.Session root = doris.get().root()) {
            for (String user : users) {
                for (String object : objects) {
                    quietly(root, DashboardSql.revoke(status.database, object, user));
                }
            }
        } catch (SQLException | KubernetesClientException e) {
            LOG.warn(
                    "Withdrawing the grants of {} failed: {}",
                    r.dashboard().getMetadata().getName(),
                    e.getMessage());
        }
        status.grantedAt = null;
    }

    /**
     * What is wrong with the declaration itself, or null. Its queries do not name the dashboard's
     * {@code catalog}, a system table or a table function: the declared tables are read only as
     * {@code database.table}, which the operator points at the catalog or at the view that masks
     * them.
     */
    static String validate(LakeDashboardSpec spec, String catalog) {
        if (spec == null || spec.tables == null || spec.tables.isEmpty()) {
            return "a dashboard declares the lake tables it reads";
        }
        Set<String> names = new HashSet<>();
        boolean copies = false;
        for (LakeDashboardSpec.Query q : spec.queries) {
            if (!names.add(q.name)) {
                return "query " + q.name + " is declared twice";
            }
            copies |= DashboardSql.Serve.of(q.serve).copies();
            if (!q.sql.strip()
                    .toUpperCase(java.util.Locale.ROOT)
                    .matches("(?s)^(SELECT|WITH)\\b.*")) {
                return "query " + q.name + " is not a SELECT";
            }
            if (q.sql.contains(";")) {
                return "query " + q.name + " holds more than one statement";
            }
            if (DashboardSql.namesSystemSchema(q.sql)) {
                return "query " + q.name + " names a schema of Doris itself";
            }
            if (DashboardSql.callsTableFunction(q.sql)) {
                return "query " + q.name + " calls a table function: only declared tables are read";
            }
            if (DashboardSql.namesCatalog(q.sql, catalog)) {
                return "query "
                        + q.name
                        + " names the dashboard's catalog: a lake table is named database.table";
            }
            if (DashboardSql.namesSystemTable(q.sql)) {
                return "query "
                        + q.name
                        + " names a system table ($): only declared tables are read";
            }
        }
        // The users see no more than the dashboard is classified for; without governance, no
        // more than L2 without personal data.
        if (spec.access != null) {
            Governance g = Governance.classified(spec.governance) ? spec.governance : null;
            int cap = g == null ? 2 : Governance.level(g);
            List<String> categories = g == null ? List.of() : g.categories;
            if (Governance.levelOf(spec.access.level) > cap
                    || !categories.containsAll(spec.access.categories)) {
                return "access "
                        + spec.access.level
                        + " "
                        + spec.access.categories
                        + " is above the dashboard's own governance (L"
                        + cap
                        + " "
                        + categories
                        + ")";
            }
        }
        if (copies && !Governance.classified(spec.governance)) {
            return "materialized and internal queries copy the lake into Doris: the dashboard"
                    + " declares governance (owner and level) for those copies";
        }
        Set<String> users = new HashSet<>();
        Set<String> people = new HashSet<>();
        for (LakeDashboardSpec.User u : spec.users) {
            if (!users.add(u.name)) {
                return "user " + u.name + " is declared twice";
            }
            // Signing in names one person; each is one Doris user of a dashboard. User pool names
            // are case-insensitive.
            if (u.sso != null && !people.add(u.sso.username.toLowerCase(Locale.ROOT))) {
                return "sign-in user " + u.sso.username + " is given to two users";
            }
            for (String q : u.queries) {
                if (!names.contains(q)) {
                    return "user " + u.name + " is given query " + q + ", which is not declared";
                }
            }
            String rows = u.rows == null ? null : DashboardSql.rowsProblem(u.rows);
            if (rows != null) {
                return "user " + u.name + ": " + rows;
            }
        }
        return null;
    }

    /**
     * Whether the dashboard may read what it declares (HLD 12.5): this namespace's classified
     * tables only, and copies at least as high as what they copy, personal if it is. Null when it
     * may, else why not.
     */
    private String govern(Round r, GovernancePolicy.Declared declared) {
        String ns = r.namespace();
        GovernancePolicy.Upstream upstream = declared.upstream(r.spec().tables);
        List<String> held =
                QualityGates.withhold(
                        QualityGates.holds(kube, ns), new ArrayList<>(r.spec().tables));
        if (!held.isEmpty()) {
            return "it reads tables held back from publication by their quality gates ("
                    + String.join("; ", held)
                    + ")";
        }
        if (!upstream.unclassified().isEmpty()) {
            return "it reads tables no declaration of this namespace classifies ("
                    + String.join(", ", upstream.unclassified())
                    + "): their level and categories are unknown";
        }
        boolean copies =
                r.spec().queries.stream().anyMatch(q -> DashboardSql.Serve.of(q.serve).copies());
        Governance g = r.spec().governance;
        if (copies && Governance.level(g) < upstream.level()) {
            return g.level
                    + " is below L"
                    + upstream.level()
                    + " of what it copies ("
                    + String.join(", ", upstream.tables())
                    + ")";
        }
        if (copies && upstream.personal() && !g.personal()) {
            return "it copies personal data ("
                    + String.join(", ", upstream.tables())
                    + ") but declares no personal category";
        }
        return null;
    }

    /**
     * The declared tables the dashboard is not cleared to see all of, each column as it may see it
     * (LLD "列标签与脱敏"), into {@code masked}; a table's labels are checked against its columns as
     * colvenna-catalog has them now. Null when it may be served so, else why not.
     */
    private String expose(
            Round r, GovernancePolicy.Declared declared, Map<String, List<Exposure.Shown>> masked) {
        Exposure.Clearance clearance = Exposure.Clearance.of(r.spec());
        for (String table : r.spec().tables) {
            Map<String, Governance.ColumnLabel> labels = declared.labelsOf(table);
            Governance g = declared.governanceOf(table);
            if (labels.isEmpty() && clearance.coversTable(g)) {
                continue;
            }
            String[] parts = table.split("\\.", 2);
            Optional<JsonNode> t = catalog.table(parts[0], parts[1]);
            if (t.isEmpty()) {
                return table + " is to be masked and does not exist yet";
            }
            List<String> columns = new ArrayList<>();
            t.get()
                    .path("schema")
                    .path("fields")
                    .forEach(f -> columns.add(f.path("name").asText()));
            String problem = Exposure.problem(g, labels, columns);
            if (problem != null) {
                return table + ": " + problem;
            }
            List<Exposure.Shown> shown = Exposure.of(g, labels, columns, clearance);
            if (Exposure.masks(shown)) {
                masked.put(table, shown);
            }
        }
        return null;
    }

    /** Table-level READ on the declared tables, nothing else, approved by the declaration. */
    private static List<Grant> grants(Round r) {
        String principal = CatalogIdentities.principalName(KIND, r.dashboard());
        String approval = r.dashboard().getMetadata().getUid();
        List<Grant> out = new ArrayList<>();
        for (String table : r.spec().tables) {
            String[] parts = table.split("\\.", 2);
            out.add(Grant.table(principal, parts[0], parts[1], "READ", approval));
        }
        return out;
    }

    /** How often the users' grants are made again, whatever happened to them in between. */
    static final Duration REGRANT = Duration.ofMinutes(10);

    /** How long an internal table's rewrite may take. */
    static final int REWRITE_SECONDS = 600;

    /**
     * The catalog, the owner, the workload group, the database, each query's object and its
     * refresh, and the users. Root makes the catalog, the owner and the users; the owner, which may
     * read the dashboard's catalog and write its database and nothing else, builds the objects.
     */
    private void serve(Round r, DorisClient doris, Map<String, List<Exposure.Shown>> masked)
            throws SQLException {
        LakeDashboardStatus status = r.status();
        LakeDashboard d = r.dashboard();
        Names names = Names.of(d);
        String catalog = names.catalog();
        String database = names.database();
        String owner = names.owner();
        String group = names.group();
        String token = token(r);
        String ownerPassword = doris.seal("owner/" + d.getMetadata().getUid());
        int concurrency =
                r.spec().workload == null || r.spec().workload.maxConcurrency == null
                        ? 10
                        : r.spec().workload.maxConcurrency;
        boolean regrant =
                status.grantedAt == null
                        || !Instant.parse(status.grantedAt).plus(REGRANT).isAfter(r.now());
        Map<String, Instant> refreshed;
        try (DorisClient.Session root = doris.root()) {
            boolean exists =
                    root.query("SHOW CATALOGS").stream()
                            .anyMatch(c -> catalog.equals(c.get("CatalogName")));
            if (!exists) {
                executeSecret(
                        root,
                        DashboardSql.createCatalog(catalog, doris.settings().catalogUri(), token),
                        token);
                status.catalogTokenId = status.identity.tokenId;
                regrant = true;
            } else if (!Objects.equals(status.catalogTokenId, status.identity.tokenId)) {
                // A renewed token, or one this status lost track of: Doris holds it in the
                // catalog's properties.
                executeSecret(root, DashboardSql.catalogToken(catalog, token), token);
                status.catalogTokenId = status.identity.tokenId;
            }
            status.catalog = catalog;
            identities.revokePrevious(status.identity);
            root.execute(DashboardSql.createDatabase(database));
            status.database = database;
            status.workloadGroup = group;
            if (regrant) {
                // Every statement here is idempotent; each grant is a write to Doris's journal, so
                // they are made again on a change, a failure or every REGRANT, not every round.
                root.execute(DashboardSql.createWorkloadGroup(group, concurrency));
                root.execute(DashboardSql.alterWorkloadGroup(group, concurrency));
                for (String statement :
                        DashboardSql.ownerGrants(
                                owner,
                                DashboardSql.passwordHash(ownerPassword),
                                catalog,
                                database,
                                group)) {
                    root.execute(statement);
                }
                for (String statement : DashboardSql.withdrawDefaults(owner)) {
                    quietly(root, statement);
                }
                // Refreshes held while the declaration was refused run again.
                for (LakeDashboardStatus.QueryState q : status.queries) {
                    if ("materialized".equals(q.serve)) {
                        quietly(root, DashboardSql.resumeRefresh(database, q.name));
                    }
                }
            }
            // The databases of the views masked tables are read through: the owner's alone. One
            // no longer needed goes, with its views.
            Set<String> shadows = new TreeSet<>();
            for (String table : masked.keySet()) {
                String shadow = DashboardSql.shadowDatabase(d, table.split("\\.", 2)[0]);
                root.execute(DashboardSql.createDatabase(shadow));
                if (regrant || !status.shadowDatabases.contains(shadow)) {
                    root.execute(DashboardSql.shadowGrant(owner, shadow));
                }
                shadows.add(shadow);
            }
            for (String gone : status.shadowDatabases) {
                if (!shadows.contains(gone)) {
                    quietly(root, DashboardSql.dropDatabase(gone));
                }
            }
            status.shadowDatabases = new ArrayList<>(shadows);
            refreshed = materializedRefreshes(root, database);
        }
        Map<String, LakeDashboardStatus.QueryState> before = new HashMap<>();
        status.queries.forEach(q -> before.put(q.name, q));
        List<LakeDashboardStatus.QueryState> next = new ArrayList<>();
        try (DorisClient.Session build = doris.as(owner, ownerPassword, REWRITE_SECONDS)) {
            Map<String, String> shadows;
            try {
                shadows = views(r, build, masked, names);
            } catch (SQLException e) {
                // The objects would go on reading the table as before, unmasked: nobody is
                // granted until the view is made.
                withdrawGrants(r);
                throw e;
            }
            Set<String> objects = new HashSet<>();
            build.query(DashboardSql.showTables(database))
                    .forEach(row -> objects.addAll(row.values()));
            for (LakeDashboardSpec.Query q : r.spec().queries) {
                next.add(
                        query(r, build, q, before.get(q.name), objects, refreshed, names, shadows));
            }
            for (LakeDashboardStatus.QueryState gone : status.queries) {
                if (next.stream().noneMatch(q -> q.name.equals(gone.name)) && gone.serve != null) {
                    try {
                        build.execute(
                                DashboardSql.drop(
                                        database, gone.name, DashboardSql.Serve.of(gone.serve)));
                    } catch (SQLException e) {
                        // Kept in the status, dropped again next round.
                        gone.message = JobObservation.abbreviate(String.valueOf(e.getMessage()));
                        next.add(gone);
                    }
                }
            }
        }
        status.queries = next;
        try (DorisClient.Session root = doris.root()) {
            users(r, root, doris, database, group, concurrency, regrant);
        }
    }

    /**
     * The views masked tables are read through, made when their definition changed or they went
     * missing, and those no longer needed dropped. Returns each masked table's shadow database.
     */
    private Map<String, String> views(
            Round r,
            DorisClient.Session build,
            Map<String, List<Exposure.Shown>> masked,
            Names names)
            throws SQLException {
        Map<String, LakeDashboardStatus.TableExposure> before = new HashMap<>();
        r.status().exposure.forEach(e -> before.put(e.table, e));
        Map<String, String> shadows = new LinkedHashMap<>();
        List<LakeDashboardStatus.TableExposure> next = new ArrayList<>();
        Map<String, Set<String>> present = new HashMap<>();
        for (Map.Entry<String, List<Exposure.Shown>> e : masked.entrySet()) {
            String table = e.getKey();
            String[] parts = table.split("\\.", 2);
            String shadow = DashboardSql.shadowDatabase(r.dashboard(), parts[0]);
            String statement =
                    DashboardSql.protectedView(shadow, table, names.catalog(), e.getValue());
            Set<String> views = present.get(shadow);
            if (views == null) {
                views = new HashSet<>();
                for (Map<String, String> row : build.query(DashboardSql.showTables(shadow))) {
                    views.addAll(row.values());
                }
                present.put(shadow, views);
            }
            LakeDashboardStatus.TableExposure x = new LakeDashboardStatus.TableExposure();
            x.table = table;
            x.view = shadow + "." + parts[1];
            x.applied = DashboardSql.applied(statement);
            e.getValue().stream()
                    .filter(Exposure.Shown::masked)
                    .forEach(c -> x.masked.add(c.column() + ":" + c.mask()));
            LakeDashboardStatus.TableExposure was = before.get(table);
            if (was == null || !x.applied.equals(was.applied) || !views.contains(parts[1])) {
                build.execute(statement);
                events.normal(
                        r.dashboard(),
                        "ColumnsMasked",
                        table + " is read through " + x.view + ", masking " + x.masked);
            }
            shadows.put(table, shadow);
            next.add(x);
        }
        for (LakeDashboardStatus.TableExposure gone : r.status().exposure) {
            if (!masked.containsKey(gone.table)) {
                quietly(build, DashboardSql.dropView(gone.view));
            }
        }
        r.status().exposure = next;
        return shadows;
    }

    /** One query: its object made or rebuilt when its definition changed or it went missing. */
    private LakeDashboardStatus.QueryState query(
            Round r,
            DorisClient.Session build,
            LakeDashboardSpec.Query q,
            LakeDashboardStatus.QueryState was,
            Set<String> objects,
            Map<String, Instant> refreshed,
            Names names,
            Map<String, String> shadows) {
        String database = names.database();
        LakeDashboardStatus.QueryState s = was != null ? was : new LakeDashboardStatus.QueryState();
        DashboardSql.Serve previous = s.serve == null ? null : DashboardSql.Serve.of(s.serve);
        DashboardSql.Serve serve = DashboardSql.Serve.of(q.serve);
        s.name = q.name;
        s.object = database + "." + q.name;
        String sql = DashboardSql.qualify(q.sql, r.spec().tables, names.catalog(), shadows);
        // The masking views' definitions are part of the query's: a column masked since makes a
        // copy anew, which would otherwise hold its values in clear until its next refresh.
        StringBuilder masks = new StringBuilder();
        for (LakeDashboardStatus.TableExposure e : r.status().exposure) {
            if (DashboardSql.tablesRead(q.sql, r.spec().tables).contains(e.table)) {
                masks.append('\n').append(e.table).append(' ').append(e.applied);
            }
        }
        String applied = DashboardSql.applied(q, sql + masks);
        try {
            if (!applied.equals(s.applied) || !objects.contains(q.name)) {
                if (previous == null && objects.contains(q.name)) {
                    // An object of this name the status does not know: whatever it is, it goes.
                    for (DashboardSql.Serve other : DashboardSql.Serve.values()) {
                        if (other != serve) {
                            quietly(build, DashboardSql.drop(database, q.name, other));
                        }
                    }
                }
                for (String statement : DashboardSql.serve(database, q, sql, previous)) {
                    build.execute(statement);
                }
                s.serve = q.serve;
                // CREATE TABLE ... AS SELECT filled an internal table as of now.
                s.refreshedAt = serve == DashboardSql.Serve.INTERNAL ? r.now().toString() : null;
                s.applied = applied;
                events.normal(r.dashboard(), "QueryServed", q.name + " served " + q.serve);
            }
            if (serve == DashboardSql.Serve.INTERNAL && due(s, q, r.now())) {
                Instant start = clock.get();
                build.execute(DashboardSql.overwrite(database, q.name, sql));
                s.refreshedAt = start.toString();
            }
            if (serve == DashboardSql.Serve.MATERIALIZED && refreshed.containsKey(q.name)) {
                s.refreshedAt = refreshed.get(q.name).toString();
            }
            s.message = null;
        } catch (SQLException e) {
            // A failed refresh leaves the object as it is. A changed definition that Doris refuses
            // (a column masked since, a query edited) takes the old object away: its users would
            // read what is no longer declared. Retried next round.
            if (!applied.equals(s.applied) && s.applied != null) {
                quietly(
                        build,
                        DashboardSql.drop(database, q.name, previous != null ? previous : serve));
                s.applied = null;
                events.warning(
                        r.dashboard(),
                        "QueryWithdrawn",
                        q.name + " is dropped until it can be made as declared");
            }
            String message = JobObservation.abbreviate(String.valueOf(e.getMessage()));
            if (!message.equals(s.message)) {
                events.warning(r.dashboard(), "QueryFailed", q.name + ": " + message);
            }
            s.message = message;
        }
        s.refreshAgeSeconds =
                s.refreshedAt == null
                        ? null
                        : Math.max(
                                0,
                                Duration.between(Instant.parse(s.refreshedAt), r.now())
                                        .toSeconds());
        return s;
    }

    /** Whether an internal query's copy is due for its refresh. */
    static boolean due(LakeDashboardStatus.QueryState s, LakeDashboardSpec.Query q, Instant now) {
        return s.refreshedAt == null
                || !Instant.parse(s.refreshedAt)
                        .plus(Duration.ofMinutes(DashboardSql.minutes(q.refresh)))
                        .isAfter(now);
    }

    /** When each materialized view's latest successful refresh started, by Doris's own clock. */
    private static Map<String, Instant> materializedRefreshes(
            DorisClient.Session doris, String database) throws SQLException {
        Map<String, Instant> out = new HashMap<>();
        for (Map<String, String> row : doris.query(DashboardSql.refreshes(database))) {
            String started = row.get("started");
            if (started != null && started.matches("[0-9]+(\\.[0-9]+)?")) {
                out.put(
                        row.get("MvName"),
                        Instant.ofEpochSecond((long) Double.parseDouble(started)));
            }
        }
        return out;
    }

    /**
     * Each declared user, its password's hash, its workload group and exactly the queries it is
     * given; grants are made again every {@link #REGRANT}, or when a user or what it is given
     * changes.
     */
    private void users(
            Round r,
            DorisClient.Session root,
            DorisClient doris,
            String database,
            String group,
            int connections,
            boolean regrant)
            throws SQLException {
        LakeDashboardStatus status = r.status();
        Map<String, String> next = new LinkedHashMap<>();
        for (LakeDashboardSpec.User u : r.spec().users) {
            String user = DashboardSql.user(r.dashboard(), u.name);
            String hash = DashboardSql.passwordHash(password(r, u));
            // The status keeps a seal of the hash, not the hash: with one login overheard, a hash
            // logs in.
            String sealed = doris.seal("user/" + user + "/" + hash);
            if (regrant || !sealed.equals(status.users.get(user))) {
                root.execute(DashboardSql.createUser(user, hash));
                root.execute(DashboardSql.setPassword(user, hash));
                regrant = true;
            }
            next.put(user, sealed);
        }
        Map<String, String> policies;
        try {
            policies = rowPolicies(r, root, database);
        } catch (SQLException e) {
            // A user without its policy would see every row: nobody keeps a grant meanwhile.
            withdrawGrants(r);
            throw e;
        }
        if (regrant || !next.keySet().equals(status.users.keySet())) {
            for (LakeDashboardSpec.User u : r.spec().users) {
                String user = DashboardSql.user(r.dashboard(), u.name);
                for (String statement : DashboardSql.userGroup(user, group, connections)) {
                    root.execute(statement);
                }
                for (String statement : DashboardSql.withdrawDefaults(user)) {
                    quietly(root, statement);
                }
                for (LakeDashboardSpec.Query q : r.spec().queries) {
                    if (u.queries.contains(q.name)) {
                        root.execute(DashboardSql.grant(database, q.name, user));
                    } else {
                        quietly(root, DashboardSql.revoke(database, q.name, user));
                    }
                }
            }
            status.grantedAt = r.now().toString();
        }
        for (String gone : status.users.keySet()) {
            if (!next.containsKey(gone)) {
                root.execute(DashboardSql.dropUser(gone));
            }
        }
        status.users = next;
        // The policies no longer wanted go last: a changed condition's new policy came first, so a
        // user is never without one meanwhile.
        for (Map.Entry<String, String> gone : status.rowPolicies.entrySet()) {
            if (!policies.containsKey(gone.getKey())) {
                root.execute(DashboardSql.dropRowPolicy(gone.getKey(), database, gone.getValue()));
            }
        }
        status.rowPolicies = policies;
    }

    /**
     * Once a user has a row condition, each user's queries get a policy (LLD "行过滤"): its condition,
     * or no rows for a user without one. Made before the grants, and checked against Doris every
     * round: a policy that went missing is made again before anyone is granted. Returns the
     * policies wanted, by name, each with its object.
     */
    private Map<String, String> rowPolicies(Round r, DorisClient.Session root, String database)
            throws SQLException {
        Map<String, String> wanted = new LinkedHashMap<>();
        Map<String, String> statements = new LinkedHashMap<>();
        if (r.spec().users.stream().noneMatch(u -> u.rows != null)) {
            return wanted;
        }
        for (LakeDashboardSpec.User u : r.spec().users) {
            String user = DashboardSql.user(r.dashboard(), u.name);
            for (String q : u.queries) {
                String name = DashboardSql.rowPolicyName(user, database, q, u.rows);
                wanted.put(name, q);
                statements.put(name, DashboardSql.createRowPolicy(name, database, q, user, u.rows));
            }
        }
        Set<String> existing = new HashSet<>();
        for (Map<String, String> row : root.query(DashboardSql.showRowPolicies())) {
            existing.add(row.get("PolicyName"));
        }
        for (Map.Entry<String, String> s : statements.entrySet()) {
            if (!existing.contains(s.getKey())) {
                root.execute(s.getValue());
            }
        }
        return wanted;
    }

    /**
     * A statement that holds a secret: Doris may echo the statement in its error, which goes to
     * status and events, so the secret is cut from it (and the original, cause included, dropped).
     */
    private static void executeSecret(DorisClient.Session doris, String statement, String secret)
            throws SQLException {
        try {
            doris.execute(statement);
        } catch (SQLException e) {
            throw new SQLException(
                    String.valueOf(e.getMessage()).replace(secret, "<redacted>"),
                    e.getSQLState(),
                    e.getErrorCode());
        }
    }

    /**
     * A statement whose failure says nothing: a revoke of what was never granted, a drop of an
     * object that is of another kind.
     */
    private static void quietly(DorisClient.Session doris, String statement) {
        try {
            doris.execute(statement);
        } catch (SQLException e) {
            LOG.debug("{}: {}", statement, e.getMessage());
        }
    }

    private String token(Round r) {
        Secret secret =
                kube.secrets()
                        .inNamespace(r.namespace())
                        .withName(CatalogIdentities.tokenSecretName(r.dashboard()))
                        .get();
        if (secret == null || secret.getData() == null) {
            throw new KubernetesClientException("the dashboard's token Secret is not there yet");
        }
        return decode(secret.getData().get(CatalogIdentities.TOKEN_KEY));
    }

    private String password(Round r, LakeDashboardSpec.User u) {
        Secret secret =
                kube.secrets().inNamespace(r.namespace()).withName(u.passwordSecret.name).get();
        String key = u.passwordSecret.key();
        if (secret == null || secret.getData() == null || !secret.getData().containsKey(key)) {
            throw new KubernetesClientException(
                    "no Secret " + u.passwordSecret.name + " with key " + key + " for " + u.name);
        }
        return decode(secret.getData().get(key));
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8).strip();
    }

    /**
     * The users with a sign-in name, published to the BI interface (BiCredentials) once their Doris
     * users are made; a dashboard without them publishes none.
     */
    private void publishSignIns(Round r) {
        List<LakeDashboardSpec.User> people =
                r.spec().users.stream().filter(u -> u.sso != null).toList();
        if (bi.isEmpty()) {
            if (!people.isEmpty()) {
                r.condition(
                        "BiPublished",
                        false,
                        "NoBi",
                        "sign-in users are declared, but no BI interface is configured (the"
                                + " chart's bi.credentialsSecret)");
            }
            return;
        }
        List<BiCredentials.Entry> entries = new ArrayList<>();
        for (LakeDashboardSpec.User u : people) {
            entries.add(
                    new BiCredentials.Entry(
                            u.sso.username,
                            DashboardSql.user(r.dashboard(), u.name),
                            password(r, u),
                            Names.of(r.dashboard()).database(),
                            dashboardName(r.dashboard())));
        }
        bi.get().publish(dashboardName(r.dashboard()), entries);
        r.condition(
                "BiPublished",
                true,
                "Published",
                entries.size() + " sign-in users published to the BI interface");
    }

    private static String dashboardName(LakeDashboard d) {
        return d.getMetadata().getNamespace() + "/" + d.getMetadata().getName();
    }

    private void summarize(Round r) {
        LakeDashboardStatus status = r.status();
        List<String> failing =
                status.queries.stream().filter(q -> q.message != null).map(q -> q.name).toList();
        if (failing.isEmpty()) {
            status.phase = "Ready";
            status.message = status.queries.size() + " queries served";
            r.condition("Ready", true, "Served", status.message);
        } else {
            status.phase = "Degraded";
            status.message = "not served: " + String.join(", ", failing);
            r.condition("Ready", false, "QueriesFailed", status.message);
        }
    }

    /**
     * A declaration that is invalid or refused serves no one and copies nothing more: its users go
     * and its materialized views stop refreshing. Both come back with the first round that does not
     * refuse it: a fixed declaration, or (judged again on schedule) the tables' declarations or
     * quality gates allowing it. Its objects stay, as they were.
     */
    private UpdateControl<LakeDashboard> failed(Round r, String reason, String message) {
        LakeDashboardStatus status = r.status();
        if (r.condition("SpecValid", false, reason, message)) {
            events.warning(r.dashboard(), reason, message);
        }
        r.condition("Ready", false, reason, message);
        status.phase = "Failed";
        status.message = message;
        status.measuredAt = null;
        boolean withdrawn = true;
        Set<String> users = new LinkedHashSet<>(status.users.keySet());
        if (r.spec() != null && r.spec().users != null) {
            r.spec().users.forEach(u -> users.add(DashboardSql.user(r.dashboard(), u.name)));
        }
        if (doris.isPresent() && (!users.isEmpty() || !status.queries.isEmpty())) {
            try (DorisClient.Session root = doris.get().root()) {
                for (String user : users) {
                    root.execute(DashboardSql.dropUser(user));
                }
                // Nor does it copy any more of the lake: its materialized views wait (its
                // internal tables are rewritten by the rounds this one does not reach).
                for (LakeDashboardStatus.QueryState q : status.queries) {
                    if ("materialized".equals(q.serve) && status.database != null) {
                        quietly(root, DashboardSql.pauseRefresh(status.database, q.name));
                    }
                }
                status.users = new LinkedHashMap<>();
                status.grantedAt = null;
            } catch (SQLException | KubernetesClientException e) {
                withdrawn = false;
                status.message =
                        message
                                + "; its users are not withdrawn yet: "
                                + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            }
        }
        if (bi.isPresent()) {
            try {
                bi.get().withdraw(dashboardName(r.dashboard()));
            } catch (KubernetesClientException e) {
                withdrawn = false;
                status.message =
                        message
                                + "; its sign-in users are not withdrawn from the BI interface yet: "
                                + JobObservation.abbreviate(String.valueOf(e.getMessage()));
            }
        }
        publish(r.dashboard(), status);
        // A refusal is judged again on schedule: what refused it (the tables' declarations, their
        // quality gates) changes without the dashboard changing. An invalid declaration changes
        // only with a new generation, which brings a round of its own.
        return reason.equals("GovernanceRefused") || !withdrawn
                ? UpdateControl.patchStatus(r.dashboard()).rescheduleAfter(RESYNC)
                : UpdateControl.patchStatus(r.dashboard());
    }

    private void publish(LakeDashboard d, LakeDashboardStatus status) {
        Map<String, String> base =
                Map.of(
                        "namespace",
                        d.getMetadata().getNamespace(),
                        "lakedashboard",
                        d.getMetadata().getName());
        List<Metrics.Sample> samples = new ArrayList<>();
        for (String phase : List.of("Pending", "Ready", "Degraded", "Failed")) {
            Map<String, String> labels = new HashMap<>(base);
            labels.put("phase", phase);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_dashboard_phase",
                            "1 for the dashboard's current phase",
                            labels,
                            phase.equals(status.phase) ? 1 : 0));
        }
        if (status.measuredAt != null) {
            samples.add(
                    new Metrics.Sample(
                            "colvenna_dashboard_measured_timestamp_seconds",
                            "when the operator last looked at the dashboard's copies",
                            base,
                            Instant.parse(status.measuredAt).getEpochSecond()));
        }
        for (LakeDashboardStatus.QueryState q : status.queries) {
            if (q.refreshedAt == null) {
                continue;
            }
            Map<String, String> labels = new HashMap<>(base);
            labels.put("query", q.name);
            labels.put("serve", q.serve);
            samples.add(
                    new Metrics.Sample(
                            "colvenna_dashboard_refreshed_timestamp_seconds",
                            "when the latest refresh of the query's copy that succeeded started:"
                                    + " it holds the lake as of then",
                            labels,
                            Instant.parse(q.refreshedAt).getEpochSecond()));
        }
        metrics.publish(
                KIND + ":" + d.getMetadata().getNamespace() + "/" + d.getMetadata().getName(),
                samples);
    }

    @Override
    public DeleteControl cleanup(LakeDashboard dashboard, Context<LakeDashboard> context) {
        LakeDashboardStatus status = dashboard.getStatus();
        try {
            // The BI interface stops connecting as its users before they go.
            if (bi.isPresent()) {
                bi.get().withdraw(dashboardName(dashboard));
            }
            if (doris.isPresent()) {
                try (DorisClient.Session s = doris.get().root()) {
                    // Its names follow from the dashboard: a status that lost them loses nothing.
                    List<String> users = new ArrayList<>();
                    if (status != null) {
                        users.addAll(status.users.keySet());
                    }
                    if (dashboard.getSpec() != null) {
                        dashboard
                                .getSpec()
                                .users
                                .forEach(u -> users.add(DashboardSql.user(dashboard, u.name)));
                    }
                    users.add(Names.of(dashboard).owner());
                    for (String user : new LinkedHashSet<>(users)) {
                        s.execute(DashboardSql.dropUser(user));
                    }
                    Names names = Names.of(dashboard);
                    if (status != null) {
                        for (Map.Entry<String, String> p : status.rowPolicies.entrySet()) {
                            s.execute(
                                    DashboardSql.dropRowPolicy(
                                            p.getKey(), names.database(), p.getValue()));
                        }
                        Set<String> shadows = new TreeSet<>(status.shadowDatabases);
                        status.exposure.forEach(e -> shadows.add(e.view.split("\\.", 2)[0]));
                        for (String shadow : shadows) {
                            s.execute(DashboardSql.dropDatabase(shadow));
                        }
                    }
                    s.execute(DashboardSql.dropDatabase(names.database()));
                    s.execute(DashboardSql.dropCatalog(names.catalog()));
                    s.execute(DashboardSql.dropWorkloadGroup(names.group()));
                }
            }
            identities.remove(dashboard, KIND);
        } catch (SQLException | CatalogException | KubernetesClientException e) {
            LOG.warn("Cleaning up {} failed; retrying", dashboard.getMetadata().getName(), e);
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(RESYNC);
        }
        metrics.forget(
                KIND
                        + ":"
                        + dashboard.getMetadata().getNamespace()
                        + "/"
                        + dashboard.getMetadata().getName());
        events.normal(dashboard, "Deleted", "Doris objects, users and identity removed");
        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<LakeDashboard> updateErrorStatus(
            LakeDashboard dashboard, Context<LakeDashboard> context, Exception e) {
        LakeDashboardStatus status =
                dashboard.getStatus() == null ? new LakeDashboardStatus() : dashboard.getStatus();
        status.phase = "Degraded";
        status.message =
                "reconcile failed: " + JobObservation.abbreviate(String.valueOf(e.getMessage()));
        dashboard.setStatus(status);
        publish(dashboard, status);
        return ErrorStatusUpdateControl.patchStatus(dashboard);
    }
}
