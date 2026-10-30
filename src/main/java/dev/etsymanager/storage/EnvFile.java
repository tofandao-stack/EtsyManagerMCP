package dev.etsymanager.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A minimal .env file: KEY=VALUE lines. Updates rewrite only the named keys, atomically. */
public class EnvFile {
    private final Path path;

    public EnvFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public synchronized Map<String, String> read() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines()) {
            String key = keyOf(line);
            if (key == null) continue;
            String value = line.substring(line.indexOf('=') + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    /** Replaces the lines of the given keys (appending missing ones) and leaves every other line as it was. */
    public synchronized void update(Map<String, String> values) {
        List<String> out = new ArrayList<>(lines());
        Set<String> pending = new LinkedHashSet<>(values.keySet());
        for (int i = 0; i < out.size(); i++) {
            String key = keyOf(out.get(i));
            if (key != null && values.containsKey(key)) {
                out.set(i, key + "=" + values.get(key));
                pending.remove(key);
            }
        }
        for (String key : pending) out.add(key + "=" + values.get(key));
        writeAtomically(String.join("\n", out) + "\n");
    }

    private List<String> lines() {
        if (!Files.exists(path)) return List.of();
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String keyOf(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) return null;
        String key = t.substring(0, t.indexOf('=')).strip();
        return key.startsWith("export ") ? key.substring(7).strip() : key;
    }

    private void writeAtomically(String content) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".env", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX filesystem
                }
                Files.writeString(tmp, content);
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
