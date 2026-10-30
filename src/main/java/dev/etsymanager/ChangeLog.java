package dev.etsymanager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Local JSON file recording every confirmed write; the only record for judging whether an edit helped. */
public class ChangeLog {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<ChangeEntry>> ENTRIES = new TypeReference<>() {};

    private final Path file;

    public ChangeLog(Path file) {
        this.file = file;
    }

    public synchronized ChangeEntry append(
            Instant timestamp, long listingId, String kind, String reason,
            TextFields previous, TextFields updated, int views, int favorites) {
        List<ChangeEntry> all = new ArrayList<>(readAll());
        ChangeEntry entry = new ChangeEntry(
                all.size() + 1, timestamp, listingId, kind, reason, previous, updated, views, favorites);
        all.add(entry);
        write(all);
        return entry;
    }

    /** Entries in write order; any filter may be null. {@code from} is inclusive, {@code to} exclusive. */
    public synchronized List<ChangeEntry> list(Long listingId, Instant from, Instant to) {
        return readAll().stream()
                .filter(e -> listingId == null || e.listingId() == listingId)
                .filter(e -> from == null || !e.timestamp().isBefore(from))
                .filter(e -> to == null || e.timestamp().isBefore(to))
                .toList();
    }

    private List<ChangeEntry> readAll() {
        if (!Files.exists(file)) return List.of();
        try {
            return JSON.readValue(Files.readString(file), ENTRIES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(List<ChangeEntry> all) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
