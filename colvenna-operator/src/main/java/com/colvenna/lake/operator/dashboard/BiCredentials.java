package com.colvenna.lake.operator.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Where the BI interface finds the Doris identity of each person who signs in: one Secret in its
 * namespace, one entry per dashboard user with a sign-in name, holding that user's Doris name and
 * password and the dashboard's database. The BI interface connects as that user and as nothing
 * else, so what a person sees there is what the user sees in Doris. The operator writes each
 * dashboard's entries and leaves the others; the installer makes the Secret, and the operator may
 * read and change that one Secret alone.
 */
public final class BiCredentials {
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int ATTEMPTS = 3;

    /** The Secret, as COLVENNA_BI_CREDENTIALS names it: {@code <namespace>/<name>}. */
    public record Settings(String namespace, String secret) {
        public static Optional<Settings> fromEnvironment(Function<String, String> env) {
            String value = env.apply("COLVENNA_BI_CREDENTIALS");
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            if (!value.matches("[a-z0-9-]{1,63}/[a-z0-9.-]{1,253}")) {
                throw new IllegalArgumentException(
                        "COLVENNA_BI_CREDENTIALS must be <namespace>/<name>, not " + value);
            }
            return Optional.of(new Settings(value.split("/", 2)[0], value.split("/", 2)[1]));
        }
    }

    /**
     * One person's identity on one dashboard: their sign-in name, the Doris user and its password,
     * the dashboard's Doris database, and the dashboard as {@code <namespace>/<name>}.
     */
    public record Entry(
            String sso, String dorisUser, String password, String database, String dashboard) {}

    private final KubernetesClient kube;
    private final Settings settings;

    public BiCredentials(KubernetesClient kube, Settings settings) {
        this.kube = kube;
        this.settings = settings;
    }

    /**
     * The dashboard's entries become exactly these, keyed by Doris user; other dashboards' stay.
     * Concurrent writers (other dashboards) are retried. Returns whether the Secret changed.
     */
    public boolean publish(String dashboard, List<Entry> entries) {
        KubernetesClientException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Secret secret =
                    kube.secrets()
                            .inNamespace(settings.namespace())
                            .withName(settings.secret())
                            .get();
            if (secret == null && entries.isEmpty()) {
                // Nothing to withdraw from a Secret that is not there (the BI interface removed).
                return false;
            }
            if (secret == null) {
                throw new KubernetesClientException(
                        "no BI credentials Secret "
                                + settings.namespace()
                                + "/"
                                + settings.secret()
                                + " (deploy/scripts/entry/install-bi.sh makes it)");
            }
            Map<String, String> data =
                    new TreeMap<>(Objects.requireNonNullElse(secret.getData(), Map.of()));
            Map<String, String> next = new TreeMap<>(data);
            next.entrySet().removeIf(e -> dashboard.equals(dashboardOf(e.getValue())));
            for (Entry e : entries) {
                next.put(e.dorisUser(), encode(e));
            }
            if (next.equals(data)) {
                return false;
            }
            secret.setData(next);
            try {
                // The resource version read above: a write that raced another dashboard's fails.
                kube.secrets().inNamespace(settings.namespace()).resource(secret).update();
                return true;
            } catch (KubernetesClientException e) {
                if (e.getCode() != 409) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    /** The dashboard's entries go. */
    public void withdraw(String dashboard) {
        publish(dashboard, List.of());
    }

    /** The entries in the Secret, by Doris user. */
    public Map<String, Entry> entries() {
        Secret secret =
                kube.secrets().inNamespace(settings.namespace()).withName(settings.secret()).get();
        Map<String, Entry> entries = new TreeMap<>();
        if (secret != null && secret.getData() != null) {
            secret.getData().forEach((k, v) -> entries.put(k, decode(v)));
        }
        return entries;
    }

    private static String encode(Entry e) {
        try {
            return Base64.getEncoder()
                    .encodeToString(JSON.writeValueAsString(e).getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException x) {
            throw new IllegalStateException(x);
        }
    }

    private static Entry decode(String base64) {
        try {
            return JSON.readValue(Base64.getDecoder().decode(base64), Entry.class);
        } catch (java.io.IOException | IllegalArgumentException x) {
            return null;
        }
    }

    /** The dashboard an entry belongs to; an entry that cannot be read belongs to none. */
    private static String dashboardOf(String base64) {
        Entry e = decode(base64);
        return e == null ? null : e.dashboard();
    }
}
