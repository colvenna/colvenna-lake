package com.colvenna.lake.operator.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A declaration's publication against a fake colvenna-catalog that keeps grants. */
class PublicationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final String MARK = "publish:LakeTable/colvenna-data/dwd/u1";
    private MockWebServer server;
    private final List<String> grants = new ArrayList<>();
    private final List<String> revoked = new ArrayList<>();
    private boolean down;
    private Publication publication;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public synchronized MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        if (down) {
                            return new MockResponse().setResponseCode(503).setBody("{}");
                        }
                        if (call.equals("GET /colvenna/admin/v1/principals")) {
                            return new MockResponse()
                                    .setBody("{\"principals\":[{\"name\":\"bi\"}]}");
                        }
                        if (call.equals("GET /colvenna/admin/v1/grants?principal=bi")) {
                            return new MockResponse()
                                    .setBody("{\"grants\":[" + String.join(",", grants) + "]}");
                        }
                        if (call.equals("POST /colvenna/admin/v1/grants")) {
                            String body =
                                    r.getBody()
                                            .readUtf8()
                                            .replaceFirst(
                                                    "\\{", "{\"id\":\"g" + grants.size() + "\",");
                            grants.add(body);
                            return new MockResponse().setBody(body);
                        }
                        if (call.startsWith("DELETE /colvenna/admin/v1/grants/")) {
                            String id =
                                    call.substring("DELETE /colvenna/admin/v1/grants/".length());
                            revoked.add(id);
                            grants.removeIf(g -> g.contains("\"id\":\"" + id + "\""));
                            return new MockResponse().setBody("{}");
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        server.start();
        publication =
                new Publication(
                        new CatalogClient(
                                URI.create(server.url("/").toString()),
                                () -> "admin",
                                SSLContext.getDefault()));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private Publication.Outcome publish(
            Set<String> readers, List<String> previous, Publication.Condition withheld) {
        return publication.publish(
                MARK,
                "published by LakeTable colvenna-data/dwd",
                readers,
                List.of("dwd.orders"),
                previous,
                withheld,
                NOW);
    }

    @Test
    void readersGetReadWhileGovernedAndLoseItWhenWithheld() {
        Publication.Outcome out = publish(Set.of("bi", "ghost"), List.of(), null);
        assertThat(out.published()).containsExactly("bi");
        assertThat(out.condition().reason()).isEqualTo("ReaderMissing");
        assertThat(grants).hasSize(1).allMatch(g -> g.contains("\"object\":\"orders\""));

        out = publish(Set.of("bi"), out.published(), null);
        assertThat(out.condition().status()).isTrue();
        assertThat(out.condition().message()).isEqualTo("READ on [dwd.orders] for [bi]");
        // The grant is current: kept, not granted again.
        assertThat(grants).hasSize(1);

        out =
                publish(
                        Set.of("bi"),
                        out.published(),
                        Publication.Condition.withheld("NotGoverned", "L2 is below L3"));
        assertThat(out.published()).isEmpty();
        assertThat(out.condition().reason()).isEqualTo("NotGoverned");
        assertThat(revoked).containsExactly("g0");
        assertThat(grants).isEmpty();
    }

    @Test
    void aWiderGrantIsNamedAndTheCatalogDownKeepsTheReaders() {
        grants.add(
                "{\"id\":\"db1\",\"principal\":\"bi\",\"scope\":\"DATABASE\",\"database\":\"dwd\","
                        + "\"privilege\":\"READ\",\"effect\":\"ALLOW\",\"approval\":\"ticket-7\","
                        + "\"expiresAt\":\"2026-12-01T00:00:00Z\"}");
        Publication.Outcome out =
                publish(
                        Set.of("bi"),
                        List.of(),
                        Publication.Condition.withheld("NotGoverned", "L2 is below L3"));
        assertThat(out.condition().message())
                .contains("L2 is below L3")
                .contains("readable all the same")
                .contains("bi (DATABASE dwd)");
        // Grants the declaration did not make are not touched.
        assertThat(revoked).isEmpty();

        down = true;
        out = publish(Set.of("bi"), List.of("bi"), null);
        assertThat(out.condition().reason()).isEqualTo("CatalogUnavailable");
        assertThat(out.published()).containsExactly("bi");

        down = false;
        publish(Set.of("bi"), List.of(), null);
        assertThat(grants).hasSize(2);
        publication.withdraw(MARK, List.of("bi"), NOW);
        assertThat(grants).hasSize(1).allMatch(g -> g.contains("ticket-7"));
    }
}
