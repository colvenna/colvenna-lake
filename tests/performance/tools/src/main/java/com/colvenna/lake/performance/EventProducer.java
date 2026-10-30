package com.colvenna.lake.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Sends JSON events to a topic at a fixed rate, open-loop, and writes the broker's receipts of a
 * sample of them: the start of each event's latency is when the broker appended it, never when it
 * was sent.
 *
 * <p>usage: {@code EventProducer <client.properties> <topic> <rate> <seconds> <bytes>
 * <sample-every> <prefix> <stop-file> <out-file>}
 *
 * <p>Event {@code n} (from 0) is {@code {"event_id": "<prefix>-<n>", "seq": n, "pad": "...",
 * "event_time": <ms>}}, about {@code bytes} long, sent when it is due ({@code n / rate} seconds
 * after the start) whether or not the ones before it were acknowledged; at most 20,000 wait for
 * their receipts at once, and a send that waits for room is late. The topic appends with the
 * broker's clock ({@code message.timestamp.type=LogAppendTime}), so a receipt's timestamp is the
 * append time. Lines of {@code <out-file>}:
 *
 * <ul>
 *   <li>{@code ack <n> <partition> <offset> <append-ms> <receipt-ms>} for each {@code n} that
 *       {@code sample-every} divides, {@code receipt-ms} by this pod's clock;
 *   <li>{@code err <n> <error>} for each event the broker did not take;
 *   <li>{@code stat <ms> <sent> <acked> <errors> <late-ms>} every second ({@code late-ms}: how far
 *       behind its schedule the latest send was);
 *   <li>{@code end <sent> <acked> <errors> <partition>:<highest-offset>:<count>...} once every
 *       receipt is in, after {@code seconds} or once {@code <stop-file>} exists.
 * </ul>
 */
public final class EventProducer {
    private static final int IN_FLIGHT = 20_000;

    private EventProducer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            throw new IllegalArgumentException(
                    "usage: EventProducer <client.properties> <topic> <rate> <seconds> <bytes>"
                            + " <sample-every> <prefix> <stop-file> <out-file>");
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
            properties.load(in);
        }
        String topic = args[1];
        long rate = Long.parseLong(args[2]);
        long seconds = Long.parseLong(args[3]);
        int bytes = Integer.parseInt(args[4]);
        long sampleEvery = Long.parseLong(args[5]);
        String prefix = args[6];
        Path stop = Path.of(args[7]);
        if (rate < 1 || seconds < 1 || bytes < 128 || bytes > 2048 || sampleEvery < 1) {
            throw new IllegalArgumentException("rate, seconds >= 1; 128 <= bytes <= 2048");
        }
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        AtomicLong acked = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        Map<Integer, long[]> partitions = new TreeMap<>();
        Semaphore room = new Semaphore(IN_FLIGHT);
        try (BufferedWriter out =
                        Files.newBufferedWriter(Path.of(args[8]), StandardCharsets.UTF_8);
                KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(properties)) {
            long start = System.nanoTime();
            long end = start + seconds * 1_000_000_000L;
            long sent = 0;
            long late = 0;
            long nextStat = System.currentTimeMillis() + 1000;
            while (!Files.exists(stop)) {
                long due = start + sent * 1_000_000_000L / rate;
                if (due >= end) {
                    break;
                }
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                room.acquire();
                late = Math.max(0, (System.nanoTime() - due) / 1_000_000);
                long n = sent;
                long now = System.currentTimeMillis();
                byte[] value = event(prefix, n, now, bytes);
                Future<RecordMetadata> unused =
                        producer.send(
                                new ProducerRecord<>(topic, value),
                                (metadata, e) -> {
                                    room.release();
                                    try {
                                        if (e != null) {
                                            errors.incrementAndGet();
                                            line(
                                                    out,
                                                    "err "
                                                            + n
                                                            + " "
                                                            + e.toString().replace('\n', ' '));
                                            return;
                                        }
                                        acked.incrementAndGet();
                                        synchronized (partitions) {
                                            long[] p =
                                                    partitions.computeIfAbsent(
                                                            metadata.partition(),
                                                            k -> new long[] {-1, 0});
                                            p[0] = Math.max(p[0], metadata.offset());
                                            p[1]++;
                                        }
                                        if (n % sampleEvery == 0) {
                                            line(
                                                    out,
                                                    "ack "
                                                            + n
                                                            + " "
                                                            + metadata.partition()
                                                            + " "
                                                            + metadata.offset()
                                                            + " "
                                                            + metadata.timestamp()
                                                            + " "
                                                            + System.currentTimeMillis());
                                        }
                                    } catch (IOException io) {
                                        throw new IllegalStateException(io);
                                    }
                                });
                sent++;
                if (now >= nextStat) {
                    line(
                            out,
                            "stat "
                                    + now
                                    + " "
                                    + sent
                                    + " "
                                    + acked.get()
                                    + " "
                                    + errors.get()
                                    + " "
                                    + late);
                    nextStat += 1000;
                }
            }
            producer.flush();
            StringBuilder summary = new StringBuilder();
            synchronized (partitions) {
                for (Map.Entry<Integer, long[]> p : partitions.entrySet()) {
                    summary.append(' ')
                            .append(p.getKey())
                            .append(':')
                            .append(p.getValue()[0])
                            .append(':')
                            .append(p.getValue()[1]);
                }
            }
            line(out, "end " + sent + " " + acked.get() + " " + errors.get() + summary);
        }
    }

    private static void line(BufferedWriter out, String text) throws IOException {
        synchronized (out) {
            out.write(text);
            out.newLine();
            out.flush();
        }
    }

    /** Event n: its id, sequence, padding to about bytes, and event time. */
    static byte[] event(String prefix, long n, long eventMs, int bytes) {
        String head = "{\"event_id\":\"" + prefix + "-" + n + "\",\"seq\":" + n + ",\"pad\":\"";
        String tail = "\",\"event_time\":" + eventMs + "}";
        int pad = Math.max(0, bytes - head.length() - tail.length());
        StringBuilder s = new StringBuilder(bytes).append(head);
        // Letters that change with n, so the padding does not compress away to nothing.
        long x = n * 2654435761L + 1;
        for (int i = 0; i < pad; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            s.append((char) ('a' + (int) ((x >>> 33) % 26)));
        }
        return s.append(tail).toString().getBytes(StandardCharsets.UTF_8);
    }
}
