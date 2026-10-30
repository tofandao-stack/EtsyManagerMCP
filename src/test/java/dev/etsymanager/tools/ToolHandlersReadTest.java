package dev.etsymanager.tools;

import dev.etsymanager.model.Listing;
import dev.etsymanager.storage.ChangeLog;
import dev.etsymanager.testing.FakeEtsyClient;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolHandlersReadTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;
    FakeEtsyClient etsy;
    ToolHandlers tools;

    @BeforeEach
    void setUp() {
        etsy = new FakeEtsyClient();
        for (int i = 1; i <= 5; i++) {
            etsy.add(new Listing(i, "Active " + i, "Desc " + i, List.of("a", "b"), List.of("wood"), "active", i * 10, i));
        }
        etsy.add(new Listing(6, "Draft one", "D", List.of(), List.of(), "draft", 0, 0));
        tools = new ToolHandlers(etsy, new ChangeLog(dir.resolve("change-log.json")));
    }

    JsonNode ok(String tool, String argsJson) {
        ToolResult r = tools.call(tool, JSON.readTree(argsJson));
        assertFalse(r.isError(), () -> "unexpected error: " + r.body());
        return r.body();
    }

    @Test
    void getShopReturnsNameAndCounts() {
        JsonNode shop = ok("get_shop", "{}");
        assertEquals(42, shop.get("shop_id").asLong());
        assertEquals("SyntheticShop", shop.get("name").asString());
        assertEquals(5, shop.get("active_listing_count").asInt());
    }

    @Test
    void listListingsFiltersByStateAndDefaultsToActive() {
        assertEquals(5, ok("list_listings", "{}").get("total_count").asInt());
        JsonNode drafts = ok("list_listings", "{\"state\":\"draft\"}");
        assertEquals(1, drafts.get("listings").size());
        assertEquals("Draft one", drafts.get("listings").get(0).get("title").asString());
    }

    @Test
    void listListingsPaginatesUntilNextOffsetIsNull() {
        JsonNode p1 = ok("list_listings", "{\"limit\":2}");
        assertEquals(2, p1.get("listings").size());
        assertEquals(2, p1.get("next_offset").asInt());
        JsonNode p3 = ok("list_listings", "{\"limit\":2,\"offset\":4}");
        assertEquals(1, p3.get("listings").size());
        assertTrue(p3.get("next_offset").isNull());
    }

    @Test
    void getListingReturnsFullPicture() {
        JsonNode l = ok("get_listing", "{\"listing_id\":3}");
        assertEquals("Active 3", l.get("title").asString());
        assertEquals("Desc 3", l.get("description").asString());
        assertEquals("a", l.get("tags").get(0).asString());
        assertEquals("wood", l.get("materials").get(0).asString());
        assertEquals("active", l.get("state").asString());
        assertEquals(30, l.get("views").asInt());
        assertEquals(3, l.get("favorites").asInt());
    }

    @Test
    void etsyErrorsComeBackAsToolErrorsWithEtsysOwnMessage() {
        ToolResult r = tools.call("get_listing", JSON.readTree("{\"listing_id\":999}"));
        assertTrue(r.isError());
        assertEquals("Listing 999 not found", r.body().get("error").asString());
    }

    @Test
    void unknownToolsAreRejected() {
        assertTrue(tools.call("delete_listing", JSON.readTree("{\"listing_id\":1}")).isError());
    }
}
