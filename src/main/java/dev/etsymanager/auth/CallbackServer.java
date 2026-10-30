package dev.etsymanager.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Tiny local HTTP listener that catches Etsy's redirect so the owner doesn't have to copy the code by hand. */
final class CallbackServer implements AutoCloseable {
    private static final String DONE_PAGE = "<!doctype html><meta charset=\"utf-8\"><title>Etsy login</title>"
            + "<p>Login received. You can close this tab and return to the terminal.</p>";

    private final HttpServer server;
    private final CompletableFuture<String> query = new CompletableFuture<>();

    private CallbackServer(HttpServer server) {
        this.server = server;
    }

    /** @throws IOException if the port is unavailable */
    static CallbackServer start(int port, String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        CallbackServer cb = new CallbackServer(server);
        server.createContext(path.isEmpty() ? "/" : path, ex -> {
            String q = ex.getRequestURI().getRawQuery();
            byte[] body = DONE_PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
            if (q != null && q.contains("code=")) cb.query.complete(q);
        });
        server.start();
        return cb;
    }

    /** The raw query string of the redirect, or null if none arrived within the timeout. */
    String awaitQuery(Duration timeout) {
        try {
            return query.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
