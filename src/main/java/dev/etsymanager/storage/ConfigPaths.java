package dev.etsymanager.storage;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where the server keeps its settings, tokens and change log. MCP clients start the server from a working
 * directory nobody controls, so the default is a fixed per-user folder rather than {@code ./.env}.
 * {@code ETSY_ENV_FILE} still overrides it (the launcher script uses that for development).
 */
public final class ConfigPaths {
    static final String APP_DIR = "etsy-manager";

    private ConfigPaths() {}

    public static Path envFile() {
        return envFile(System.getenv(), System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
    }

    static Path envFile(Map<String, String> env, String osName, Path home) {
        String override = env.get("ETSY_ENV_FILE");
        if (override != null && !override.isBlank()) return Path.of(override.strip()).toAbsolutePath();
        return configDir(env, osName, home).resolve(".env");
    }

    static Path configDir(Map<String, String> env, String osName, Path home) {
        String os = osName.toLowerCase();
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            Path base = appData == null || appData.isBlank() ? home.resolve("AppData").resolve("Roaming") : Path.of(appData);
            return base.resolve(APP_DIR);
        }
        if (os.contains("mac")) return home.resolve("Library").resolve("Application Support").resolve(APP_DIR);
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? home.resolve(".config") : Path.of(xdg);
        return base.resolve(APP_DIR);
    }
}
