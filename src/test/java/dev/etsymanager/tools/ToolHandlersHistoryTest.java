package dev.etsymanager.tools;

import dev.etsymanager.model.ChangeEntry;
import dev.etsymanager.model.Listing;
import dev.etsymanager.model.TextFields;
import dev.etsymanager.storage.ChangeLog;
import dev.etsymanager.testing.FakeEtsyClient;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolHandlersHistoryTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;
    FakeEtsyClient etsy;
    ChangeLog log;
    ToolHandlers tools;

    @BeforeEach
    void setUp() {
        etsy = new FakeEtsyClient()
                .add(new Listing(1, "One", "d1", List.of("a"), List.of("m"), "active", 10, 1))
                .add(new Listing(2, "Two", "d2", List.of("b"), List.of("m"), "active", 20, 2));
        log = new ChangeLog(dir.resolve("change-log.json"));
        tools = new ToolHandlers(etsy, log);
    }

    ToolResult call(String tool, String args) {
        return tools.call(tool, JSON.readTree(args));
    }

    JsonNode ok(String tool, String args) {
        ToolResult r = call(tool, args);
        assertFalse(r.isError(), () -> "unexpected error: " + r.body());
        return r.body();
    }

    void edit(long id, String title, String reason) {
        ok("update_listing_text", "{\"listing_id\":" + id + ",\"title\":\"" + title
                + "\",\"confirm\":true,\"reason\":\"" + reason + "\"}");
    }

    @Test
    void listChangesFiltersByListingAndDate() {
        log.append(Instant.parse("2026-01-01T00:00:00Z"), 1, "update", "r1", t("A"), t("B"), 0, 0);
        log.append(Instant.parse("2026-02-01T00:00:00Z"), 2, "update", "r2", t("A"), t("B"), 0, 0);
        log.append(Instant.parse("2026-03-01T00:00:00Z"), 1, "update", "r3", t("B"), t("C"), 0, 0);

        assertEquals(3, ok("list_changes", "{}").get("changes").size());
        JsonNode one = ok("list_changes", "{\"listing_id\":1}").get("changes");
        assertEquals(2, one.size());
        assertEquals("r1", one.get(0).get("reason").asString());
        JsonNode ranged = ok("list_changes", "{\"since\":\"2026-01-15\",\"until\":\"2026-02-15\"}").get("changes");
        assertEquals(1, ranged.size());
        assertEquals("r2", ranged.get(0).get("reason").asString());
    }

    @Test
    void changeEntriesCarryPreviousValuesTimestampAndCounts() {
        edit(1, "Fresh", "why");
        JsonNode c = ok("list_changes", "{}").get("changes").get(0);
        assertEquals("One", c.at("/previous/title").asString());
        assertEquals("Fresh", c.at("/updated/title").asString());
        assertEquals(10, c.get("views").asInt());
        assertEquals(1, c.get("favorites").asInt());
        assertEquals("why", c.get("reason").asString());
        assertFalse(c.get("timestamp").asString().isEmpty());
    }

    @Test
    void historyShowsChangesWithRecordedCountsAndTheCurrentOnes() {
        edit(1, "Fresh", "first");
        etsy.listings.put(1L, new Listing(1, "Fresh", "d1", List.of("a"), List.of("m"), "active", 55, 6));
        edit(1, "Fresher", "second");
        etsy.listings.put(1L, new Listing(1, "Fresher", "d1", List.of("a"), List.of("m"), "active", 90, 8));

        JsonNode h = ok("get_listing_history", "{\"listing_id\":1}");
        JsonNode changes = h.get("changes");
        assertEquals(2, changes.size());
        assertEquals(10, changes.get(0).get("views").asInt());
        assertEquals(55, changes.get(1).get("views").asInt());
        assertEquals(90, h.at("/current/views").asInt());
        assertEquals(8, h.at("/current/favorites").asInt());
    }

    @Test
    void restorePreviewsThenAppliesTheLoggedPreviousVersionAndIsItselfLogged() {
        edit(1, "Fresh", "experiment");

        JsonNode preview = ok("restore_listing", "{\"listing_id\":1}");
        assertEquals("preview", preview.get("mode").asString());
        assertEquals("Fresh", preview.at("/changes/title/old").asString());
        assertEquals("One", preview.at("/changes/title/new").asString());
        assertEquals(1, etsy.writes.size());

        assertTrue(call("restore_listing", "{\"listing_id\":1,\"confirm\":true}").isError());
        assertEquals(1, etsy.writes.size());

        ok("restore_listing", "{\"listing_id\":1,\"confirm\":true,\"reason\":\"did not help\"}");
        assertEquals("One", etsy.listings.get(1L).title());
        List<ChangeEntry> entries = log.list(1L, null, null);
        assertEquals(2, entries.size());
        assertEquals("restore", entries.get(1).kind());
        assertEquals("did not help", entries.get(1).reason());
        assertEquals("Fresh", entries.get(1).previous().title());
    }

    @Test
    void restoreCanTargetASpecificChange() {
        edit(1, "B", "r1");
        edit(1, "C", "r2");
        ok("restore_listing", "{\"listing_id\":1,\"change_id\":2,\"confirm\":true,\"reason\":\"back to B\"}");
        assertEquals("B", etsy.listings.get(1L).title());
    }

    @Test
    void restoreWithoutLoggedChangesIsAnError() {
        ToolResult r = call("restore_listing", "{\"listing_id\":2}");
        assertTrue(r.isError());
        assertTrue(r.body().get("error").asString().contains("No logged change"));
    }

    @Test
    void restoreRejectsAChangeBelongingToAnotherListing() {
        edit(2, "Z", "r");
        assertTrue(call("restore_listing", "{\"listing_id\":1,\"change_id\":1}").isError());
    }

    private static TextFields t(String title) {
        return new TextFields(title, "d", List.of(), List.of());
    }
}
