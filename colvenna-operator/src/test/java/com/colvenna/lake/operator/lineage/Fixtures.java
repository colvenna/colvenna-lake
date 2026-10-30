package com.colvenna.lake.operator.lineage;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeDashboardSpec;
import com.colvenna.lake.operator.crd.LakeDashboardStatus;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTableSpec;
import com.colvenna.lake.operator.crd.LakeTableStatus;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.crd.LakeTaskSpec;
import com.colvenna.lake.operator.crd.LakeTaskStatus;
import com.colvenna.lake.operator.flink.FlinkJobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.spec.FlinkDeploymentSpec;
import org.apache.flink.kubernetes.operator.api.spec.FlinkVersion;
import org.apache.flink.kubernetes.operator.api.spec.JobSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.apache.flink.kubernetes.operator.api.status.ReconciliationState;

/** A MySQL source into ODS, a DWD and a DWS table over it, a backfill and a dashboard. */
final class Fixtures {
    private Fixtures() {}

    static final ObjectMapper JSON = new ObjectMapper();
    static final String NS = "colvenna-data";
    static final LineageGraph.Naming NAMING =
            new LineageGraph.Naming(
                    "paimon://colvenna-catalog.colvenna-catalog.svc:8181",
                    "https://colvenna-catalog.colvenna-catalog.svc:8181",
                    "doris://doris-fe.colvenna-doris.svc:9030");

