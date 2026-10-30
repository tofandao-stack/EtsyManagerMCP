package dev.etsymanager;

import java.time.Instant;

/**
 * One confirmed write. {@code previous} and {@code updated} are full snapshots of the four
 * editable fields; views and favorites are the listing's counts just before the write.
 */
public record ChangeEntry(
        int id,
        Instant timestamp,
        long listingId,
        String kind,
        String reason,
        TextFields previous,
        TextFields updated,
        int views,
        int favorites) {}
