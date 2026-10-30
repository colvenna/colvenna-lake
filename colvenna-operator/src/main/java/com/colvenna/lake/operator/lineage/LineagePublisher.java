package com.colvenna.lake.operator.lineage;

import com.colvenna.lake.operator.crd.LakeDashboard;
import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.crd.LakeTask;
import com.colvenna.lake.operator.lineage.LineageGraph.Column;
import com.colvenna.lake.operator.lineage.LineageGraph.Graph;
import com.colvenna.lake.operator.lineage.LineageGraph.Naming;
import com.colvenna.lake.operator.lineage.LineageGraph.Run;
import com.colvenna.lake.operator.metrics.Metrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports each managed namespace's lineage to an OpenLineage endpoint, on a schedule, while the
 * operator leads (LLD "血缘"): a run seen for the first time is STARTed, a running run that changed
 * (a column added, another table read) is reported RUNNING with what it is now, a finished one
 * COMPLETE or FAIL, and a run whose job went away or runs another version is ended before the new
 * version starts. What was reported is kept (a {@link Store}), so a restarted operator ends the
 * runs its predecessor started; an event the endpoint did not take is sent again next time.
 */
public final class LineagePublisher {
    private static final Logger LOG = LoggerFactory.getLogger(LineagePublisher.class);

    /** Where events go. */
    public interface Sink {
        void send(JsonNode event) throws IOException;
    }

    /** What was reported of each namespace's runs, by run id. */
    public interface Store {
        Map<String, Reported> load(String namespace);

        void save(String namespace, Map<String, Reported> runs);
    }

    /** A run as last reported: its job and what it is, whether it is still open, how it looked. */
    public record Reported(
            String job, boolean open, LineageGraph.JobType kind, String fingerprint) {}

    /** An event the endpoint refused as wrong (400, 409, 413, 415, 422): not sent again. */
    public static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;