    static <T extends HasMetadata> T named(T o, String name) {
        o.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid("uid-" + name)
                        .withGeneration(1L)
                        .build());
        return o;
    }

    static LakeSource source() {
        LakeSource s = named(new LakeSource(), "orders-src");
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "mysql";
        spec.connection = new LakeSourceSpec.Connection();
        spec.connection.host = "mysql.example.internal";
        spec.connection.database = "shop";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods";
        s.setSpec(spec);
        LakeSourceStatus status = new LakeSourceStatus();
        for (String t : List.of("orders", "segments")) {
            LakeSourceStatus.SourceTable table = new LakeSourceStatus.SourceTable();
            table.name = t;
            table.target = t;
            table.state = "Ready";
            status.tables.add(table);
        }
        s.setStatus(status);
        return s;
    }

    static LakeTable table(String name, String db, String target, String hash) {
        LakeTable t = named(new LakeTable(), name);
        LakeTableSpec spec = new LakeTableSpec();
        spec.target = new LakeTableSpec.Target();
        spec.target.database = db;
        spec.target.table = target;
        spec.query = "SELECT * FROM somewhere";
        t.setSpec(spec);
        LakeTableStatus status = new LakeTableStatus();
        status.applied = new LakeTableStatus.Applied();
        status.applied.hash = hash;
        status.applied.target = db + "." + target;
        t.setStatus(status);
        return t;
    }

    static LakeTable dwd(String hash) {
        LakeTable t = table("dwd-orders", "dwd", "orders", hash);
        t.getSpec().query =
                "SELECT o.id, s.label FROM ods.orders AS o JOIN ods.segments FOR SYSTEM_TIME AS OF"
                        + " o.pt AS s ON o.customer_id = s.id";
        t.getStatus().applied.streams.add("ods.orders");
        t.getStatus().applied.lookups.add("ods.segments");
        t.getStatus().applied.quarantine = "orders_quarantine";
        return t;
    }

    static LakeTable dws(String hash) {
        LakeTable t = table("dws-segments", "dws", "segments", hash);
        t.getSpec().query = "SELECT label, COUNT(*) FROM dwd.orders GROUP BY label";
        t.getStatus().applied.streams.add("dwd.orders");
        return t;
    }

    /** The owner's Flink deployment: running its spec (or not), with a job id. */
    static FlinkDeployment deployment(
            HasMetadata owner, String name, String hash, String jobId, boolean live, String args) {
        FlinkDeployment fd = new FlinkDeployment();
        fd.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(NS)
                        .withUid("uid-" + name)
                        .withGeneration(2L)
                        .withAnnotations(Map.of(FlinkJobs.SPEC_HASH, hash))
                        .withOwnerReferences(
                                new OwnerReferenceBuilder()
                                        .withApiVersion("colvenna-lake.io/v1alpha1")
                                        .withKind(owner.getKind())
                                        .withName(owner.getMetadata().getName())
                                        .withUid(owner.getMetadata().getUid())
                                        .withController(true)
                                        .build())
                        .build());
        FlinkDeploymentSpec spec = new FlinkDeploymentSpec();
        spec.setImage("registry/colvenna-jobs@sha256:abc");
        spec.setFlinkVersion(FlinkVersion.v1_20);
        spec.setJob(new JobSpec());
        spec.getJob().setState(JobState.RUNNING);
        spec.getJob().setArgs(new String[] {args});
        fd.setSpec(spec);
        FlinkDeploymentStatus status = new FlinkDeploymentStatus();
        // The lifecycle follows from the reconciliation status: deployed, as last written.
        status.getReconciliationStatus().serializeAndSetLastReconciledSpec(spec, fd);
        status.getReconciliationStatus().setState(ReconciliationState.DEPLOYED);
        status.setObservedGeneration(live ? 2L : 1L);
        JobStatus job = new JobStatus();
        job.setJobId(jobId);
        job.setState(org.apache.flink.api.common.JobStatus.RUNNING);
        job.setStartTime("1792200000000");
        status.setJobStatus(job);
        fd.setStatus(status);
        return fd;
    }

    static FlinkDeployment cdc(LakeSource s, String hash, String jobId) {
        return deployment(
                s, "orders-src-cdc", hash, jobId, true, "{\"tables\":[\"orders\",\"segments\"]}");
    }

    /** A table's deployment, its job argument holding the query it runs. */
    static FlinkDeployment tableJob(LakeTable t, String jobId, boolean live) {
        return deployment(
                t,
                t.getMetadata().getName() + "-table",
                t.getStatus().applied.hash,
                jobId,
                live,
                JSON.createObjectNode().put("query", t.getSpec().query).toString());
    }

    static LakeTask backfill(String name, String phase, int attempts, String... ended) {
        LakeTask task = named(new LakeTask(), name);
        LakeTaskSpec spec = new LakeTaskSpec();
        spec.type = "backfill";
        spec.table = new LakeTaskSpec.Table();
        spec.table.database = "dwd";
        spec.table.table = "orders";
        task.setSpec(spec);
        LakeTaskStatus status = new LakeTaskStatus();
        status.phase = phase;
        status.attempts = attempts;
        status.startedAt = "2026-10-17T01:00:00Z";
        status.run = name + "-" + attempts;
        for (int i = 0; i < ended.length; i++) {
            LakeTaskStatus.Attempt a = new LakeTaskStatus.Attempt();
            a.attempt = i + 1;
            a.run = name + "-" + (i + 1);
            a.outcome = ended[i];
            a.finishedAt = "2026-10-17T01:0" + (i + 1) + ":00Z";
            status.history.add(a);
        }
        task.setStatus(status);
        return task;
    }

    static LakeDashboard dashboard() {
        LakeDashboard d = named(new LakeDashboard(), "sales");
        LakeDashboardSpec spec = new LakeDashboardSpec();
        spec.tables.add("dws.segments");
        spec.tables.add("dwd.orders");
        LakeDashboardSpec.Query q = new LakeDashboardSpec.Query();
        q.name = "by_label";
        q.serve = "materialized";
        // Names one of the two tables only; a quoted mention of the other is not a read.
        q.sql = "SELECT label, n FROM dws.segments WHERE label <> 'dwd.orders'";
        spec.queries.add(q);
        d.setSpec(spec);
        LakeDashboardStatus status = new LakeDashboardStatus();
        status.database = "dash_sales";
        LakeDashboardStatus.QueryState served = new LakeDashboardStatus.QueryState();
        served.name = "by_label";
        served.serve = "materialized";
        served.object = "dash_sales.by_label";
        served.applied = "a1";
        status.queries.add(served);
        d.setStatus(status);
        return d;
    }

    /** colvenna-catalog's tables, as its REST API returns them; mutable, for schema changes. */
    static final class Catalog {
        final Map<String, JsonNode> tables = new HashMap<>();

        Catalog() {
            put("ods.orders", "id:BIGINT NOT NULL", "customer_id:BIGINT", "pt:TIMESTAMP(3)");
            put("ods.segments", "id:BIGINT NOT NULL", "label:STRING");
            put("dwd.orders", "id:BIGINT NOT NULL", "label:STRING");
            put("dwd.orders_quarantine", "id:BIGINT", "colvenna_check:STRING");
            put("dws.segments", "label:STRING NOT NULL", "n:BIGINT NOT NULL");
        }

        void put(String table, String... columns) {
            var t = JSON.createObjectNode();
            var fields = t.putObject("schema").putArray("fields");
            int id = 0;
            for (String c : columns) {
                String[] parts = c.split(":", 2);
                fields.addObject().put("id", id++).put("name", parts[0]).put("type", parts[1]);
            }
            tables.put(table, t);
        }

        Optional<JsonNode> get(String db, String table) {
            return Optional.ofNullable(tables.get(db + "." + table));
        }

        Optional<List<LineageGraph.Column>> schema(String table) {
            return Optional.ofNullable(tables.get(table)).map(LineagePublisher::columns);
        }
    }
}
