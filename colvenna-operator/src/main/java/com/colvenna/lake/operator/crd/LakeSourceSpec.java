package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a user declares about a source. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ValidationRule(
        value = "self.type == 'kafka' || has(self.connection)",
        message = "a mysql, postgres or mongodb source declares its connection")
@ValidationRule(
        value = "self.type != 'postgres' || has(self.connection.publication)",
        message = "a postgres source names the publication its tables are in")
@ValidationRule(
        value = "self.type != 'postgres' || has(self.connection.schema)",
        message = "a postgres source names its schema")
@ValidationRule(
        value =
                "self.type != 'kafka' || (has(self.kafka) && has(self.events) && has(self.target.table))",
        message = "a kafka source declares kafka, events and target.table")
@ValidationRule(
        value =
                "!has(self.monitoring) || !has(self.monitoring.heartbeat) || self.type == 'mysql'"
                        + " || self.type == 'postgres'",
        message = "a heartbeat is written to a mysql or postgres source only")
public class LakeSourceSpec {

    @Required
    @Pattern("^(mysql|postgres|mongodb|kafka)$")
    @JsonPropertyDescription(
            "Source kind: mysql, postgres, mongodb or kafka (an event stream, HLD 8).")
    public String type;

    @JsonPropertyDescription(
            "Stop the ingestion job, keeping its position, until set back to false.")
    public Boolean suspend;

    @JsonPropertyDescription("mysql, postgres and mongodb: how to reach the database.")
    public Connection connection;

    @JsonPropertyDescription(
            "mysql, postgres and mongodb: which tables (collections) to ingest. Defaults to all.")
    public Tables tables;

    @JsonPropertyDescription("kafka: the cluster and topics, provided by the user.")
    public Kafka kafka;

    @JsonPropertyDescription(
            "mysql: read the changes from the user's Kafka (Debezium JSON with schemas) instead of"
                    + " the database's log. The connection is still used for admission and"
                    + " reconciliation.")
    public Transport transport;

    @JsonPropertyDescription("kafka: the fields every event carries and the rules they keep.")
    public Events events;

    @Required public Target target;

    public Job job;

    @JsonPropertyDescription(
            "The data's owner, level, categories and subject keys (HLD 12.5). Without it the"
                    + " tables are treated as unclassified (temporary L4) and not published.")
    public Governance governance;

    @Size(max = 32)
    @JsonPropertyDescription(
            "Catalog principals the tables are published to (table-level READ), while governed.")
    public List<@Pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$") String> readers = new ArrayList<>();

    @Pattern("^(Retain|Delete)$")
    @Default("Retain")
    @JsonPropertyDescription(
            "What happens to the target tables when this source is deleted: Retain (default)"
                    + " keeps them, Delete drops them.")
    public String deletionPolicy;

    @JsonPropertyDescription("How the source's latency to the lake is measured (LLD \"监控与数据质量\").")
    public Monitoring monitoring;

