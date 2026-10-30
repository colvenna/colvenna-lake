package com.colvenna.lake.operator.source;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * What a Kafka source's user can see of its cluster: whether it connects and authenticates, the
 * declared topics and their partitions, whether it may read them, and how far the job's consumer
 * group is behind. Read with the job's own credentials, so a revoked password or a missing ACL
 * shows here first.
 */
public class KafkaProbe {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One topic: partitions, the end offsets and the group's committed offsets, summed. */
    public record Topic(String name, int partitions, long end, long committed) {
        public long lag() {
            return Math.max(0, end - committed);
        }
    }

    /** The outcome: {@code reason} is null when every topic was read. */
    public record Result(String reason, String message, List<Topic> topics) {
        static Result failed(String reason, String message) {
            return new Result(reason, message, List.of());
        }
    }

    /**
     * @param changeTopics the topics carry CDC changes: a compacted one would have dropped delete
     *     events (only a key's last record survives), so its cleanup policy is read too, which
     *     takes DescribeConfigs
     */
    public Result probe(
            String bootstrap,
            List<String> topics,
            String mechanism,
            Path caFile,
            String group,
            SourceProbe.Login login,
            boolean changeTopics) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put("security.protocol", "SASL_SSL");
        p.put("sasl.mechanism", mechanism);
        p.put(
                "sasl.jaas.config",
                (mechanism.equals("PLAIN")
                                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                                : "org.apache.kafka.common.security.scram.ScramLoginModule")
                        + " required username=\""
                        + jaas(login.user())
                        + "\" password=\""
                        + jaas(login.password())
                        + "\";");
        if (caFile != null) {
            p.put("ssl.truststore.type", "PEM");
            p.put("ssl.truststore.location", caFile.toString());
        }
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        p.put(AdminClientConfig.RETRIES_CONFIG, 1);
        try (Admin admin = Admin.create(p)) {
            Map<String, TopicDescription> described =
                    admin.describeTopics(topics).allTopicNames().get(seconds(), TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (TopicDescription d : described.values()) {
                d.partitions()
                        .forEach(
                                pi ->
                                        latest.put(
                                                new TopicPartition(d.name(), pi.partition()),
                                                OffsetSpec.latest()));
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(seconds(), TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(seconds(), TimeUnit.SECONDS);
            List<Topic> out = new ArrayList<>();
            for (String name : topics) {
                TopicDescription d = described.get(name);
                long end = 0;
                long done = 0;
                for (var pi : d.partitions()) {
                    TopicPartition tp = new TopicPartition(name, pi.partition());
                    end += ends.get(tp).offset();
                    OffsetAndMetadata c = committed.get(tp);
                    done += c == null ? 0 : c.offset();
                }
                out.add(new Topic(name, d.partitions().size(), end, done));
            }
            // Describe is not Read: fetch from each topic's end, as the job will from its offsets.
            read(p, latest.keySet());
            if (changeTopics) {
                String compacted = compacted(admin, topics);
                if (compacted != null) {
                    return Result.failed(
                            "TopicCompacted",
                            compacted
                                    + " is compacted, which drops a deleted row's delete event;"
                                    + " the topics need cleanup.policy=delete");
                }
            }
            return new Result(null, null, out);
        } catch (ExecutionException e) {
            return classify(e.getCause());
        } catch (TimeoutException e) {
            return Result.failed(
                    "Unreachable", "no answer from " + bootstrap + " in " + seconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Unreachable", "interrupted");
        } catch (RuntimeException e) {
            return classify(e);
        }
    }

    /** The first topic whose cleanup policy compacts, or null. */
    private static String compacted(Admin admin, List<String> topics)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<ConfigResource> resources =
                topics.stream().map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t)).toList();
        Map<ConfigResource, Config> configs =
                admin.describeConfigs(resources).all().get(seconds(), TimeUnit.SECONDS);
        for (ConfigResource resource : resources) {
            ConfigEntry policy = configs.get(resource).get("cleanup.policy");
            if (policy != null && policy.value() != null && policy.value().contains("compact")) {
                return resource.name();
            }
        }
        return null;
    }

    private static long seconds() {
        return TIMEOUT.toSeconds();
    }

    /** Fetches once at each partition's end; throws when the user may not read the topic. */
    private static void read(Properties admin, java.util.Set<TopicPartition> partitions) {
        Properties p = new Properties();
        p.putAll(admin);
        p.remove(AdminClientConfig.RETRIES_CONFIG);
        p.remove(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        try (KafkaConsumer<byte[], byte[]> consumer =
                new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            consumer.poll(Duration.ofSeconds(2));
        }
    }

    static Result classify(Throwable e) {
        if (e instanceof SslAuthenticationException) {
            return Result.failed(
                    "TlsFailed",
                    "the TLS handshake failed (the cluster's CA or host name): " + e.getMessage());
        }
        if (e instanceof AuthenticationException) {
            return Result.failed(
                    "AuthenticationFailed",
                    "the cluster refused the credentials: " + e.getMessage());
        }
        if (e instanceof AuthorizationException) {
            return Result.failed(
                    "NotAuthorized",
                    "the user may not read a topic or its group: " + e.getMessage());
        }
        if (e instanceof UnknownTopicOrPartitionException) {
            return Result.failed("TopicMissing", e.getMessage());
        }
        if (e instanceof org.apache.kafka.common.errors.TimeoutException) {
            return Result.failed("Unreachable", e.getMessage());
        }
        return Result.failed("Unreachable", String.valueOf(e.getMessage()));
    }

    private static String jaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
