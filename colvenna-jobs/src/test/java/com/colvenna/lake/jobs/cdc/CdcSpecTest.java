package com.colvenna.lake.jobs.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CdcSpecTest {
    private static final String MYSQL =
            "{\"targetDatabase\":\"ods_shop\",\"host\":\"db.internal\",\"port\":3306,"
                    + "\"database\":\"shop\",\"serverId\":\"6000-6001\","
                    + "\"tables\":[\"orders\",\"order$items\"],\"versionColumn\":\"version_no\","
                    + "\"tableOptions\":{\"bucket\":\"2\",\"write-only\":\"true\"}}";

    @Test
    void parsesTheOperatorsDeclaration() {
        CdcSpec spec = CdcSpec.parse(MYSQL);
        assertEquals("ods_shop", spec.targetDatabase);
        assertEquals(List.of("orders", "order$items"), spec.tables);
        assertEquals(null, spec.sourceCa);
        assertEquals(Map.of("bucket", "2", "write-only", "true"), spec.tableOptions);
        // Exact names: '$' and anything else regex-special match only themselves.
        assertEquals("\\Qorders\\E|\\Qorder$items\\E", spec.tableRegex());
        assertEquals(true, "order$items".matches(spec.tableRegex()));
        assertEquals(false, "orders_archive".matches(spec.tableRegex()));
    }

    @Test
    void rejectsNamesThatAreNotPlainIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(MYSQL.replace("\"orders\"", "\"orders.*\"")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(MYSQL.replace("ods_shop", "ods;drop")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(MYSQL.replace("[\"orders\",\"order$items\"]", "[]")));
    }

    @Test
    void theSourceCaIsAnAbsolutePath() {
        assertEquals(
                "/source-ca/ca.pem",
                CdcSpec.parse(
                                MYSQL.replace(
                                        "\"port\"", "\"sourceCa\":\"/source-ca/ca.pem\",\"port\""))
                        .sourceCa);
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(MYSQL.replace("\"port\"", "\"sourceCa\":\"ca.pem\",\"port\"")));
    }

    @Test
    void kafkaNamesATargetPerTopicAndBoundsTheOverlap() {
        String kafka =
                MYSQL.replace(
                        "\"tableOptions\"",
                        "\"kafka\":{\"bootstrap\":\"k:9093\",\"group\":\"g\","
                                + "\"targets\":{\"cdc.shop.orders\":\"orders\"}},"
                                + "\"tableOptions\"");
        CdcSpec spec = CdcSpec.parse(kafka);
        assertEquals(Map.of("cdc.shop.orders", "orders"), spec.kafka.targets);
        assertEquals(List.of("cdc.shop.orders"), spec.kafka.topics());
        assertEquals(600_000L, spec.kafka.overlapMillis);
        assertEquals("SCRAM-SHA-512", spec.kafka.mechanism);
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(kafka.replace("{\"cdc.shop.orders\":\"orders\"}", "{}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CdcSpec.parse(kafka.replace(":\"orders\"}", ":\"orders;x\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CdcSpec.parse(
                                kafka.replace("\"group\"", "\"overlapMillis\":1000,\"group\"")));
    }

    @Test
    void mongodbHasNoVersionColumn() {
        CdcSpec spec =
                CdcSpec.parse(
                        "{\"targetDatabase\":\"ods_shop\",\"host\":\"mongo\",\"port\":27017,"
                                + "\"database\":\"shop\",\"tables\":[\"orders\"],"
                                + "\"authSource\":\"admin\"}");
        assertEquals(null, spec.versionColumn);
        assertEquals("admin", spec.authSource);
        assertEquals("tls=true&authSource=admin", MongoCdcJob.connectionOptions(spec));
        assertThrows(IllegalArgumentException.class, spec::requireVersionColumn);
    }
}
