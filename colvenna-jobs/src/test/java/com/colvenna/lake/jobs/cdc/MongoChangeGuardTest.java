package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.paimon.flink.action.cdc.CdcSourceRecord;
import org.junit.jupiter.api.Test;

class MongoChangeGuardTest {
    private static CdcSourceRecord change(String op, String fullDocument) {
        return new CdcSourceRecord(
                "{\"operationType\":\""
                        + op
                        + "\",\"ns\":{\"db\":\"shop\",\"coll\":\"orders\"},"
                        + "\"documentKey\":\"{\\\"_id\\\": \\\"o-1\\\"}\""
                        + (fullDocument == null ? "" : ",\"fullDocument\":" + fullDocument)
                        + "}");
    }

    @Test
    void changesTheParserTakesPassOn() throws Exception {
        assertFalse(MongoChangeGuard.skip(change("insert", "\"{\\\"_id\\\": 1}\"")));
        assertFalse(MongoChangeGuard.skip(change("update", "\"{\\\"_id\\\": 1}\"")));
        assertFalse(MongoChangeGuard.skip(change("replace", "\"{\\\"_id\\\": 1}\"")));
        assertFalse(MongoChangeGuard.skip(change("delete", null)));
    }

    @Test
    void anUpdateOfADocumentAlreadyDeletedIsSkipped() throws Exception {
        assertTrue(MongoChangeGuard.skip(change("update", null)));
        assertTrue(MongoChangeGuard.skip(change("update", "null")));
    }

    @Test
    void aKeyTheTargetCannotKeepStopsTheJobNamingTheCollection() throws Exception {
        CdcSourceRecord objectId =
                new CdcSourceRecord(
                        "{\"operationType\":\"insert\",\"ns\":{\"coll\":\"orders\"},"
                                + "\"documentKey\":\"{\\\"_id\\\": {\\\"$oid\\\": \\\"65f0\\\"}}\","
                                + "\"fullDocument\":\"{}\"}");
        assertFalse(MongoChangeGuard.skip(objectId));
        CdcSourceRecord number =
                new CdcSourceRecord(
                        "{\"operationType\":\"insert\",\"ns\":{\"coll\":\"orders\"},"
                                + "\"documentKey\":\"{\\\"_id\\\": 5}\",\"fullDocument\":\"{}\"}");
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> MongoChangeGuard.skip(number));
        assertEquals("SOURCE_UNSUPPORTED_KEY table=orders id=NUMBER", e.getMessage());
    }
}
