package com.colvenna.lake.operator.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gauges in the Prometheus text format, replaced per resource on every reconcile so a deleted
 * resource's series disappear. Served on its own port inside the cluster.
 */
public final class Metrics {
    /** One gauge sample. */
    public record Sample(String name, String help, Map<String, String> labels, double value) {}

    private final Map<String, java.util.List<Sample>> byResource = new ConcurrentHashMap<>();
    private HttpServer server;

    public void publish(String resource, java.util.List<Sample> samples) {
        byResource.put(resource, java.util.List.copyOf(samples));
    }

    public void forget(String resource) {
        byResource.remove(resource);
    }

    public String render() {
        Map<String, String> help = new TreeMap<>();
        Map<String, StringBuilder> lines = new TreeMap<>();
        for (var samples : byResource.values()) {
            for (Sample s : samples) {
                help.putIfAbsent(s.name(), s.help());
                StringBuilder out = lines.computeIfAbsent(s.name(), n -> new StringBuilder());
                out.append(s.name()).append('{');
                boolean first = true;
                for (var label : new TreeMap<>(s.labels()).entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(label.getKey())
                            .append("=\"")
                            .append(
                                    label.getValue()
                                            .replace("\\", "\\\\")
                                            .replace("\"", "\\\"")
                                            .replace("\n", "\\n"))
                            .append('"');
                }
                out.append("} ").append(s.value()).append('\n');
            }
        }
        StringBuilder text = new StringBuilder();
        for (var e : lines.entrySet()) {
            text.append("# HELP ")
                    .append(e.getKey())
                    .append(' ')
                    .append(help.get(e.getKey()))
                    .append('\n');
            text.append("# TYPE ").append(e.getKey()).append(" gauge\n");
            text.append(e.getValue());
        }
        return text.toString();
    }

    public void serve(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(
                "/metrics",
                exchange -> {
                    byte[] body = render().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/healthz",
                exchange -> {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
