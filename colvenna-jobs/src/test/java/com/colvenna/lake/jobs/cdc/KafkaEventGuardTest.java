package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KafkaEventGuardTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KEY = List.of("id");

    private static JsonNode change(String op, long id, long version) throws Exception {
        String image = "{\"id\":" + id + ",\"version_no\":" + version + "}";
        return JSON.readTree(
                op.equals("d")
                        ? "{\"op\":\"d\",\"before\":" + image + ",\"after\":null}"
                        : "{\"op\":\"" + op + "\",\"before\":null,\"after\":" + image + "}");
    }

    private static boolean stale(
            Map<String, Long> seen, Map<String, Long> deleted, JsonNode payload) {
        return KafkaEventGuard.isStale(seen, deleted, "orders", KEY, null, payload, "version_no");
    }

    @Test
    void aVersionSeenAgainOrOlderIsStale() throws Exception {
        Map<String, Long> seen = KafkaEventGuard.recentVersions();
        Map<String, Long> deleted = Map.of();
        assertFalse(stale(seen, deleted, change("c", 1, 1)));
        assertTrue(stale(seen, deleted, change("c", 1, 1)), "a duplicate");
        assertFalse(stale(seen, deleted, change("u", 1, 3)));
        assertTrue(stale(seen, deleted, change("u", 1, 2)), "out of order");
        // Other rows are not affected.
        assertFalse(stale(seen, deleted, change("c", 2, 1)));
    }

    @Test
    void aDeleteCarriesTheVersionItRemoves() throws Exception {
        Map<String, Long> seen = KafkaEventGuard.recentVersions();
        Map<String, Long> deleted = Map.of();
        assertFalse(stale(seen, deleted, change("c", 1, 4)));
        assertFalse(stale(seen, deleted, change("d", 1, 4)));
        // Replayed, the row's last version does not bring it back.
        assertTrue(stale(seen, deleted, change("u", 1, 4)));
        assertTrue(stale(seen, deleted, change("d", 1, 3)));
        // Inserted again under the contract: a greater version.
        assertFalse(stale(seen, deleted, change("c", 1, 5)));
    }

    @Test
    void theTargetsRecentDeletesAreRemembered() throws Exception {
        Map<String, Long> deleted = new HashMap<>();
        deleted.put(KafkaEventGuard.rowKey("orders", List.of("7")), 3L);
        Map<String, Long> seen = KafkaEventGuard.recentVersions();
        // The overlap replays the row's history: none of it comes back.
        assertTrue(stale(seen, deleted, change("c", 7, 1)));
        assertTrue(stale(seen, deleted, change("u", 7, 3)));
        assertTrue(stale(seen, deleted, change("d", 7, 3)));
        assertFalse(stale(seen, deleted, change("c", 7, 4)));
        assertFalse(stale(seen, deleted, change("c", 8, 1)));
    }

    @Test
    void noJudgementWithoutTheRowsKeyOrVersion() throws Exception {
        Map<String, Long> seen = KafkaEventGuard.recentVersions();
        JsonNode otherKey = JSON.readTree("{\"op\":\"c\",\"after\":{\"ID\":1,\"version_no\":1}}");
        assertFalse(stale(seen, Map.of(), otherKey));
        assertFalse(stale(seen, Map.of(), otherKey), "never one key for every row");
        JsonNode noVersion = JSON.readTree("{\"op\":\"c\",\"after\":{\"id\":1}}");
        assertFalse(stale(seen, Map.of(), noVersion));
        assertFalse(stale(seen, Map.of(), noVersion));
    }

    @Test
    void aTableNotCreatedYetIsKnownByItsRecordKey() throws Exception {
        Map<String, Long> seen = KafkaEventGuard.recentVersions();
        JsonNode key = JSON.readTree("{\"id\":1}");
        assertFalse(
                KafkaEventGuard.isStale(
                        seen, Map.of(), "orders", null, key, change("c", 1, 1), "version_no"));
        assertTrue(
                KafkaEventGuard.isStale(
                        seen, Map.of(), "orders", null, key, change("c", 1, 1), "version_no"));
    }

    @Test
    void rowKeysDoNotRunTogether() {
        assertNotEquals(
                KafkaEventGuard.rowKey("t", List.of("a|1", "2")),
                KafkaEventGuard.rowKey("t", List.of("a", "1|2")));
        assertEquals(
                KafkaEventGuard.rowKey("t", List.of("1")),
                KafkaEventGuard.rowKey("t", List.of("1")));
    }

    @Test
    void aSnapshotAfterTheTargetsWereWrittenOrStreamedIsTheCollectorSnapshottingAgain()
            throws Exception {
        KafkaEventGuard guard =
                new KafkaEventGuard(
                        null,
                        null,
                        "version_no",
                        Map.of(),
                        new TargetHistory(1_000L, Set.of(), Map.of(), Map.of()),
                        0);
        assertTrue(guard.resnapshot("orders#0", source("true", 2_000)));
        assertTrue(guard.resnapshot("orders#0", source("last", 2_000)));
        // The connector's first snapshot, before the targets' last write.
        assertFalse(guard.resnapshot("orders#0", source("true", 500)));
        // An incremental snapshot keeps the connector's position.
        assertFalse(guard.resnapshot("orders#0", source("incremental", 2_000)));
        // Once a partition streamed here, a snapshot in it is a new one, whenever it was taken;
        // another partition may still be reading the first snapshot.
        assertFalse(guard.resnapshot("orders#0", source("false", 400)));
        assertTrue(guard.resnapshot("orders#0", source("true", 500)));
        assertFalse(guard.resnapshot("orders#1", source("true", 500)));
        // Targets never written and nothing streamed: every snapshot is the first.
        KafkaEventGuard fresh =
                new KafkaEventGuard(null, null, "version_no", Map.of(), TargetHistory.none(), 0);
        assertFalse(fresh.resnapshot("orders#0", source("true", 2_000)));
    }

    private static JsonNode source(String snapshot, long tsMillis) throws Exception {
        return JSON.readTree(
                "{\"op\":\"r\",\"source\":{\"snapshot\":\""
                        + snapshot
                        + "\",\"ts_ms\":"
                        + tsMillis
                        + "}}");
    }
}
