package com.colvenna.lake.operator.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The requests the operator sends Polaris, as Polaris 1.8.0's APIs take them. */
class PolarisClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockWebServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger();
    private PolarisClient client;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        String body = r.getBody().readString(StandardCharsets.UTF_8);
                        calls.add(call + " " + r.getHeader("Authorization"));
                        bodies.add(body);
                        if (call.equals("POST /api/catalog/v1/oauth/tokens")) {
                            tokens.incrementAndGet();
                            return new MockResponse()
                                    .setBody(
                                            "{\"access_token\":\"t"
                                                    + tokens.get()
                                                    + "\",\"expires_in\":3600}");
                        }
                        if (call.contains("/namespaces/gone/")) {
                            return new MockResponse().setResponseCode(404).setBody("{}");
                        }
                        if (r.getMethod().equals("HEAD")) {
                            // No body: one would be read as the next answer.
                            return new MockResponse()
                                    .setResponseCode(
                                            call.endsWith("/namespaces/ods_web/tables/clicks")
                                                    ? 204
                                                    : 404);
                        }
                        if (call.endsWith("/notifications")) {
                            return new MockResponse()
                                    .setResponseCode(body.contains("\"timestamp\":1,") ? 409 : 204);
                        }
                        if (call.startsWith(
                                "GET /api/management/v1/catalogs/colvenna_open/catalog-roles/web_readers/grants")) {
                            return new MockResponse()
                                    .setBody(
                                            "{\"grants\":[{\"type\":\"table\",\"namespace\":[\"ods_web\"],"
                                                    + "\"tableName\":\"clicks\",\"privilege\":\"TABLE_READ_DATA\"},"
                                                    + "{\"type\":\"catalog\",\"privilege\":\"CATALOG_READ_PROPERTIES\"}]}");
                        }
                        if (call.contains("/catalog-roles/missing/")) {
                            return new MockResponse().setResponseCode(404).setBody("{}");
                        }
                        if (call.startsWith("PUT ") || call.startsWith("POST ")) {
                            return new MockResponse().setResponseCode(201);
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        server.start();
        client =
                new PolarisClient(
                        server.url("/").uri(),
                        "colvenna_open",
                        () -> new PolarisClient.Credentials("op-id", "op secret"),
                        SSLContext.getDefault());
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void notificationsPointTheCatalogAtAMetadataFileWithItsSequence() throws Exception {
        assertThat(
                        client.update(
                                "ods_web",
                                "clicks",
                                7,
                                "uuid-1",
                                "s3://b/t/metadata/v7.metadata.json"))
                .isEqualTo(204);
        assertThat(calls.get(0)).startsWith("POST /api/catalog/v1/oauth/tokens");
        assertThat(bodies.get(0))
                .isEqualTo(
                        "grant_type=client_credentials&client_id=op-id&client_secret=op+secret"
                                + "&scope=PRINCIPAL_ROLE:ALL");
        assertThat(calls.get(1))
                .isEqualTo(
                        "POST /api/catalog/v1/colvenna_open/namespaces/ods_web/tables/clicks/notifications"
                                + " Bearer t1");
        JsonNode n = JSON.readTree(bodies.get(1));
        assertThat(n.path("notification-type").asText()).isEqualTo("UPDATE");
        assertThat(n.path("payload").path("timestamp").asLong()).isEqualTo(7);
        assertThat(n.path("payload").path("metadata-location").asText())
                .isEqualTo("s3://b/t/metadata/v7.metadata.json");
        // Not newer than the last: stale, said so.
        assertThat(client.update("ods_web", "clicks", 1, "uuid-1", "x"))
                .isEqualTo(PolarisClient.STALE);
        client.drop("ods_web", "clicks", 8);
        assertThat(JSON.readTree(bodies.get(bodies.size() - 1)).path("notification-type").asText())
                .isEqualTo("DROP");
        // One token for all of them.
        assertThat(tokens.get()).isEqualTo(1);
    }

    @Test
    void existsSaysWhetherTheCatalogHasTheTableAndAnUpdateAnswered404Fails() {
        assertThat(client.exists("ods_web", "clicks")).isTrue();
        assertThat(client.exists("ods_web", "orders")).isFalse();
        // An UPDATE answered 404 did not point the catalog anywhere.
        assertThatThrownBy(() -> client.update("gone", "clicks", 9, "uuid-1", "x"))
                .isInstanceOf(PolarisClient.PolarisException.class)
                .satisfies(
                        e ->
                                assertThat(((PolarisClient.PolarisException) e).notFound())
                                        .as(e.getMessage())
                                        .isTrue());
        // A DROP of a table not there is done.
        client.drop("gone", "clicks", 10);
    }

    @Test
    void grantsAreTheCatalogRolesTableGrants() throws Exception {
        assertThat(client.tableGrants("web_readers"))
                .containsExactly(
                        new PolarisClient.TableGrant(
                                "web_readers", "ods_web", "clicks", "TABLE_READ_DATA"));
        assertThat(client.tableGrants("missing")).isEmpty();
        PolarisClient.TableGrant grant =
                new PolarisClient.TableGrant("web_readers", "ods_web", "orders", "TABLE_READ_DATA");
        client.grant(grant);
        assertThat(calls.get(calls.size() - 1))
                .startsWith(
                        "PUT /api/management/v1/catalogs/colvenna_open/catalog-roles/web_readers/grants");
        JsonNode g = JSON.readTree(bodies.get(bodies.size() - 1)).path("grant");
        assertThat(g.path("type").asText()).isEqualTo("table");
        assertThat(g.path("namespace").get(0).asText()).isEqualTo("ods_web");
        assertThat(g.path("tableName").asText()).isEqualTo("orders");
        client.revoke(grant);
        assertThat(calls.get(calls.size() - 1))
                .startsWith(
                        "POST /api/management/v1/catalogs/colvenna_open/catalog-roles/web_readers/grants");
        assertThatThrownBy(
                        () ->
                                client.grant(
                                        new PolarisClient.TableGrant(
                                                "missing", "ods_web", "clicks", "TABLE_READ_DATA")))
                .isInstanceOf(PolarisClient.PolarisException.class);
    }
}