    /** The source's monitoring. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Monitoring {
        @JsonPropertyDescription(
                "mysql and postgres: a table the operator writes each round, a synthetic change"
                        + " the lake's latency is measured by (the acceptance contract's 2.1).")
        public Heartbeat heartbeat;
    }

    /** A heartbeat table, one of the source's tables, and who may write it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Heartbeat {
        @Required
        @Pattern("^[A-Za-z0-9_]{1,64}$")
        @JsonPropertyDescription(
                "The table (id INT primary key, beat_at DATETIME(6) or TIMESTAMP, and the version"
                        + " column), included in tables like any other.")
        public String table;

        @Required
        @JsonPropertyDescription("A user that may insert and update that table, and nothing more.")
        public SecretRef credentialsSecret;
    }

    /** How to reach the source. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Connection {
        @Required
        @Pattern("^[A-Za-z0-9.-]{1,253}$")
        public String host;

        @Min(1)
        @Max(65535)
        @JsonPropertyDescription(
                "Defaults to 3306 for mysql, 5432 for postgres and 27017 for mongodb.")
        public Integer port;

        @Required
        @Pattern("^[A-Za-z0-9_$]{1,64}$")
        @JsonPropertyDescription("The source database.")
        public String database;

        @Pattern("^[A-Za-z0-9_$]{1,63}$")
        @JsonPropertyDescription("postgres: the schema of the tables.")
        public String schema;

        @Pattern("^[A-Za-z0-9_]{1,63}$")
        @JsonPropertyDescription(
                "postgres: the publication, owned by the source, that contains the tables.")
        public String publication;

        @Required
        @JsonPropertyDescription(
                "Secret in this namespace with the replication user (keys username, password).")
        public SecretRef credentialsSecret;

        @JsonPropertyDescription(
                "Optional Secret with a user that may read trigger definitions, used only for"
                        + " admission checks. mysql shows triggers only to users with the TRIGGER"
                        + " privilege.")
        public SecretRef inspectorSecret;

        @Pattern("^[A-Za-z0-9/_+-]{1,64}$")
        @JsonPropertyDescription("mysql: the server's time zone, for TIMESTAMP columns.")
        public String serverTimeZone;

        @Pattern("^[A-Za-z0-9_-]{1,64}$")
        @JsonPropertyDescription("mongodb: the database the user is defined in.")
        public String authSource;

        @Pattern("^[1-9][0-9]{0,4}h$")
        @Default("24h")
        @JsonPropertyDescription(
                "mongodb: the oplog must reach at least this far back; a job down longer than the"
                        + " oplog reaches cannot resume. Shorter raises a Warning.")
        public String minOplogWindow;

        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        @JsonPropertyDescription(
                "ConfigMap in this namespace with the source's CA bundle (key ca.pem). With it,"
                        + " the operator and the jobs verify the server certificate and host name;"
                        + " without it, TLS is required, unverified for mysql and postgres and"
                        + " checked against the JVM's default trust store for mongodb.")
        public String caConfigMap;
    }

    /** A Secret holding a user name and password. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SecretRef {
        @Required
        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        public String name;

        @Default("username")
        public String usernameKey;

        @Default("password")
        public String passwordKey;
    }

    /** A user's Kafka cluster: SASL/SCRAM over TLS. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Kafka {
        @Required
        @Pattern("^[A-Za-z0-9.:,-]{1,1000}$")
        @JsonPropertyDescription("host:port[,host:port...] of the TLS listener.")
        public String bootstrapServers;

        @Required
        @Size(min = 1, max = 16)
        public List<@Pattern("^[A-Za-z0-9._-]{1,249}$") String> topics = new ArrayList<>();

        @Required
        @JsonPropertyDescription(
                "Secret in this namespace with the SASL user (username, password).")
        public SecretRef credentialsSecret;

        @Pattern("^(SCRAM-SHA-(256|512)|PLAIN)$")
        @Default("SCRAM-SHA-512")
        public String mechanism;

        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        @JsonPropertyDescription(
                "ConfigMap in this namespace with the cluster's CA (key ca.pem); without it the"
                        + " JVM's default trust store decides.")
        public String caConfigMap;

        @Pattern("^(earliest|latest)$")
        @Default("earliest")
        @JsonPropertyDescription("Where to start a partition no table has read from yet.")
        public String startingOffsets;
    }

    /** Where a database source's changes come from. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Transport {
        @Required public KafkaTransport kafka;
    }

    /** A user's Kafka carrying a Debezium connector's topics, one per table. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KafkaTransport {
        @Required
        @Pattern("^[A-Za-z0-9.:,-]{1,1000}$")
        public String bootstrapServers;

        @Required
        @Pattern("^[A-Za-z0-9._-]{1,200}$")
        @JsonPropertyDescription(
                "The connector's topic.prefix: a table's changes are in"
                        + " <prefix>.<database>.<table>.")
        public String topicPrefix;

        @Required
        @JsonPropertyDescription(
                "Secret in this namespace with the SCRAM user (username, password).")
        public SecretRef credentialsSecret;

        @Pattern("^SCRAM-SHA-(256|512)$")
        @Default("SCRAM-SHA-512")
        public String mechanism;

        @Pattern("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
        @JsonPropertyDescription("ConfigMap in this namespace with the cluster's CA (key ca.pem).")
        public String caConfigMap;

        @Pattern("^[0-9]{1,4}(s|m|h)$")
        @Default("10m")
        @JsonPropertyDescription(
                "How far before the targets' last write a job without state starts reading (1m to"
                        + " 24h): longer than the previous writer's lag when it stopped and than"
                        + " the connector's lag. Changes read twice are absorbed.")
        public String overlap;
    }

    /** The events' fields, event time and rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Events {
        @Required
        @Size(min = 1, max = 256)
        public List<Field> fields = new ArrayList<>();

        @Required public EventTime eventTime;

        @Pattern("^[1-9][0-9]{0,4}(s|m|h)$")
        @Default("5m")
        @JsonPropertyDescription(
                "How far events may arrive out of order before they count as late.")
        public String maxOutOfOrderness;

        @Pattern("^[1-9][0-9]{0,4}(s|m|h)$")
        @Default("1m")
        @JsonPropertyDescription(
                "After this long without records a partition no longer holds back the watermark.")
        public String idleness;

        @Pattern("^[1-9][0-9]{0,4}(h|d)$")
        @Default("30d")
        @JsonPropertyDescription("Events older than this are rejected (TOO_OLD).")
        public String acceptPast;

        @Pattern("^[1-9][0-9]{0,4}(m|h|d)$")
        @Default("1d")
        @JsonPropertyDescription("Events this far in the future are rejected (TOO_NEW).")
        public String acceptFuture;

        @Pattern("^(keep|reject)$")
        @Default("keep")
        @JsonPropertyDescription("Undeclared fields: keep them in extra_json, or reject the event.")
        public String unknownFields;
    }

    /** One declared field and its rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Field {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        public String name;

        @Required
        @Pattern("^(STRING|BOOLEAN|INT|BIGINT|DOUBLE|DECIMAL\\([0-9]{1,2}, ?[0-9]{1,2}\\))$")
        public String type;

        @JsonPropertyDescription("Reject events without it (MISSING_FIELD).")
        public Boolean required;

        @Min(1)
        @JsonPropertyDescription("STRING: the most characters (TOO_LONG).")
        public Integer maxLength;

        @JsonPropertyDescription("STRING: the values allowed (NOT_ALLOWED_VALUE).")
        public List<String> values = new ArrayList<>();

        @JsonPropertyDescription("Numbers: the range allowed (OUT_OF_RANGE).")
        public Double min;

        public Double max;
    }

    /** Which field holds the event time, and how. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventTime {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        public String field;

        @Pattern("^(rfc3339|epoch-millis)$")
        @Default("rfc3339")
        public String format;
    }

    /** Table selection: regular expressions matched against whole source table names. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tables {
        public List<String> include = new ArrayList<>();
        public List<String> exclude = new ArrayList<>();
    }

    /** Where and how the tables land. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Target {
        @Required
        @Pattern("^[a-z][a-z0-9_]{0,62}$")
        @JsonPropertyDescription(
                "The Paimon database the tables are written to, owned by this source alone.")
        public String database;

        @Pattern("^[A-Za-z0-9_]{1,64}$")
        @JsonPropertyDescription(
                "mysql and postgres: the BIGINT column every source table carries and raises on"
                        + " each update (HLD 7.4), version_no unless declared; it orders changes in"
                        + " the target. mongodb has none: its change stream is ordered.")
        public String versionColumn;

        @Min(1)
        @Max(1024)
        @Default("1")
        public Integer bucket;

        @JsonPropertyDescription("Extra Paimon table options; cannot override the managed ones.")
        public Map<String, String> tableOptions = new LinkedHashMap<>();

        @Pattern("^[a-z][a-z0-9_]{0,57}$")
        @JsonPropertyDescription(
                "kafka: the events table; rejected records go to <table>_rejections.")
        public String table;

        @Pattern("^(daily|hourly)$")
        @Default("daily")
        @JsonPropertyDescription("kafka: partitions by event date (dt), or date and hour (dt, hr).")
        public String partitioning;
    }

    /** Ingestion job sizing. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Job {
        @Min(1)
        @Max(64)
        @Default("1")
        public Integer parallelism;

        @Pattern("^[1-9][0-9]{0,4}(s|m)$")
        @Default("30s")
        @JsonPropertyDescription("Checkpoint interval; also the lower bound of visibility delay.")
        public String checkpointInterval;

        public Resources jobManager;
        public Resources taskManager;
    }

    /** CPU and memory of a Flink process. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Resources {
        @Min(0)
        @Max(64)
        public Double cpu;

        @Pattern("^[1-9][0-9]{0,5}(m|g)$")
        @JsonPropertyDescription("Flink memory, for example 2048m.")
        public String memory;
    }
}
