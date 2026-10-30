package dev.etsymanager;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileTest {
    @TempDir Path dir;

    @Test
    void updateRewritesOnlyTheNamedLinesAndPreservesTheRest() throws IOException {
        Path f = dir.resolve(".env");
        Files.writeString(f, "# my config\nETSY_API_KEY=key123\n\nETSY_ACCESS_TOKEN=old\nOTHER=\"quoted value\"\n");

        new EnvFile(f).update(Map.of("ETSY_ACCESS_TOKEN", "new", "ETSY_REFRESH_TOKEN", "r1"));

        assertEquals("# my config\nETSY_API_KEY=key123\n\nETSY_ACCESS_TOKEN=new\nOTHER=\"quoted value\"\nETSY_REFRESH_TOKEN=r1\n",
                Files.readString(f));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp file left behind");
        }
    }

    @Test
    void createsTheFileWhenMissingAndReadParsesValues() throws IOException {
        EnvFile env = new EnvFile(dir.resolve(".env"));
        env.update(Map.of("A", "1"));
        Files.writeString(dir.resolve(".env"), "A=1\nB=\"two words\"\n# c=3\n", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(Map.of("A", "1", "B", "two words"), env.read());
    }

    @Test
    void readOfMissingFileIsEmpty() {
        assertTrue(new EnvFile(dir.resolve("nope")).read().isEmpty());
    }
}
