package dev.etsymanager.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local HTTP stub that plays back scripted responses and records what it received. */
public class StubEtsyServer implements AutoCloseable {
    public record Received(String method, String pathAndQuery, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        public static Reply status(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    public final List<Received> requests = new ArrayList<>();
    private final Map<String, Deque<Reply>> scripts = new ConcurrentHashMap<>();
    private final HttpServer server;

    public StubEtsyServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            String pq = ex.getRequestURI().toString();
            synchronized (requests) {
                requests.add(new Received(ex.getRequestMethod(), pq, headers, body));
            }
            String path = ex.getRequestURI().getPath();
            Deque<Reply> q = scripts.get(ex.getRequestMethod() + " " + path);
            Reply r = q == null || q.isEmpty() ? Reply.status(500, "{\"error\":\"unscripted " + path + "\"}") : q.poll();
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    public StubEtsyServer on(String method, String path, Reply... replies) {
        scripts.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).addAll(List.of(replies));
        return this;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
