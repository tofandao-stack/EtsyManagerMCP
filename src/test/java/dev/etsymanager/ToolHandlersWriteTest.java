package dev.etsymanager;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolHandlersWriteTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;
    FakeEtsyClient etsy;
    ChangeLog log;
    ToolHandlers tools;

    @BeforeEach
    void setUp() {
        etsy = new FakeEtsyClient().add(new Listing(
                7, "Old title", "Old description", List.of("old", "tags"), List.of("silver"), "active", 120, 9));
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

    List<String> warnings(JsonNode result) {
        return result.get("warnings").valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void withoutConfirmItReturnsAPreviewAndChangesNothing() {
        JsonNode r = ok("update_listing_text", "{\"listing_id\":7,\"title\":\"New title\",\"tags\":[\"new\"]}");

        assertEquals("preview", r.get("mode").asString());
        assertEquals("Old title", r.at("/changes/title/old").asString());
        assertEquals("New title", r.at("/changes/title/new").asString());
        assertEquals("old", r.at("/changes/tags/old/0").asString());
        assertEquals("new", r.at("/changes/tags/new/0").asString());
        assertFalse(r.get("changes").has("description"));
        assertTrue(etsy.writes.isEmpty());
        assertTrue(log.list(null, null, null).isEmpty());
    }

    @Test
    void confirmWithoutAReasonIsRejectedAndNothingIsSent() {
        for (String args : List.of(
                "{\"listing_id\":7,\"title\":\"X\",\"confirm\":true}",
                "{\"listing_id\":7,\"title\":\"X\",\"confirm\":true,\"reason\":\"   \"}")) {
            ToolResult r = call("update_listing_text", args);
            assertTrue(r.isError());
            assertTrue(r.body().get("error").asString().contains("reason"));
        }
        assertTrue(etsy.writes.isEmpty());
    }

    @Test
    void confirmedWriteSendsOnlyMentionedFieldsAndIsLogged() {
        JsonNode r = ok("update_listing_text",
                "{\"listing_id\":7,\"tags\":[\"fresh\",\"tag\"],\"confirm\":true,\"reason\":\"test long-tail tags\"}");

        assertEquals("applied", r.get("mode").asString());
        assertEquals(1, etsy.writes.size());
        TextFields sent = etsy.writes.get(0).getValue();
        assertNull(sent.title());
        assertNull(sent.description());
        assertNull(sent.materials());
        assertEquals(List.of("fresh", "tag"), sent.tags());

        List<ChangeEntry> entries = log.list(7L, null, null);
        assertEquals(1, entries.size());
        ChangeEntry e = entries.get(0);
        assertEquals("test long-tail tags", e.reason());
        assertEquals("update", e.kind());
        assertEquals(new TextFields("Old title", "Old description", List.of("old", "tags"), List.of("silver")), e.previous());
        assertEquals(new TextFields("Old title", "Old description", List.of("fresh", "tag"), List.of("silver")), e.updated());
        assertEquals(120, e.views());
        assertEquals(9, e.favorites());
        assertNotNull(e.timestamp());
    }

    @Test
    void fieldsEqualToTheCurrentValueAreNotSentAndAnEmptyEditWritesNothing() {
        JsonNode r = ok("update_listing_text",
                "{\"listing_id\":7,\"title\":\"Old title\",\"confirm\":true,\"reason\":\"noop\"}");
        assertEquals("no_changes", r.get("mode").asString());
        assertTrue(etsy.writes.isEmpty());
        assertTrue(log.list(null, null, null).isEmpty());
    }

    @Test
    void callWithNoEditableFieldIsAnError() {
        assertTrue(call("update_listing_text", "{\"listing_id\":7}").isError());
    }

    @Test
    void validationWarnsButDoesNotBlock() {
        String longTitle = "x".repeat(141);
        String tags = "[" + "\"t\",".repeat(13) + "\"" + "y".repeat(21) + "\"]";
        JsonNode preview = ok("update_listing_text",
                "{\"listing_id\":7,\"title\":\"" + longTitle + "\",\"tags\":" + tags + "}");
        List<String> w = warnings(preview);
        assertTrue(w.stream().anyMatch(s -> s.contains("title") && s.contains("140")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("13 tags")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("20 characters")), w.toString());

        ok("update_listing_text", "{\"listing_id\":7,\"title\":\"" + longTitle
                + "\",\"confirm\":true,\"reason\":\"try long\"}");
        assertEquals(1, etsy.writes.size());
    }

    @Test
    void warnsAboutDisallowedAndRepeatedCharacters() {
        JsonNode r = ok("update_listing_text",
                "{\"listing_id\":7,\"title\":\"Rings & Things & More 50% off: now: always\",\"tags\":[\"bad@tag\"]}");
        List<String> w = warnings(r);
        assertTrue(w.stream().anyMatch(s -> s.contains("title") && s.contains("'&'")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("title") && s.contains("':'")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("tag") && s.contains("'@'")), w.toString());
        assertFalse(w.stream().anyMatch(s -> s.contains("'%'")), w.toString());
    }

    @Test
    void etsyRejectionIsPassedThroughUnchangedAndNothingIsLogged() {
        etsy.failNextWrite = new EtsyException(400, "Title contains a forbidden word");
        ToolResult r = call("update_listing_text",
                "{\"listing_id\":7,\"title\":\"Bad\",\"confirm\":true,\"reason\":\"r\"}");
        assertTrue(r.isError());
        assertEquals("Title contains a forbidden word", r.body().get("error").asString());
        assertTrue(log.list(null, null, null).isEmpty());
    }

    @Test
    void noToolCanTouchPriceQuantityPhotosShippingOrAltText() {
        ok("update_listing_text",
                "{\"listing_id\":7,\"title\":\"T\",\"price\":1,\"quantity\":5,\"alt_text\":\"a\",\"confirm\":true,\"reason\":\"r\"}");
        TextFields sent = etsy.writes.get(0).getValue();
        assertEquals("T", sent.title());
        assertNull(sent.description());
        assertEquals(List.of("get_shop", "list_listings", "get_listing", "update_listing_text",
                "list_changes", "get_listing_history", "restore_listing"), ToolDefinitions.names());
    }
}
