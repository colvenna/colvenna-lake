package com.colvenna.lake.operator.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Posts events to an OpenLineage HTTP endpoint (Marquez {@code /api/v1/lineage}, DataHub, or
 * OpenMetadata), with an API key as a bearer token when one is configured; the key is read for each
 * event, so a rotated Secret takes effect without a restart.
 */
public final class HttpLineageSink implements LineagePublisher.Sink {
    /** Answers that refuse the event itself: malformed, conflicting, too large, unprocessable. */
    private static final java.util.Set<Integer> REJECTED =
            java.util.Set.of(400, 409, 413, 415, 422);

    private final URI endpoint;
    private final Path apiKeyFile;
    private final HttpClient http;

    public HttpLineageSink(URI endpoint, Path apiKeyFile) {
        this.endpoint = endpoint;
        this.apiKeyFile = apiKeyFile;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void send(JsonNode event) throws IOException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(event.toString()));
        if (apiKeyFile != null) {
            request.header("Authorization", "Bearer " + Files.readString(apiKeyFile).trim());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            String body = response.body();
            String message =
                    endpoint
                            + " answered "
                            + status
                            + ": "
                            + (body.length() > 300 ? body.substring(0, 300) : body);
            // The event itself is wrong. Anything else (a key that expired, a wrong address, a
            // throttle, a server error) may pass once it is fixed: the event is sent again.
            if (REJECTED.contains(status)) {
                throw new LineagePublisher.Rejected(message);
            }
            throw new IOException(message);
        }
    }
}
