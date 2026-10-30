package com.colvenna.lake.operator.source;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.types.ObjectId;

/**
 * A MongoDB source over TLS: whether it can serve change streams (a replica set or a sharded
 * cluster, a supported version, the user's find and changeStream privileges), how far back its
 * oplog reaches, and its collections, each keyed by {@code _id} (HLD 7.2).
 */
public final class MongoProbe implements SourceProbe {
    /** Flink CDC 3.5's MongoDB source supports these server versions. */
    static final int MIN_MAJOR = 4;

    static final int MAX_MAJOR = 7;

    /** Documents sampled for the type of {@code _id}. */
    static final int ID_SAMPLE = 1000;

    private final boolean tls;

    public MongoProbe() {
        this(true);
    }

    /** Without TLS: tests only, against a server that offers none. */
    MongoProbe(boolean tls) {
        this.tls = tls;
    }

    MongoClient client(Target target, Login login) throws Exception {
        MongoClientSettings.Builder settings =
                MongoClientSettings.builder()
                        // Collection UUIDs as UUIDs: the identity the status keeps.
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .applyConnectionString(
                                new ConnectionString(
                                        "mongodb://" + target.host() + ":" + target.port()))
                        .applyToClusterSettings(c -> c.serverSelectionTimeout(15, TimeUnit.SECONDS))
                        .applyToSocketSettings(
                                s ->
                                        s.connectTimeout(10, TimeUnit.SECONDS)
                                                .readTimeout(300, TimeUnit.SECONDS));
        if (login != null) {
            settings.credential(
                    MongoCredential.createCredential(
                            login.user(), target.authSource(), login.password().toCharArray()));
        }
        if (tls) {
            SSLContext context =
                    target.caFile() == null
                            ? SSLContext.getDefault()
                            : Tls.sslContext(target.caFile());
            settings.applyToSslSettings(s -> s.enabled(true).context(context));
        }
        return MongoClients.create(settings.build());
    }

    @Override
    public SourceModel.Inventory inventory(Target target) throws SQLException {
        try (MongoClient client = client(target, target.replication())) {
            MongoDatabase admin = client.getDatabase("admin");
            List<SourceModel.Check> checks = new ArrayList<>();
            Document hello = admin.runCommand(new Document("hello", 1));
            // Sharded clusters also stream changes, but nothing here was verified on one.
            boolean replicated = hello.containsKey("setName");
            checks.add(
                    new SourceModel.Check(
                            "replica_set",
                            replicated,
                            replicated
                                    ? "replica set " + hello.getString("setName")
                                    : "isdbgrid".equals(hello.getString("msg"))
                                            ? "sharded clusters are not supported in this version"
                                            : "a standalone server has no change streams"));
            String version = admin.runCommand(new Document("buildInfo", 1)).getString("version");
            int major = Integer.parseInt(version.split("\\.")[0]);
            checks.add(
                    new SourceModel.Check(
                            "version",
                            major >= MIN_MAJOR && major <= MAX_MAJOR,
                            "MongoDB "
                                    + version
                                    + " (supported: "
                                    + MIN_MAJOR
                                    + ".0 to "
                                    + MAX_MAJOR
                                    + ".x)"));
            Set<String> actions = actions(admin, target.database());
            boolean granted = actions.contains("find") && actions.contains("changeStream");
            checks.add(
                    new SourceModel.Check(
                            "privileges",
                            granted,
                            granted
                                    ? "find and changeStream on " + target.database()
                                    : "the user lacks find or changeStream on "
                                            + target.database()
                                            + " (has "
                                            + actions
                                            + ")"));
            MongoDatabase db = client.getDatabase(target.database());
            Map<String, SourceModel.Table> tables = new TreeMap<>();
            for (Document info : db.listCollections()) {
                String name = info.getString("name");
                if (name.startsWith("system.") || !"collection".equals(info.getString("type"))) {
                    continue;
                }
                Document details = info.get("info", Document.class);
                Object uuid = details == null ? null : details.get("uuid");
                tables.put(name, table(db, name, uuid == null ? null : uuid.toString()));
            }
            return new SourceModel.Inventory(checks, tables, retention(client));
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(String.valueOf(e.getMessage()), e);
        }
    }

