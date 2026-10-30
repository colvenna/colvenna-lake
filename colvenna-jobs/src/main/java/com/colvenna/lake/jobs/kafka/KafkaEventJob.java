package com.colvenna.lake.jobs.kafka;

import com.colvenna.lake.jobs.Catalogs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.kafka.shaded.org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.LogicalTypeConversion;
import org.apache.paimon.flink.sink.FlinkSinkBuilder;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.RowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A declared Kafka event stream (HLD 8): records parsed against the declared fields and rules, late
 * ones told apart by the watermark, accepted events into a partitioned append table and every other
 * record into the rejections table, both committed with each checkpoint. Records are parsed in the
 * source, so the watermark follows each partition's event times and an idle partition does not hold
 * it back. The Kafka password comes from the environment (a Secret), never from arguments or SQL.
 */
public final class KafkaEventJob {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventJob.class);
    private static final OutputTag<EventParser.Rejection> REJECTED =
            new OutputTag<EventParser.Rejection>("rejected") {};

    private KafkaEventJob() {}

    /** A record as parsed: an event or a rejection. */
    public static final class Parsed implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public EventParser.Event event;
        public EventParser.Rejection rejection;

        public Parsed() {}

        static Parsed of(EventParser.Result result) {
            Parsed p = new Parsed();
            p.event = result.event;
            p.rejection = result.rejection;
            return p;
        }

        long timestamp() {
            // A rejection has no event time to move the watermark with.
            return event == null ? Long.MIN_VALUE : event.eventTimeMs;
        }
    }

    /** Parses each record, except those a table already holds (StartingOffsets). */
    static final class Deserializer implements KafkaRecordDeserializationSchema<Parsed> {
        private static final long serialVersionUID = 1L;
        private final EventParser parser;
        private final StartingOffsets.Resume resume;

        Deserializer(EventSpec spec, StartingOffsets.Resume resume) {
            this.parser = new EventParser(spec);
            this.resume = resume;
        }

        @Override
        public void deserialize(ConsumerRecord<byte[], byte[]> r, Collector<Parsed> out) {
            if (resume.holds(r.topic(), r.partition(), r.offset())) {
                return;
            }
            EventParser.Source source =
                    new EventParser.Source(r.topic(), r.partition(), r.offset());
            out.collect(Parsed.of(parser.parse(r.value(), source, System.currentTimeMillis())));
        }

        @Override
        public TypeInformation<Parsed> getProducedType() {
            return TypeInformation.of(Parsed.class);
        }
    }

    /** Events on time go on; rejections, and events behind the watermark (LATE), aside. */
    static final class Route extends ProcessFunction<Parsed, EventParser.Event> {
        private static final long serialVersionUID = 1L;

        @Override
        public void processElement(Parsed p, Context ctx, Collector<EventParser.Event> out) {
            if (p.rejection != null) {
                ctx.output(REJECTED, p.rejection);
            } else if (EventParser.isLate(
                    p.event.eventTimeMs, ctx.timerService().currentWatermark())) {
                ctx.output(
                        REJECTED,
                        new EventParser.Rejection(
                                "LATE", null, System.currentTimeMillis(), p.event.source));
            } else {
                out.collect(p.event);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: KafkaEventJob <spec-json>");
        }
        EventSpec spec = EventSpec.parse(args[0]);
        FileStoreTable events;
        FileStoreTable rejections;
        StartingOffsets.Resume resume;
        try (Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(Options.fromMap(Catalogs.restOptions())))) {
            events =
                    (FileStoreTable) catalog.getTable(Identifier.create(spec.database, spec.table));
            rejections =
                    (FileStoreTable)
                            catalog.getTable(Identifier.create(spec.database, spec.rejections));
            requireSchema(spec.table, events.rowType(), spec.eventRowType());
            requireSchema(spec.rejections, rejections.rowType(), EventSpec.rejectionRowType());
            // Only for partitions the job has no state for: a restored job keeps its own.
            resume = StartingOffsets.resume(List.of(events, rejections));
        }
        LOG.info(
                "KAFKA_EVENTS_START next={} heldBeyondAgreedCommit={}",
                resume.next,
                resume.written.values().stream().mapToInt(java.util.Set::size).sum());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<Parsed> parsed =
                env.fromSource(
                        source(spec, resume),
                        WatermarkStrategy.<Parsed>forBoundedOutOfOrderness(
                                        Duration.ofMillis(spec.maxOutOfOrdernessMs))
                                .withTimestampAssigner((p, previous) -> p.timestamp())
                                .withIdleness(Duration.ofMillis(spec.idlenessMs)),
                        "kafka");
        SingleOutputStreamOperator<EventParser.Event> onTime =
                parsed.process(new Route()).returns(TypeInformation.of(EventParser.Event.class));
        RowType eventType = spec.eventRowType();
        RowType rejectionType = EventSpec.rejectionRowType();
        DataStream<Row> eventRows = onTime.map(e -> Row.of(e.values)).returns(typeInfo(eventType));
        DataStream<Row> rejectionRows =
                onTime.getSideOutput(REJECTED)
                        .map(KafkaEventJob::rejectionRow)
                        .returns(typeInfo(rejectionType));
        new FlinkSinkBuilder(events).forRow(eventRows, flinkType(eventType)).build();
        new FlinkSinkBuilder(rejections).forRow(rejectionRows, flinkType(rejectionType)).build();
        env.execute("colvenna-kafka-events " + spec.database + "." + spec.table);
    }

    static Row rejectionRow(EventParser.Rejection r) {
        return Row.of(
                EventParser.dateOf(r.detectedAtMs),
                r.reason,
                r.field,
                r.detectedAtMs,
                r.source.topic,
                r.source.partition,
                r.source.offset);
    }

    private static KafkaSource<Parsed> source(EventSpec spec, StartingOffsets.Resume resume) {
        return KafkaSource.<Parsed>builder()
                .setTopics(spec.topics)
                // The group carries committed offsets for lag; the tables decide where to start.
                .setGroupId(spec.group)
                .setStartingOffsets(
                        new StartingOffsets.Positions(
                                resume, "latest".equals(spec.startingOffsets)))
                .setDeserializer(new Deserializer(spec, resume))
                .setProperties(KafkaLogin.properties(spec.bootstrap, spec.mechanism, spec.caFile))
                .build();
    }

    /**
     * The existing table must have the declared columns; colvenna-operator creates and evolves it.
     */
    static void requireSchema(String table, RowType actual, RowType want) {
        List<String> a = new ArrayList<>();
        for (DataField f : actual.getFields()) {
            a.add(f.name() + " " + f.type().copy(true));
        }
        List<String> w = new ArrayList<>();
        for (DataField f : want.getFields()) {
            w.add(f.name() + " " + f.type().copy(true));
        }
        if (!a.equals(w)) {
            throw new IllegalStateException(
                    "SCHEMA_MISMATCH: " + table + " has " + a + " but the declaration needs " + w);
        }
    }

    private static org.apache.flink.table.types.DataType flinkType(RowType type) {
        return TypeConversions.fromLogicalToDataType(LogicalTypeConversion.toLogicalType(type));
    }

    private static TypeInformation<Row> typeInfo(RowType type) {
        String[] names = type.getFieldNames().toArray(new String[0]);
        TypeInformation<?>[] types = new TypeInformation<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = typeInfo(type.getTypeAt(i));
        }
        return Types.ROW_NAMED(names, types);
    }

    private static TypeInformation<?> typeInfo(org.apache.paimon.types.DataType type) {
        switch (type.getTypeRoot()) {
            case VARCHAR:
            case CHAR:
                return Types.STRING;
            case BOOLEAN:
                return Types.BOOLEAN;
            case INTEGER:
                return Types.INT;
            case BIGINT:
                return Types.LONG;
            case DOUBLE:
                return Types.DOUBLE;
            case DECIMAL:
                return Types.BIG_DEC;
            default:
                throw new IllegalArgumentException("Unsupported event column type " + type);
        }
    }
}
