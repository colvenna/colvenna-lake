package com.colvenna.lake.operator.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * The calls the operator makes to Polaris (LLD "开放层"): notifications that point the EXTERNAL
 * catalog at a table's Iceberg metadata, or drop it, and the table grants of readers' catalog
 * roles. It authenticates with its principal's client credentials and keeps the token until shortly
 * before it expires.
 */
public class PolarisClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Polaris's answer to a notification whose sequence is not above the last one. */
    public static final int STALE = 409;

    private final URI base;
    private final String catalog;
    private final Supplier<Credentials> credentials;
    private final HttpClient http;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    /** A principal's client credentials. */
    public record Credentials(String clientId, String clientSecret) {}

    /**
     * @param base Polaris's API, e.g. https://polaris.colvenna-polaris.svc:8181
     * @param catalog the EXTERNAL catalog published to
     * @param credentials the principal's client credentials, read when a token is needed
     */
    public PolarisClient(
            URI base, String catalog, Supplier<Credentials> credentials, SSLContext tls) {
        this.base = base;
        this.catalog = catalog;
        this.credentials = credentials;
        this.http =
                HttpClient.newBuilder()
                        .sslContext(tls)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
    }

    /** An error answer of Polaris (its status), or one it could not be reached for (status 0). */
    public static final class PolarisException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;

        PolarisException(String message, int status) {
            super(message);
            this.status = status;
        }

        /** Polaris answered that the thing asked about is not there. */
        public boolean notFound() {
            return status == 404;
        }
    }

    /**
     * Points the catalog at a table's metadata file (UPDATE, which creates the table if absent).
     *
     * @return the status: 204, or {@link #STALE} when the sequence is not above the last
     */
    public int update(
            String namespace, String table, long sequence, String uuid, String metadataLocation) {
        Reply reply = notification(namespace, table, "UPDATE", sequence, uuid, metadataLocation);
        if (reply.status != 204 && reply.status != STALE) {
            throw new PolarisException(reply.describe(), reply.status);
        }
        return reply.status;
    }

    /** Takes the table out of the catalog; its files stay. A table not there is fine. */
    public void drop(String namespace, String table, long sequence) {
        Reply reply = notification(namespace, table, "DROP", sequence, "", "");
        if (reply.status != 204 && reply.status != 404) {
            throw new PolarisException(reply.describe(), reply.status);
        }
    }

    private Reply notification(
            String namespace,
            String table,
            String type,
            long sequence,
            String uuid,
            String metadataLocation) {
        ObjectNode body = JSON.createObjectNode().put("notification-type", type);
        body.putObject("payload")
                .put("table-name", table)
                .put("timestamp", sequence)
                .put("table-uuid", uuid)
                .put("metadata-location", metadataLocation);
        return send("POST", table(namespace, table) + "/notifications", body);
    }

    /** Whether the catalog has the table registered. */
    public boolean exists(String namespace, String table) {
        Reply reply = send("HEAD", table(namespace, table), null);
        if (reply.status == 204 || reply.status == 200) {
            return true;
        }
        if (reply.status == 404) {
            return false;
        }
        throw new PolarisException(reply.describe(), reply.status);
    }

    /** A table grant of a catalog role. */
    public record TableGrant(String role, String namespace, String table, String privilege) {}

    /** The catalog role's table grants; empty when the role does not exist. */
    public List<TableGrant> tableGrants(String role) {
        Reply reply = send("GET", grants(role), null);
        List<TableGrant> out = new ArrayList<>();
        if (reply.status == 404) {
            return out;
        }
        reply.require(200);
        for (JsonNode g : reply.body.path("grants")) {
            if ("table".equals(g.path("type").asText()) && g.path("namespace").size() == 1) {
                out.add(
                        new TableGrant(
                                role,
                                g.path("namespace").get(0).asText(),
                                g.path("tableName").asText(),
                                g.path("privilege").asText()));
            }
        }
        return out;
    }

    /** Grants a catalog role a privilege on a table; the role must exist. */
    public void grant(TableGrant grant) {
        Reply reply = send("PUT", grants(grant.role()), grantBody(grant));
        if (reply.status != 201 && reply.status != 200) {
            throw new PolarisException(reply.describe(), reply.status);
        }
    }

    /** Revokes a catalog role's privilege on a table; one already gone is fine. */
    public void revoke(TableGrant grant) {
        Reply reply = send("POST", grants(grant.role()), grantBody(grant));
        if (reply.status != 201
                && reply.status != 200
                && reply.status != 204
                && reply.status != 404) {
            throw new PolarisException(reply.describe(), reply.status);
        }
    }

    private String table(String namespace, String table) {
        return "/api/catalog/v1/"
                + path(catalog)
                + "/namespaces/"
                + path(namespace)
                + "/tables/"
                + path(table);
    }

    private String grants(String role) {
        return "/api/management/v1/catalogs/"
                + path(catalog)
                + "/catalog-roles/"
                + path(role)
                + "/grants";
    }

    private static ObjectNode grantBody(TableGrant grant) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode g = body.putObject("grant");
        g.put("type", "table");
        g.putArray("namespace").add(grant.namespace());
        g.put("tableName", grant.table());
        g.put("privilege", grant.privilege());
        return body;
    }

    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The bearer token, fetched again a minute before it expires. */
    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(60))) {
            return token;
        }
        Credentials c = credentials.get();
        String form =
                "grant_type=client_credentials&client_id="
                        + URLEncoder.encode(c.clientId(), StandardCharsets.UTF_8)
                        + "&client_secret="
                        + URLEncoder.encode(c.clientSecret(), StandardCharsets.UTF_8)
                        + "&scope=PRINCIPAL_ROLE:ALL";
        HttpRequest request =
                HttpRequest.newBuilder(base.resolve("/api/catalog/v1/oauth/tokens"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PolarisException(
                        "Polaris refused the operator's credentials: " + response.statusCode(),
                        response.statusCode());
            }
            JsonNode body = JSON.readTree(response.body());
            token = body.path("access_token").asText();
            tokenExpires = Instant.now().plusSeconds(body.path("expires_in").asLong(600));
            return token;
        } catch (IOException e) {
            throw new PolarisException("Polaris's token endpoint failed: " + e, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolarisException("interrupted", 0);
        }
    }

    private Reply send(String method, String path, JsonNode body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token())
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
            return new Reply(method + " " + path, response.statusCode(), json);
        } catch (IOException e) {
            throw new PolarisException(method + " " + path + " failed: " + e, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolarisException("interrupted", 0);
        }
    }

    private record Reply(String call, int status, JsonNode body) {
        String describe() {
            return call
                    + " returned "
                    + status
                    + ": "
                    + body.path("error").path("message").asText(body.toString());
        }

        Reply require(int expected) {
            if (status != expected) {
                throw new PolarisException(describe(), status);
            }
            return this;
        }
    }
}
