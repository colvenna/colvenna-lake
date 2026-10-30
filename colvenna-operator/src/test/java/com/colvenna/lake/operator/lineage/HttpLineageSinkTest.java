package com.colvenna.lake.operator.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Events are posted as JSON, with the API key when there is one; a refusal is an error. */
class HttpLineageSinkTest {
    @TempDir Path dir;

    @Test
    void postsTheEventWithTheKeyAsItIsNow() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"code\":422}"));
            server.start();
            Path key = dir.resolve("key");
            Files.writeString(key, "k1\n");
            HttpLineageSink sink = new HttpLineageSink(server.url("/api/v1/lineage").uri(), key);
            var event = new ObjectMapper().createObjectNode().put("eventType", "START");
            sink.send(event);
            RecordedRequest r = server.takeRequest();
            assertThat(r.getMethod()).isEqualTo("POST");
            assertThat(r.getPath()).isEqualTo("/api/v1/lineage");
            assertThat(r.getHeader("Authorization")).isEqualTo("Bearer k1");
            assertThat(r.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(r.getBody().readUtf8()).isEqualTo("{\"eventType\":\"START\"}");
            // A rotated key is used from the next event on.
            Files.writeString(key, "k2");
            sink.send(event);
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer k2");
            // Refused as wrong, or refused for now: an expired key, a wrong address, a throttle,
            // a server error are sent again.
            assertThatThrownBy(() -> sink.send(event))
                    .isInstanceOf(LineagePublisher.Rejected.class)
                    .hasMessageContaining("422");
            for (int code : List.of(401, 403, 404, 429, 503)) {
                server.enqueue(new MockResponse().setResponseCode(code));
            }
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> sink.send(event))
                        .isInstanceOf(IOException.class)
                        .isNotInstanceOf(LineagePublisher.Rejected.class);
            }
        }
    }

    @Test
    void withoutAKeyNoneIsSent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201));
            server.start();
            new HttpLineageSink(server.url("/lineage").uri(), null)
                    .send(new ObjectMapper().createObjectNode());
            assertThat(server.takeRequest().getHeader("Authorization")).isNull();
        }
    }
}
