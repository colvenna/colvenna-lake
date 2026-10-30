package com.colvenna.lake.operator.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * colvenna-catalog's admin API and the few REST catalog calls the operator makes (databases, table
 * existence and drops), over TLS with the catalog's private CA. The token is read for every call,
 * so a rotated Secret takes effect without a restart.
 */
public final class CatalogClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "/colvenna/admin/v1";
    private static final String CATALOG = "/v1/colvenna";

    private final URI base;
    private final Supplier<String> token;
    private final HttpClient http;

    public CatalogClient(URI base, Supplier<String> token, SSLContext tls) {
        this.base = base;
        this.token = token;
        SSLParameters protocols = new SSLParameters();
        protocols.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        this.http =
                HttpClient.newBuilder()
                        .sslContext(tls)
                        .sslParameters(protocols)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
    }

    public static CatalogClient create(String uri, Path tokenFile, Path caFile) {
        return new CatalogClient(
                URI.create(uri),
                () -> {
                    try {
                        return Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                    } catch (IOException e) {
                        throw new CatalogException("Cannot read the operator token", e);
                    }
                },
                tls(caFile));
    }

    /** An SSL context trusting only the given CA (colvenna-catalog's private CA). */
    public static SSLContext tls(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("colvenna-ca-" + i++, (X509Certificate) cert);
            }
            TrustManagerFactory trust =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new CatalogException("Cannot load the catalog CA " + caFile, e);
        }
    }

    // ---- principals and tokens ----

    /** Creates the principal if missing. Fails if it exists but is disabled. */
    public void ensurePrincipal(String name, String description) {
        ObjectNode body = JSON.createObjectNode().put("name", name).put("description", description);
        Reply reply = send("POST", ADMIN + "/principals", body);
        if (reply.status == 409) {
            JsonNode existing = principal(name).orElseThrow();
            if (!existing.path("disabledAt").isMissingNode()
                    && !existing.path("disabledAt").isNull()) {
                throw new CatalogException("Principal " + name + " exists but is disabled", 409);
            }
            return;
        }
        reply.require(200);
    }

    public Optional<JsonNode> principal(String name) {
        for (JsonNode p :
                send("GET", ADMIN + "/principals", null).require(200).body.path("principals")) {
            if (name.equals(p.path("name").asText())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public void disablePrincipal(String name) {
        Reply reply = send("POST", ADMIN + "/principals/" + path(name) + "/disable", null);
        if (reply.status != 404) {
            reply.require(200);
        }
    }

    /** A new token; its secret is returned once. */
    public IssuedToken issueToken(String principal, Duration ttl, String description) {
        ObjectNode body =
                JSON.createObjectNode().put("ttl", ttl.toString()).put("description", description);
        JsonNode reply =
                send("POST", ADMIN + "/principals/" + path(principal) + "/tokens", body)
                        .require(200)
                        .body;
        return new IssuedToken(
                reply.path("id").asText(),
                reply.path("token").asText(),
                Instant.parse(reply.path("expiresAt").asText()));
    }

    public List<String> activeTokenIds(String principal) {
        List<String> ids = new ArrayList<>();
        for (JsonNode t :
                send("GET", ADMIN + "/principals/" + path(principal) + "/tokens", null)
                        .require(200)
                        .body
                        .path("tokens")) {
            if (t.path("revokedAt").isMissingNode() || t.path("revokedAt").isNull()) {
                ids.add(t.path("id").asText());
            }
        }
        return ids;
    }

    public void revokeToken(String id) {
        Reply reply = send("DELETE", ADMIN + "/tokens/" + path(id), null);
        if (reply.status != 404) {
            reply.require(200);
        }
    }

    // ---- grants ----

    public List<Grant> grants(String principal) {
        List<Grant> grants = new ArrayList<>();
        for (JsonNode g :
                send("GET", ADMIN + "/grants?principal=" + query(principal), null)
                        .require(200)
                        .body
                        .path("grants")) {
            grants.add(Grant.from(g));
        }
        return grants;
    }

    /** Every grant of every principal. */
    public List<Grant> allGrants() {
        List<Grant> grants = new ArrayList<>();
        for (JsonNode g : send("GET", ADMIN + "/grants", null).require(200).body.path("grants")) {
            grants.add(Grant.from(g));
        }
        return grants;
    }

    /** The names of admin principals: platform identities, not readers. */
    public java.util.Set<String> adminPrincipals() {
        java.util.Set<String> admins = new java.util.HashSet<>();
        for (JsonNode p :
                send("GET", ADMIN + "/principals", null).require(200).body.path("principals")) {
            if (p.path("admin").asBoolean(false)) {
                admins.add(p.path("name").asText());
            }
        }
        return admins;
    }

    public Grant grant(Grant request) {
        return Grant.from(send("POST", ADMIN + "/grants", request.toJson()).require(200).body);
    }

    public void revokeGrant(String id) {
        Reply reply = send("DELETE", ADMIN + "/grants/" + path(id), null);
        if (reply.status != 404) {
            reply.require(200);
        }
    }

    // ---- catalog objects ----

    /** Creates the database with these options unless it exists; returns whether it created it. */
    public boolean ensureDatabase(String database, Map<String, String> options) {
        ObjectNode body = JSON.createObjectNode().put("name", database);
        ObjectNode opts = body.putObject("options");
        options.forEach(opts::put);
        Reply reply = send("POST", CATALOG + "/databases", body);
        if (reply.status == 409) {
            return false;
        }
        reply.require(200);
        return true;
    }

    /** A database's options, or empty if it does not exist. */
    public Optional<Map<String, String>> databaseOptions(String database) {
        Reply reply = send("GET", CATALOG + "/databases/" + path(database), null);
        if (reply.status == 404) {
            return Optional.empty();
        }
        Map<String, String> options = new java.util.HashMap<>();
        reply.require(200)
                .body
                .path("options")
                .fields()
                .forEachRemaining(e -> options.put(e.getKey(), e.getValue().asText()));
        return Optional.of(options);
    }

    /** Whether the table exists and has at least one snapshot. */
    public boolean hasSnapshot(String database, String table) {
        Reply reply =
                send(
                        "GET",
                        CATALOG
                                + "/databases/"
                                + path(database)
                                + "/tables/"
                                + path(table)
                                + "/snapshot",
                        null);
        if (reply.status == 404) {
            return false;
        }
        reply.require(200);
        return true;
    }

    /** The id of the table's latest snapshot, or empty if it has none or does not exist. */
    public OptionalLong latestSnapshotId(String database, String table) {
        Reply reply =
                send(
                        "GET",
                        CATALOG
                                + "/databases/"
                                + path(database)
                                + "/tables/"
                                + path(table)
                                + "/snapshot",
                        null);
        if (reply.status == 404) {
            return OptionalLong.empty();
        }
        // GetTableSnapshotResponse: {"snapshot": {"snapshot": {"id": ...}, "recordCount": ...}}.
        JsonNode snapshot = reply.require(200).body.path("snapshot");
        JsonNode id = snapshot.path("snapshot").path("id");
        return id.isIntegralNumber() ? OptionalLong.of(id.asLong()) : OptionalLong.empty();
    }

    /** The table's metadata as the REST catalog returns it, or empty if it does not exist. */
    public Optional<JsonNode> table(String database, String table) {
        Reply reply =
                send(
                        "GET",
                        CATALOG + "/databases/" + path(database) + "/tables/" + path(table),
                        null);
        if (reply.status == 404) {
            return Optional.empty();
        }
        return Optional.of(reply.require(200).body);
    }

    public List<String> tables(String database) {
        List<String> names = new ArrayList<>();
        String page = null;
        do {
            String uri =
                    CATALOG
                            + "/databases/"
                            + path(database)
                            + "/tables"
                            + (page == null ? "" : "?pageToken=" + query(page));
            Reply reply = send("GET", uri, null);
            if (reply.status == 404) {
                return names;
            }
            JsonNode body = reply.require(200).body;
            body.path("tables").forEach(t -> names.add(t.asText()));
            page =
                    body.path("nextPageToken").isTextual()
                            ? body.path("nextPageToken").asText()
                            : null;
        } while (page != null && !page.isEmpty());
        return names;
    }

    public void dropTable(String database, String table) {
        Reply reply =
                send(
                        "DELETE",
                        CATALOG + "/databases/" + path(database) + "/tables/" + path(table),
                        null);
        if (reply.status != 404) {
            reply.require(200);
        }
    }

    public void dropDatabaseIfEmpty(String database) {
        if (!tables(database).isEmpty()) {
            return;
        }
        Reply reply = send("DELETE", CATALOG + "/databases/" + path(database), null);
        if (reply.status != 404) {
            reply.require(200);
        }
    }

    // ---- transport ----

    private Reply send(String method, String pathAndQuery, JsonNode body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(base.resolve(pathAndQuery))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token.get())
                        .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        try {
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode json =
                    text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            return new Reply(method + " " + pathAndQuery, response.statusCode(), json);
        } catch (IOException e) {
            throw new CatalogException(method + " " + pathAndQuery + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogException(method + " " + pathAndQuery + " interrupted", e);
        }
    }

    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A token as issued. */
    public record IssuedToken(String id, String token, Instant expiresAt) {}

    private record Reply(String call, int status, JsonNode body) {
        Reply require(int expected) {
            if (status != expected) {
                throw new CatalogException(
                        call
                                + " returned "
                                + status
                                + ": "
                                + body.path("message").asText(body.toString()),
                        status);
            }
            return this;
        }
    }
}
