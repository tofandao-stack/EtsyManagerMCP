package dev.etsymanager.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigPathsTest {
    private static final Path HOME = Path.of("/home/u");

    @Test
    void envOverrideWins() {
        assertEquals(Path.of("/x/my.env"),
                ConfigPaths.envFile(Map.of("ETSY_ENV_FILE", "/x/my.env"), "Linux", HOME));
    }

    @Test
    void linuxUsesXdgConfigOrDotConfig() {
        assertEquals(HOME.resolve(".config/etsy-manager/.env"), ConfigPaths.envFile(Map.of(), "Linux", HOME));
        assertEquals(Path.of("/xdg/etsy-manager/.env"),
                ConfigPaths.envFile(Map.of("XDG_CONFIG_HOME", "/xdg"), "Linux", HOME));
    }

    @Test
    void macUsesApplicationSupport() {
        assertEquals(HOME.resolve("Library/Application Support/etsy-manager/.env"),
                ConfigPaths.envFile(Map.of(), "Mac OS X", HOME));
    }

    @Test
    void windowsUsesAppData() {
        assertEquals(Path.of("/appdata", "etsy-manager", ".env"),
                ConfigPaths.envFile(Map.of("APPDATA", "/appdata"), "Windows 11", HOME));
        assertEquals(HOME.resolve("AppData/Roaming/etsy-manager/.env"),
                ConfigPaths.envFile(Map.of(), "Windows 11", HOME));
    }

    @Test
    void firstWriteCreatesAnOwnerOnlyFolderAndFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve("new-folder/.env");
        new EnvFile(env).update(Map.of("A", "1"));
        assertEquals("A=1\n", Files.readString(env));
        try {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(env.getParent())));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(env)));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
    }
}