    /** The actions the user holds on the database (or on every database). */
    static Set<String> actions(MongoDatabase admin, String database) {
        Document status =
                admin.runCommand(
                        new Document("connectionStatus", 1).append("showPrivileges", true));
        Set<String> out = new TreeSet<>();
        Document auth = status.get("authInfo", Document.class);
        if (auth == null) {
            return out;
        }
        for (Document p : auth.getList("authenticatedUserPrivileges", Document.class, List.of())) {
            Document resource = p.get("resource", Document.class);
            // Cluster resources ({cluster: true}) carry no database.
            String db = resource == null ? null : resource.getString("db");
            boolean covers =
                    resource != null
                            && (Boolean.TRUE.equals(resource.getBoolean("anyResource"))
                                    || (db != null
                                            && (db.isEmpty() || db.equals(database))
                                            && "".equals(resource.getString("collection"))));
            if (covers) {
                out.addAll(p.getList("actions", String.class, List.of()));
            }
        }
        return out;
    }

    /**
     * One collection: its key, the BSON type of {@code _id} in a sample of documents (the job
     * checks every change's key too), and its UUID, which changes when the collection is replaced.
     */
    static SourceModel.Table table(MongoDatabase db, String name, String uuid) {
        Set<String> types = new TreeSet<>();
        try (MongoCursor<Document> docs =
                db.getCollection(name)
                        .find()
                        .projection(Projections.include("_id"))
                        .limit(ID_SAMPLE)
                        .iterator()) {
            while (docs.hasNext()) {
                Object id = docs.next().get("_id");
                types.add(
                        id instanceof ObjectId
                                ? "objectId"
                                : id instanceof String
                                        ? "string"
                                        : id == null ? "null" : id.getClass().getSimpleName());
            }
        }
        String type = types.isEmpty() ? "objectId" : String.join("+", types);
        return new SourceModel.Table(
                name,
                List.of(new SourceModel.Column("_id", type, type, false)),
                List.of("_id"),
                List.of(),
                List.of(),
                true,
                null,
                uuid);
    }

    /** The oplog's span, from its first to its last entry. */
    static SourceModel.Retention retention(MongoClient client) {
        try {
            var oplog = client.getDatabase("local").getCollection("oplog.rs");
            Document first = oplog.find().sort(new Document("$natural", 1)).limit(1).first();
            Document last = oplog.find().sort(new Document("$natural", -1)).limit(1).first();
            if (first == null || last == null) {
                return new SourceModel.Retention(null, "the oplog is empty");
            }
            long from = first.get("ts", BsonTimestamp.class).getTime();
            long to = last.get("ts", BsonTimestamp.class).getTime();
            return new SourceModel.Retention((to - from) / 3600.0, "local.oplog.rs");
        } catch (MongoCommandException e) {
            return new SourceModel.Retention(
                    null,
                    "the user may not read local.oplog.rs (grant read on local): "
                            + e.getErrorCodeName());
        } catch (MongoException e) {
            return new SourceModel.Retention(null, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public Map<String, Long> versions(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            long limit)
            throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (MongoClient client = client(target, target.replication());
                MongoCursor<Document> docs =
                        client.getDatabase(target.database())
                                .getCollection(table.name())
                                .find()
                                .projection(Projections.include("_id"))
                                .iterator()) {
            while (docs.hasNext()) {
                if (out.size() >= limit) {
                    throw new TooManyRowsException(
                            table.name() + " has more than " + limit + " documents");
                }
                out.put(Keys.encode(List.of(idText(docs.next().get("_id")))), 0L);
            }
            return out;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(String.valueOf(e.getMessage()), e);
        }
    }

    @Override
    public Map<String, Long> versionsOf(
            Target target,
            SourceModel.Table table,
            List<String> keyColumns,
            String versionColumn,
            List<List<String>> keys)
            throws SQLException {
        Map<String, Long> out = new HashMap<>();
        List<Object> ids = new ArrayList<>();
        for (List<String> key : keys) {
            String id = key.get(0);
            ids.add(
                    table.columns().get(0).type().equals("objectId") && ObjectId.isValid(id)
                            ? new ObjectId(id)
                            : id);
        }
        try (MongoClient client = client(target, target.replication());
                MongoCursor<Document> docs =
                        client.getDatabase(target.database())
                                .getCollection(table.name())
                                .find(Filters.in("_id", ids))
                                .projection(Projections.include("_id"))
                                .iterator()) {
            while (docs.hasNext()) {
                out.put(Keys.encode(List.of(idText(docs.next().get("_id")))), 0L);
            }
            return out;
        } catch (Exception e) {
            throw new SQLException(String.valueOf(e.getMessage()), e);
        }
    }

    /** {@code _id} as the Paimon target holds it: an ObjectId's hex, a string as it is. */
    static String idText(Object id) {
        return id instanceof ObjectId ? ((ObjectId) id).toHexString() : String.valueOf(id);
    }
}
