package com.colvenna.lake.jobs.kafka;

import com.colvenna.lake.jobs.Catalogs;
import java.util.Properties;

/**
 * How a job reaches the user's Kafka: SASL over TLS (SCRAM, or PLAIN as Confluent Cloud takes API
 * keys) as the user in {@code COLVENNA_SOURCE_USER} and {@code COLVENNA_SOURCE_PASSWORD}, from the
 * declaration's Secret and never an argument, against the cluster's CA when the declaration names
 * one.
 */
public final class KafkaLogin {
    private KafkaLogin() {}

    /** Client properties for the shaded Kafka client of the Flink connector. */
    public static Properties properties(String bootstrap, String mechanism, String caFile) {
        String module =
                mechanism.equals("PLAIN")
                        ? "org.apache.flink.kafka.shaded.org.apache.kafka.common.security.plain.PlainLoginModule"
                        : "org.apache.flink.kafka.shaded.org.apache.kafka.common.security.scram.ScramLoginModule";
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", bootstrap);
        properties.setProperty("security.protocol", "SASL_SSL");
        properties.setProperty("sasl.mechanism", mechanism);
        properties.setProperty(
                "sasl.jaas.config",
                module
                        + " required username=\""
                        + jaas(Catalogs.required("COLVENNA_SOURCE_USER"))
                        + "\" password=\""
                        + jaas(Catalogs.required("COLVENNA_SOURCE_PASSWORD"))
                        + "\";");
        if (caFile != null) {
            properties.setProperty("ssl.truststore.type", "PEM");
            properties.setProperty("ssl.truststore.location", caFile);
        }
        properties.setProperty("allow.auto.create.topics", "false");
        properties.setProperty("isolation.level", "read_committed");
        return properties;
    }

    static String jaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
