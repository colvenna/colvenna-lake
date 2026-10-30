package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The MongoDB probe against a real single-node replica set: what admission and reconciliation read.
 */
@Testcontainers
class MongoProbeTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0").withReplicaSet();

    @Test
    void inventoryShowsTheStreamsPrerequisitesCollectionsAndOplog() throws Exception {
        ObjectId first = new ObjectId();
        try (MongoClient root = MongoClients.create(MONGO.getReplicaSetUrl())) {
            MongoDatabase shop = root.getDatabase("shop");
            shop.getCollection("orders")
                    .insertMany(
                            List.of(
                                    new Document("_id", first).append("state", "paid"),
                                    new Document("state", "new")));
            shop.getCollection("customers")
                    .insertOne(new Document("_id", "c-1").append("name", "Ann"));
            shop.getCollection("ledger").insertOne(new Document("_id", 7).append("amount", 1));
            root.getDatabase("admin")
                    .runCommand(
                            new Document("createRole", "cdc")
                                    .append(
                                            "privileges",
                                            List.of(
                                                    new Document(
                                                                    "resource",
                                                                    new Document("db", "")
                                                                            .append(
                                                                                    "collection",
                                                                                    ""))
                                                            .append(
                                                                    "actions",
                                                                    List.of(
                                                                            "find",
                                                                            "changeStream",
                                                                            "listCollections",
                                                                            "listDatabases",
                                                                            "splitVector",
                                                                            "collStats"))))
                                    .append("roles", List.of()));
            root.getDatabase("admin")
                    .runCommand(
                            new Document("createUser", "colvenna_cdc")
                                    .append("pwd", "secret")
                                    .append(
                                            "roles",
                                            List.of(
                                                    new Document("role", "cdc")
                                                            .append("db", "admin"),
                                                    new Document("role", "read")
                                                            .append("db", "local"))));
        }
        SourceProbe.Target target =
                new SourceProbe.Target(
                        MONGO.getHost(),
                        MONGO.getMappedPort(27017),
                        "shop",
                        null,
                        null,
                        new SourceProbe.Login("colvenna_cdc", "secret"),
                        null,
                        null,
                        "admin");
        MongoProbe probe = new MongoProbe(false);
        SourceModel.Inventory inventory = probe.inventory(target);
        assertThat(inventory.checks()).allMatch(SourceModel.Check::ok);
        assertThat(inventory.tables()).containsOnlyKeys("orders", "customers", "ledger");
        assertThat(inventory.tables().get("orders").column("_id").orElseThrow().type())
                .isEqualTo("objectId");
        assertThat(inventory.retention().windowHours()).isNotNull().isGreaterThanOrEqualTo(0.0);
        // The collection's UUID, which changes when the collection is replaced.
        assertThat(inventory.tables().get("orders").sourceId()).matches("[0-9a-f-]{36}");

        assertThat(Admission.check("mongodb", inventory.tables().get("orders"), null).admitted())
                .isTrue();
        assertThat(Admission.check("mongodb", inventory.tables().get("customers"), null).admitted())
                .isTrue();
        assertThat(Admission.check("mongodb", inventory.tables().get("ledger"), null).reason())
                .isEqualTo("UnsupportedKeyType");

        Map<String, Long> versions =
                probe.versions(target, inventory.tables().get("orders"), List.of("_id"), null, 10);
        assertThat(versions)
                .hasSize(2)
                .containsEntry(Keys.encode(List.of(first.toHexString())), 0L);
        assertThat(
                        probe.versionsOf(
                                target,
                                inventory.tables().get("orders"),
                                List.of("_id"),
                                null,
                                List.of(
                                        List.of(first.toHexString()),
                                        List.of(new ObjectId().toHexString()))))
                .containsOnlyKeys(Keys.encode(List.of(first.toHexString())));
    }

    @Test
    void aUserWithoutTheStreamPrivilegeIsRefused() throws Exception {
        try (MongoClient root = MongoClients.create(MONGO.getReplicaSetUrl())) {
            root.getDatabase("admin")
                    .runCommand(
                            new Document("createUser", "reader")
                                    .append("pwd", "secret")
                                    .append(
                                            "roles",
                                            List.of(
                                                    new Document("role", "read")
                                                            .append("db", "other"))));
        }
        SourceModel.Inventory inventory =
                new MongoProbe(false)
                        .inventory(
                                new SourceProbe.Target(
                                        MONGO.getHost(),
                                        MONGO.getMappedPort(27017),
                                        "shop",
                                        null,
                                        null,
                                        new SourceProbe.Login("reader", "secret"),
                                        null,
                                        null,
                                        "admin"));
        // The container does not enforce access control, so the user still reads the oplog; the
        // privileges it was granted are what the check reads.
        assertThat(inventory.failedChecks()).contains("privileges");
    }
}