        public Rejected(String message) {
            super(message);
        }
    }

    private final List<String> namespaces;
    private final Function<String, LineageGraph.Namespace> lister;
    private final BiFunction<String, String, Optional<JsonNode>> tables;
    private final Naming naming;
    private final Sink sink;
    private final Store store;
    private final Metrics metrics;
    private final Supplier<Instant> clock;
    private final Duration every;
    private final Map<String, Long> sent = new HashMap<>();
    private final Map<String, Long> failures = new HashMap<>();
    private final Map<String, Long> rejected = new HashMap<>();
    private ScheduledExecutorService schedule;

    public LineagePublisher(
            Set<String> namespaces,
            Function<String, LineageGraph.Namespace> lister,
            BiFunction<String, String, Optional<JsonNode>> tables,
            Naming naming,
            Sink sink,
            Store store,
            Metrics metrics,
            Supplier<Instant> clock,
            Duration every) {
        this.namespaces = new ArrayList<>(new TreeSet<>(namespaces));
        this.lister = lister;
        this.tables = tables;
        this.naming = naming;
        this.sink = sink;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
        this.every = every;
    }

    public synchronized void start() {
        if (schedule != null) {
            return;
        }
        schedule =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "lineage");
                            t.setDaemon(true);
                            return t;
                        });
        // publishAll throws nothing: a run that did would end the schedule.
        var unused =
                schedule.scheduleWithFixedDelay(
                        this::publishAll, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (schedule != null) {
            schedule.shutdownNow();
            schedule = null;
        }
    }

    void publishAll() {
        for (String ns : namespaces) {
            try {
                publish(ns);
            } catch (RuntimeException e) {
                LOG.warn("lineage of {} not reported: {}", ns, e.toString());
                failures.merge(ns, 1L, Long::sum);
                samples(ns, null, null);
            }
        }
    }

    /** Reports one namespace: what changed since the last report. */
    void publish(String ns) {
        // The catalog's tables, each read once per report.
        Map<String, Optional<List<Column>>> seen = new HashMap<>();
        Graph graph =
                LineageGraph.of(
                        lister.apply(ns),
                        table -> seen.computeIfAbsent(table, this::schema),
                        naming);
        Gaps gaps =
                new Gaps(
                        graph.unsettled().size(),
                        seen.values().stream().filter(Optional::isEmpty).count());
        String jobNamespace = Naming.jobNamespace(ns);
        Map<String, Reported> reported = new LinkedHashMap<>(store.load(ns));
        Set<String> current = new HashSet<>();
        graph.runs().forEach(r -> current.add(r.id()));
        try {
            for (Run run : graph.runs()) {
                Reported was = reported.get(run.id());
                String fingerprint = OpenLineageEvents.fingerprint(jobNamespace, run);
                if (was == null) {
                    // A new version ends what its job ran before, first (the catalog reads a
                    // streaming job's datasets from its latest events), and as of when the new
                    // one started: the catalog takes the latest event for the job's latest run.
                    endOthers(ns, jobNamespace, reported, current, run.job().name(), start(run));
                    send(
                            ns,
                            OpenLineageEvents.event(
                                    jobNamespace, run, LineageGraph.START, start(run)));
                    if (!run.state().equals(LineageGraph.RUNNING)) {
                        send(ns, OpenLineageEvents.event(jobNamespace, run, run.state(), end(run)));
                    }
                } else if (was.open() && !run.state().equals(LineageGraph.RUNNING)) {
                    send(ns, OpenLineageEvents.event(jobNamespace, run, run.state(), end(run)));
                } else if (was.open() && !was.fingerprint().equals(fingerprint)) {
                    send(
                            ns,
                            OpenLineageEvents.event(
                                    jobNamespace, run, LineageGraph.RUNNING, clock.get()));
                } else if (!was.open() && run.state().equals(LineageGraph.RUNNING)) {
                    // Ended while it was away (suspended), and back as it was.
                    send(
                            ns,
                            OpenLineageEvents.event(
                                    jobNamespace, run, LineageGraph.START, clock.get()));
                } else {
                    continue;
                }
                reported.put(
                        run.id(),
                        new Reported(
                                run.job().name(),
                                run.state().equals(LineageGraph.RUNNING),
                                run.job().kind(),
                                fingerprint));
            }
            for (Map.Entry<String, Reported> e : new ArrayList<>(reported.entrySet())) {
                if (!current.contains(e.getKey())
                        && !graph.unsettled().contains(e.getValue().job())) {
                    end(ns, jobNamespace, reported, e.getKey(), clock.get());
                    reported.remove(e.getKey());
                }
            }
        } catch (IOException e) {
            failures.merge(ns, 1L, Long::sum);
            LOG.warn("lineage of {}: {}", ns, e.getMessage());
            store.save(ns, reported);
            samples(ns, reported, gaps);
            return;
        }
        store.save(ns, reported);
        samples(ns, reported, gaps);
        metrics.publish(
                "lineage:" + ns + ":success",
                List.of(
                        new Metrics.Sample(
                                "colvenna_lineage_last_success_timestamp_seconds",
                                "when the namespace's lineage was last reported in full",
                                Map.of("namespace", ns),
                                clock.get().getEpochSecond())));
    }

    /** Ends, as of {@code at}, the open runs of a job that are not current. */
    private void endOthers(
            String ns,
            String jobNamespace,
            Map<String, Reported> reported,
            Set<String> current,
            String job,
            Instant at)
            throws IOException {
        for (Map.Entry<String, Reported> e : reported.entrySet()) {
            if (e.getValue().job().equals(job) && !current.contains(e.getKey())) {
                end(ns, jobNamespace, reported, e.getKey(), at);
            }
        }
    }

    /** Ends a run known only by its report, if it is open: its job ran another version. */
    private void end(
            String ns,
            String jobNamespace,
            Map<String, Reported> reported,
            String runId,
            Instant at)
            throws IOException {
        Reported r = reported.get(runId);
        if (r.open()) {
            send(
                    ns,
                    OpenLineageEvents.end(
                            jobNamespace, r.job(), runId, r.kind(), LineageGraph.COMPLETE, at));
            reported.put(runId, new Reported(r.job(), false, r.kind(), r.fingerprint()));
        }
    }

    private Instant start(Run run) {
        return run.startedAt() != null ? run.startedAt() : clock.get();
    }

    private Instant end(Run run) {
        return run.endedAt() != null ? run.endedAt() : clock.get();
    }

    /**
     * Sends an event. One the endpoint refuses for good is counted and logged, and the report goes
     * on: sent again it would be refused again, and would hold back every event after it.
     */
    private void send(String ns, JsonNode event) throws IOException {
        try {
            sink.send(event);
            sent.merge(ns, 1L, Long::sum);
        } catch (Rejected e) {
            rejected.merge(ns, 1L, Long::sum);
            LOG.warn(
                    "lineage of {}: {} of {} refused: {}",
                    ns,
                    event.path("eventType").asText(),
                    event.path("job").path("name").asText(),
                    e.getMessage());
        }
    }

    /**
     * What a report leaves out for now: the jobs in transition (their last run stands), and the
     * lake tables colvenna-catalog did not return (reported without columns).
     */
    private record Gaps(long unsettled, long unknownTables) {}

    private void samples(String ns, Map<String, Reported> reported, Gaps gaps) {
        Map<String, String> labels = Map.of("namespace", ns);
        List<Metrics.Sample> out = new ArrayList<>();
        if (gaps != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_lineage_unsettled_jobs",
                            "jobs in transition, whose last reported run stands",
                            labels,
                            gaps.unsettled()));
            out.add(
                    new Metrics.Sample(
                            "colvenna_lineage_tables_without_columns",
                            "lake tables reported without columns: colvenna-catalog did not return them",
                            labels,
                            gaps.unknownTables()));
        }
        out.add(
                new Metrics.Sample(
                        "colvenna_lineage_events_sent",
                        "lineage events the endpoint took since the operator started",
                        labels,
                        sent.getOrDefault(ns, 0L)));
        out.add(
                new Metrics.Sample(
                        "colvenna_lineage_events_rejected",
                        "lineage events the endpoint refused for good since the operator started",
                        labels,
                        rejected.getOrDefault(ns, 0L)));
        out.add(
                new Metrics.Sample(
                        "colvenna_lineage_failures",
                        "lineage reports that failed since the operator started",
                        labels,
                        failures.getOrDefault(ns, 0L)));
        if (reported != null) {
            out.add(
                    new Metrics.Sample(
                            "colvenna_lineage_open_runs",
                            "runs reported started and not yet ended",
                            labels,
                            reported.values().stream().filter(Reported::open).count()));
        }
        metrics.publish("lineage:" + ns, out);
    }

    /** A namespace's declarations and deployments, as the API server lists them. */
    public static Function<String, LineageGraph.Namespace> lister(KubernetesClient kube) {
        return ns ->
                new LineageGraph.Namespace(
                        ns,
                        kube.resources(LakeSource.class).inNamespace(ns).list().getItems(),
                        kube.resources(LakeTable.class).inNamespace(ns).list().getItems(),
                        kube.resources(LakeTask.class).inNamespace(ns).list().getItems(),
                        kube.resources(LakeDashboard.class).inNamespace(ns).list().getItems(),
                        kube.resources(FlinkDeployment.class).inNamespace(ns).list().getItems());
    }

    private Optional<List<Column>> schema(String table) {
        String[] parts = table.split("\\.", 2);
        return tables.apply(parts[0], parts[1]).map(LineagePublisher::columns);
    }

    /**
     * A table's columns as colvenna-catalog returns them: its fields' names, types and comments.
     */
    static List<Column> columns(JsonNode table) {
        List<Column> columns = new ArrayList<>();
        for (JsonNode f : table.path("schema").path("fields")) {
            JsonNode type = f.path("type");
            columns.add(
                    new Column(
                            f.path("name").asText(),
                            type.isTextual() ? type.asText() : type.toString(),
                            f.hasNonNull("description") ? f.path("description").asText() : null));
        }
        return columns;
    }
}
