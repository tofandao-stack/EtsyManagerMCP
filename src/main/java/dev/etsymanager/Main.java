package dev.etsymanager;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Entry point. {@code java -jar etsy-manager-mcp.jar} serves MCP over stdio;
 * {@code ... login} runs the one-off OAuth login. Never write to stdout while serving: it is the protocol channel.
 */
public final class Main {
    private static final URI API_BASE = URI.create("https://api.etsy.com/v3/application");
    private static final URI TOKEN_URI = URI.create("https://api.etsy.com/v3/public/oauth/token");

    private Main() {}

    public static void main(String[] args) throws Exception {
        Path envPath = Path.of(System.getenv().getOrDefault("ETSY_ENV_FILE", ".env")).toAbsolutePath();
        EnvFile env = new EnvFile(envPath);
        EtsyClient etsy = new HttpEtsyClient(HttpClient.newHttpClient(), API_BASE, TOKEN_URI, env, Instant::now, Main::sleep);

        if (args.length > 0 && args[0].equals("login")) {
            try {
                Login.interactive(etsy, env).run();
            } catch (IllegalArgumentException | EtsyException e) {
                System.err.println("Login failed: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length > 0) {
            System.err.println("Usage: java -jar etsy-manager-mcp.jar [login]");
            System.exit(2);
        }

        ChangeLog changeLog = new ChangeLog(envPath.resolveSibling("change-log.json"));
        McpWiring.start(new ToolHandlers(etsy, changeLog));
        Thread.currentThread().join();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
