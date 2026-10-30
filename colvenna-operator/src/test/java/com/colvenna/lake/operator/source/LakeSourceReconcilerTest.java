package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import com.colvenna.lake.operator.crd.LakeSourceStatus;
import com.colvenna.lake.operator.lake.LakeReader;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LakeSourceReconcilerTest {

    static LakeSourceSpec spec() {
        LakeSourceSpec spec = new LakeSourceSpec();
        spec.type = "mysql";
        spec.connection = new LakeSourceSpec.Connection();
        spec.connection.host = "db";
        spec.connection.database = "shop";
        spec.target = new LakeSourceSpec.Target();
        spec.target.database = "ods_shop";
        return spec;
    }

    @Test
    void onlyRetentionAndLayoutOptionsMayBeDeclared() {
        LakeSourceSpec spec = spec();
        assertThat(LakeSourceReconciler.validate(spec)).isNull();
        for (String key :
                List.of(
                        "sequence.field",
                        "ignore-delete",
                        "merge-engine",
                        "fields.a.aggregate-function")) {
            spec.target.tableOptions = Map.of(key, "x");
            assertThat(LakeSourceReconciler.validate(spec))
                    .as(key)
                    .contains(key + " is not allowed");
        }
        spec.target.tableOptions =
                Map.of("snapshot.time-retained", "1 h", "file-index.bloom-filter.columns", "id");
        assertThat(LakeSourceReconciler.validate(spec)).isNull();
        spec.tables = new LakeSourceSpec.Tables();
        spec.tables.include = List.of("orders(");
        assertThat(LakeSourceReconciler.validate(spec)).contains("invalid regular expression");
    }

    @Test
    void theTargetIsAnInputChangelogTableOrderedByTheVersion() {
        LakeSourceSpec spec = spec();
        spec.target.versionColumn = "rev";
        spec.target.bucket = 4;
        spec.target.tableOptions = Map.of("snapshot.time-retained", "1 h");
        assertThat(SourceJobs.tableOptions(spec))
                .containsEntry("bucket", "4")
                .containsEntry("changelog-producer", "input")
                .containsEntry("write-only", "true")
                .containsEntry("sequence.field", "rev")
                .containsEntry("merge-engine", "deduplicate")
                .containsEntry("consumer.expiration-time", "1 d")
                .containsEntry("snapshot.time-retained", "1 h");
    }

    @Test
    void failuresAreAttributedToTheTablesTheyName() {
        String schema =
                "org.apache.flink.runtime.JobException: Recovery is suppressed\n"
                        + "Caused by: java.lang.UnsupportedOperationException: Cannot convert"
                        + " field amount from type DECIMAL(12, 2) NOT NULL to VARCHAR(32) NOT NULL"
                        + " of Paimon table ods_shop.invoices.\n";
        assertThat(LakeSourceReconciler.tablesNamed(schema, "ods_shop"))
                .containsExactly("invoices");
        assertThat(LakeSourceReconciler.tablesNamed(schema, "ods_other")).isEmpty();
        String guard =
                "Caused by: java.lang.IllegalStateException: NON_MONOTONIC_SOURCE_VERSION"
                        + " table=orders before=3 after=2";
        assertThat(LakeSourceReconciler.tablesNamed(guard, "ods_shop")).containsExactly("orders");
        assertThat(LakeSourceReconciler.deterministic(schema)).isTrue();
        assertThat(LakeSourceReconciler.deterministic("Cannot update primary key requestId:x"))
                .isTrue();
        assertThat(LakeSourceReconciler.deterministic("java.net.SocketTimeoutException")).isFalse();
    }

    @Test
    void namesFitFlinkAndPostgresLimits() {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName("a-very-long-source-name-that-keeps-going")
                        .withNamespace("colvenna-data")
                        .withUid("2b111fc6-1234-5678-9abc-def012345678")
                        .build());
        String job = SourceJobs.postgresJobName(source, "a_long_table_name_as_well");
        assertThat(job.length()).isLessThanOrEqualTo(45);
        assertThat(job).matches("[a-z0-9-]+");
        String slot = SourceJobs.slotName(source, "t".repeat(80));
        assertThat(slot).startsWith("colvenna_2b111fc6_").hasSizeLessThanOrEqualTo(63);
        // The longest table name kept whole, and two tables that differ only past the cut.
        String whole = "t".repeat(63 - "colvenna_2b111fc6_".length());
        assertThat(SourceJobs.slotName(source, whole)).isEqualTo("colvenna_2b111fc6_" + whole);
        assertThat(SourceJobs.slotName(source, whole + "a"))
                .hasSize(63)
                .matches("[a-z0-9_]+")
                .isNotEqualTo(SourceJobs.slotName(source, whole + "b"));
        // A cut that would end in _ does not put a second one before the hash.
        assertThat(SourceJobs.slotName(source, "t".repeat(35) + "_" + "u".repeat(20)))
                .doesNotContain("__")
                .startsWith("colvenna_2b111fc6_" + "t".repeat(35) + "_");
    }

    @Test
    void eachHeartbeatCountsToTheFirstCommitThatHoldsIt() {
        LakeSourceStatus.HeartbeatState state = new LakeSourceStatus.HeartbeatState();
        Instant t0 = Instant.parse("2026-10-15T10:00:00Z");
        state.pending.addAll(
                List.of(
                        t0.toString(),
                        t0.plusSeconds(30).toString(),
                        t0.plusSeconds(60).toString()));
        // One commit holds the first two beats, 70 s after the first; the next holds the third.
        LakeReader.Beat fifth =
                new LakeReader.Beat(
                        5, t0.plusSeconds(30).toEpochMilli(), t0.plusSeconds(70).toEpochMilli());
        LakeReader.Beat sixth =
                new LakeReader.Beat(
                        6, t0.plusSeconds(60).toEpochMilli(), t0.plusSeconds(95).toEpochMilli());
        Long longest =
                LakeSourceReconciler.seen(
                        state, new LakeReader.Beats(List.of(fifth, sixth), sixth, false));
        assertThat(longest).isEqualTo(70L);
        assertThat(state.pending).isEmpty();
        assertThat(state.seenSnapshot).isEqualTo(6L);
        assertThat(state.beatAt).isEqualTo(t0.plusSeconds(60).toString());
        // Nothing new: no sample.
        assertThat(LakeSourceReconciler.seen(state, new LakeReader.Beats(List.of(), sixth, false)))
                .isNull();
        // The table made again: what waited was for the old one.
        state.pending.add(t0.plusSeconds(90).toString());
        LakeReader.Beat first =
                new LakeReader.Beat(
                        1, t0.plusSeconds(600).toEpochMilli(), t0.plusSeconds(610).toEpochMilli());
        assertThat(
                        LakeSourceReconciler.seen(
                                state, new LakeReader.Beats(List.of(first), first, true)))
                .isNull();
        assertThat(state.pending).isEmpty();
        assertThat(state.seenSnapshot).isEqualTo(1L);
    }

    static LakeSourceStatus beating(Instant beatAt, String... jobTables) {
        LakeSourceStatus status = new LakeSourceStatus();
        LakeSourceStatus.JobState job = new LakeSourceStatus.JobState();
        job.tables.addAll(List.of(jobTables));
        status.jobs.add(job);
        status.heartbeat = new LakeSourceStatus.HeartbeatState();
        status.heartbeat.ok = true;
        status.heartbeat.beatAt = beatAt.toString();
        status.heartbeat.committedAt = beatAt.plusSeconds(20).toString();
        return status;
    }

    static LakeSourceSpec beatingSpec() {
        LakeSourceSpec spec = spec();
        spec.monitoring = new LakeSourceSpec.Monitoring();
        spec.monitoring.heartbeat = new LakeSourceSpec.Heartbeat();
        spec.monitoring.heartbeat.table = "colvenna_heartbeat";
        return spec;
    }

    @Test
    void anIdleMysqlTableHasCaughtUpOnceTheJobsHeartbeatIsPastTheSourceRead() {
        LakeSourceSpec spec = beatingSpec();
        Instant due = Instant.parse("2026-10-24T03:00:00Z");
        LakeSourceStatus status = beating(due.minusSeconds(1), "orders", "colvenna_heartbeat");
        // The lake's newest heartbeat was written before the source read was due: not yet.
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        status = beating(due.plusSeconds(5), "orders", "colvenna_heartbeat");
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .contains("heartbeat");
        // Not with a skewed clock, a failing heartbeat, or another job.
        status.heartbeat.skewed = true;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        status.heartbeat.skewed = null;
        status.heartbeat.ok = false;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        status.heartbeat.ok = true;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "invoices", due))
                .isNull();
        // PostgreSQL gives each table its own job and slot, MongoDB has no heartbeat: only commits.
        LakeSourceStatus apart = beating(due.plusSeconds(5), "orders");
        apart.jobs.add(new LakeSourceStatus.JobState());
        apart.jobs.get(1).tables.add("colvenna_heartbeat");
        spec.type = "postgres";
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, apart, "orders", due)).isNull();
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        spec.type = "mongodb";
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        // Without a declared heartbeat only a commit counts.
        spec = spec();
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
    }

    @Test
    void throughKafkaTheHeartbeatAndTheTablesWholeTopicMustBeIn() {
        LakeSourceSpec spec = beatingSpec();
        spec.transport = new LakeSourceSpec.Transport();
        spec.transport.kafka = new LakeSourceSpec.KafkaTransport();
        spec.transport.kafka.topicPrefix = "cdc";
        Instant due = Instant.parse("2026-10-24T03:00:00Z");
        LakeSourceStatus status = beating(due.plusSeconds(5), "orders", "colvenna_heartbeat");
        status.kafka = new LakeSourceStatus.KafkaState();
        status.kafka.checkedAt = due.plusSeconds(60).toString();
        LakeSourceStatus.TopicState orders = new LakeSourceStatus.TopicState();
        orders.name = "cdc.shop.orders";
        orders.endOffset = 120L;
        orders.committedOffset = 120L;
        orders.lag = 0L;
        status.kafka.topics.add(orders);
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .contains("cdc.shop.orders");
        // Records still to consume; a group past the end (the topic made again) is not caught up.
        orders.committedOffset = 117L;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        orders.committedOffset = 130L;
        orders.lag = 0L;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        orders.committedOffset = 120L;
        // The topic idle because the collector is behind: no heartbeat past the read, no catch-up.
        LakeSourceStatus behind = beating(due.minusSeconds(30), "orders", "colvenna_heartbeat");
        behind.kafka = status.kafka;
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, behind, "orders", due))
                .isNull();
        // A probe from before the heartbeat was committed, or a failed probe.
        status.kafka.checkedAt = due.plusSeconds(10).toString();
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        status.kafka.checkedAt = due.plusSeconds(60).toString();
        status.kafka.reason = "Unreachable";
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
        // Another table's topic says nothing of this one.
        status.kafka.reason = null;
        orders.name = "cdc.shop.colvenna_heartbeat";
        assertThat(LakeSourceReconciler.caughtUpWithoutCommit(spec, status, "orders", due))
                .isNull();
    }

    @Test
    void onlyKeysUnexplainedTwiceInARowAreAGapAndEachGapWarnsOnce() {
        Map<String, Set<String>> gaps = new HashMap<>();
        Map<String, Set<String>> warned = new HashMap<>();
        // First sighting: maybe in flight.
        Set<String> lasting = LakeSourceReconciler.lastingGap(gaps, "t", List.of("a", "b"));
        assertThat(lasting).isEmpty();
        assertThat(LakeSourceReconciler.newGap(warned, "t", lasting)).isFalse();
        // b arrived, a did not; c is new and in flight: the gap is a.
        lasting = LakeSourceReconciler.lastingGap(gaps, "t", List.of("a", "c"));
        assertThat(lasting).containsExactly("a");
        assertThat(LakeSourceReconciler.newGap(warned, "t", lasting)).isTrue();
        // The same gap again: no second Warning, however busy the table is around it.
        lasting = LakeSourceReconciler.lastingGap(gaps, "t", List.of("a", "c", "d"));
        assertThat(lasting).containsExactlyInAnyOrder("a", "c");
        assertThat(LakeSourceReconciler.newGap(warned, "t", lasting)).isTrue();
        lasting = LakeSourceReconciler.lastingGap(gaps, "t", List.of("a", "c"));
        assertThat(LakeSourceReconciler.newGap(warned, "t", lasting)).isFalse();
        // Each table on its own.
        assertThat(LakeSourceReconciler.lastingGap(gaps, "u", List.of("a"))).isEmpty();
    }

    @Test
    void aStalledWaitIsReportedOncePerSourceReadAsAWarningOnlyWithAProgressSignal() {
        Instant due = Instant.parse("2026-10-24T03:00:00Z");
        assertThat(LakeSourceReconciler.stalled(due, due.plusSeconds(599))).isFalse();
        assertThat(LakeSourceReconciler.stalled(due, due.plusSeconds(601))).isTrue();
        Map<String, Instant> reported = new HashMap<>();
        Instant read = due.minusSeconds(60);
        assertThat(LakeSourceReconciler.firstReport(reported, "t", read)).isTrue();
        assertThat(LakeSourceReconciler.firstReport(reported, "t", read)).isFalse();
        // The next pending read is a wait of its own.
        assertThat(LakeSourceReconciler.firstReport(reported, "t", read.plusSeconds(300))).isTrue();
        assertThat(LakeSourceReconciler.firstReport(reported, "u", read)).isTrue();
        // MySQL with its heartbeat in the job: the heartbeat would have shown progress.
        LakeSourceSpec spec = beatingSpec();
        LakeSourceStatus status = beating(due, "orders", "colvenna_heartbeat");
        assertThat(LakeSourceReconciler.progressSignal(spec, status, "orders")).isTrue();
        spec.type = "postgres";
        assertThat(LakeSourceReconciler.progressSignal(spec, status, "orders")).isFalse();
        assertThat(LakeSourceReconciler.progressSignal(spec(), status, "orders")).isFalse();
    }
}
