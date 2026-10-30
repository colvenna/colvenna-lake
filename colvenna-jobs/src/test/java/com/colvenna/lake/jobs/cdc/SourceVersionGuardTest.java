package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.flink.util.Collector;
import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.junit.jupiter.api.Test;

class SourceVersionGuardTest {
    private static final Collector<RichCdcMultiplexRecord> DISCARD =
            new Collector<RichCdcMultiplexRecord>() {
                @Override
                public void collect(RichCdcMultiplexRecord value) {}

                @Override
                public void close() {}
            };

    private static CdcSourceRecord change(String op, String before, String after) {
        return new CdcSourceRecord(
                "shop.orders",
                null,
                "{\"payload\":{\"op\":\""
                        + op
                        + "\",\"source\":{\"table\":\"orders\"},\"before\":"
                        + before
                        + ",\"after\":"
                        + after
                        + "}}");
    }

    @Test
    void mysqlUpdatesMustRaiseTheDeclaredVersion() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        SourceVersionGuard guard =
                new SourceVersionGuard(
                        (s, out) -> accepted.incrementAndGet(), "rev", List.of(), false);

        IllegalStateException lower =
                assertThrows(
                        IllegalStateException.class,
                        () -> guard.flatMap(change("u", "{\"rev\":2}", "{\"rev\":1}"), DISCARD));
        assertTrue(lower.getMessage().startsWith("NON_MONOTONIC_SOURCE_VERSION table=orders"));
        assertThrows(
                IllegalStateException.class,
                () -> guard.flatMap(change("u", "{\"rev\":2}", "{\"rev\":2}"), DISCARD));
        IllegalStateException missing =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                guard.flatMap(
                                        change("u", "{\"version_no\":2}", "{\"version_no\":3}"),
                                        DISCARD));
        assertTrue(
                missing.getMessage().startsWith("SOURCE_VERSION_MISSING table=orders column=rev"));
        assertEquals(0, accepted.get());

        guard.flatMap(change("u", "{\"rev\":2}", "{\"rev\":3}"), DISCARD);
        guard.flatMap(change("c", "null", "{\"rev\":1}"), DISCARD);
        // MySQL deletes carry the full row, and nothing about them can regress a version.
        guard.flatMap(change("d", "{\"rev\":3}", "null"), DISCARD);
        assertEquals(3, accepted.get());
    }

    @Test
    void postgresChangesNeedTheFullBeforeImageAndAStableKey() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        SourceVersionGuard guard =
                new SourceVersionGuard(
                        (s, out) -> accepted.incrementAndGet(),
                        "version_no",
                        List.of("tenant", "id"),
                        true);

        // REPLICA IDENTITY DEFAULT: the before image has the key only.
        IllegalStateException partial =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                guard.flatMap(
                                        change("d", "{\"tenant\":1,\"id\":7}", "null"), DISCARD));
        assertTrue(partial.getMessage().startsWith("SOURCE_BEFORE_IMAGE_INCOMPLETE"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        guard.flatMap(
                                change(
                                        "u",
                                        "{\"tenant\":1,\"version_no\":1}",
                                        "{\"tenant\":1,\"id\":7,\"version_no\":2}"),
                                DISCARD));
        IllegalStateException moved =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                guard.flatMap(
                                        change(
                                                "u",
                                                "{\"tenant\":1,\"id\":7,\"version_no\":1}",
                                                "{\"tenant\":1,\"id\":8,\"version_no\":2}"),
                                        DISCARD));
        assertTrue(moved.getMessage().startsWith("SOURCE_KEY_CHANGED table=orders key=id"));
        assertEquals(0, accepted.get());

        guard.flatMap(
                change(
                        "u",
                        "{\"tenant\":1,\"id\":7,\"version_no\":1}",
                        "{\"tenant\":1,\"id\":7,\"version_no\":2}"),
                DISCARD);
        guard.flatMap(change("d", "{\"tenant\":1,\"id\":7,\"version_no\":2}", "null"), DISCARD);
        assertEquals(2, accepted.get());
    }

    @Test
    void nonJsonRecordsFail() {
        SourceVersionGuard guard =
                new SourceVersionGuard((s, out) -> {}, "version_no", List.of(), false);
        assertThrows(
                IllegalArgumentException.class,
                () -> guard.flatMap(new CdcSourceRecord("t", null, 42), DISCARD));
    }

    @Test
    void readsKafkasAlreadyParsedRecordsToo() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        SourceVersionGuard guard =
                new SourceVersionGuard(
                        (s, out) -> accepted.incrementAndGet(), "rev", List.of(), false);
        CdcSourceRecord text = change("u", "{\"rev\":2}", "{\"rev\":1}");
        CdcSourceRecord parsed =
                new CdcSourceRecord(
                        text.getTopic(),
                        null,
                        new org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind
                                        .ObjectMapper()
                                .readTree((String) text.getValue()));
        assertThrows(IllegalStateException.class, () -> guard.flatMap(parsed, DISCARD));
        assertEquals(0, accepted.get());
    }
}
