package dev.etsymanager.etsy;

import dev.etsymanager.model.Listing;
import dev.etsymanager.model.ListingPage;
import dev.etsymanager.model.Shop;
import dev.etsymanager.model.TextFields;
import dev.etsymanager.storage.EnvFile;
import dev.etsymanager.testing.StubEtsyServer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpEtsyClientTest {
    static final String API = "/v3/application";
    static final String TOKEN = "/v3/public/oauth/token";
    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @TempDir Path dir;
    StubEtsyServer stub;
    EnvFile env;
    List<Duration> sleeps = new ArrayList<>();
    HttpEtsyClient client;

    @BeforeEach
    void setUp() throws IOException {
        stub = new StubEtsyServer();
        Path f = dir.resolve(".env");
        Files.writeString(f, "ETSY_API_KEY=key:secret\nETSY_REDIRECT_URI=https://example.test/cb\n"
                + "ETSY_ACCESS_TOKEN=access-1\nETSY_REFRESH_TOKEN=refresh-1\n"
                + "ETSY_TOKEN_EXPIRES_AT=" + NOW.plusSeconds(3600).getEpochSecond() + "\nETSY_SHOP_ID=42\nKEEP=me\n");
        env = new EnvFile(f);
        client = new HttpEtsyClient(HttpClient.newHttpClient(), stub.uri(API), stub.uri(TOKEN), env,
                () -> NOW, sleeps::add);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    static String listingJson(long id, String title) {
        return "{\"listing_id\":" + id + ",\"title\":\"" + title + "\",\"description\":\"d\",\"tags\":[\"a\",\"b\"],"
                + "\"materials\":[\"m\"],\"state\":\"active\",\"views\":7,\"num_favorers\":3}";
    }

    @Test
    void getListingParsesFieldsAndSendsApiKeyAndBearerHeaders() {
        stub.on("GET", API + "/listings/9", StubEtsyServer.Reply.ok(listingJson(9, "Hello")));

        Listing l = client.getListing(9);

        assertEquals(new Listing(9, "Hello", "d", List.of("a", "b"), List.of("m"), "active", 7, 3), l);
        StubEtsyServer.Received r = stub.requests.get(0);
        assertEquals("key:secret", r.header("x-api-key"));
        assertEquals("Bearer access-1", r.header("authorization"));
    }

    @Test
    void listListingsSendsStateLimitOffsetAndReadsTotalCount() {
        stub.on("GET", API + "/shops/42/listings", StubEtsyServer.Reply.ok(
                "{\"count\":57,\"results\":[" + listingJson(1, "A") + "," + listingJson(2, "B") + "]}"));

        ListingPage page = client.listListings("draft", 2, 10);

        assertEquals(57, page.totalCount());
        assertEquals(2, page.listings().size());
        String pq = stub.requests.get(0).pathAndQuery();
        assertTrue(pq.contains("state=draft") && pq.contains("limit=2") && pq.contains("offset=10"), pq);
    }

    @Test
    void shopIsLookedUpFromTheSignedInUserWhenNoShopIdIsConfigured() throws IOException {
        Files.writeString(dir.resolve(".env"), Files.readString(dir.resolve(".env")).replace("ETSY_SHOP_ID=42\n", ""));
        stub.on("GET", API + "/users/me", StubEtsyServer.Reply.ok("{\"user_id\":1,\"shop_id\":77}"))
            .on("GET", API + "/shops/77", StubEtsyServer.Reply.ok(
                    "{\"shop_id\":77,\"shop_name\":\"S\",\"title\":\"T\",\"listing_active_count\":12}"));

        assertEquals(new Shop(77, "S", "T", 12), client.getShop());
    }

    @Test
    void updateSendsOnlyGivenFieldsAsAFormPatch() {
        stub.on("PATCH", API + "/shops/42/listings/9", StubEtsyServer.Reply.ok(listingJson(9, "New & better")));

        client.updateListingText(9, new TextFields("New & better", null, List.of("x", "y z"), null));

        StubEtsyServer.Received r = stub.requests.get(0);
        assertEquals("application/x-www-form-urlencoded", r.header("content-type"));
        assertEquals("title=New+%26+better&tags=x%2Cy+z", r.body());
    }

    @Test
    void etsyErrorMessageIsPassedThroughUnchanged() {
        stub.on("PATCH", API + "/shops/42/listings/9",
                StubEtsyServer.Reply.status(400, "{\"error\":\"Title cannot contain more than one &\"}"));

        EtsyException e = assertThrows(EtsyException.class,
                () -> client.updateListingText(9, new TextFields("a & b & c", null, null, null)));
        assertEquals(400, e.status());
        assertEquals("Title cannot contain more than one &", e.getMessage());
    }

    @Test
    void rateLimitIsRetriedOnceAfterRetryAfter() {
        stub.on("GET", API + "/listings/9",
                new StubEtsyServer.Reply(429, "{\"error\":\"slow down\"}", Map.of("Retry-After", "3")),
                StubEtsyServer.Reply.ok(listingJson(9, "Hello")));

        assertEquals("Hello", client.getListing(9).title());
        assertEquals(List.of(Duration.ofSeconds(3)), sleeps);
        assertEquals(2, stub.requests.size());
    }

    @Test
    void persistentRateLimitSurfacesAClearError() {
        StubEtsyServer.Reply limited = new StubEtsyServer.Reply(429, "{\"error\":\"slow down\"}", Map.of("Retry-After", "2"));
        stub.on("GET", API + "/listings/9", limited, limited);

        RateLimitException e = assertThrows(RateLimitException.class, () -> client.getListing(9));
        assertTrue(e.getMessage().toLowerCase().contains("rate limit"), e.getMessage());
        assertEquals(2, stub.requests.size());
    }

    @Test
    void expiredAccessTokenIsRefreshedBeforeTheCallAndOnlyTokenLinesAreRewritten() throws IOException {
        Files.writeString(dir.resolve(".env"), Files.readString(dir.resolve(".env"))
                .replace("ETSY_TOKEN_EXPIRES_AT=" + NOW.plusSeconds(3600).getEpochSecond(),
                        "ETSY_TOKEN_EXPIRES_AT=" + NOW.minusSeconds(5).getEpochSecond()));
        client = new HttpEtsyClient(HttpClient.newHttpClient(), stub.uri(API), stub.uri(TOKEN), env, () -> NOW, sleeps::add);
        stub.on("POST", TOKEN, StubEtsyServer.Reply.ok(
                        "{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}"))
            .on("GET", API + "/listings/9", StubEtsyServer.Reply.ok(listingJson(9, "Hello")));

        client.getListing(9);

        StubEtsyServer.Received refresh = stub.requests.get(0);
        assertTrue(refresh.body().contains("grant_type=refresh_token"), refresh.body());
        assertTrue(refresh.body().contains("refresh_token=refresh-1"), refresh.body());
        assertTrue(refresh.body().contains("client_id=key&"), "client_id is the keystring only: " + refresh.body());
        assertFalse(refresh.body().contains("secret"), refresh.body());
        assertEquals("Bearer access-2", stub.requests.get(1).header("authorization"));
        Map<String, String> saved = env.read();
        assertEquals("access-2", saved.get("ETSY_ACCESS_TOKEN"));
        assertEquals("refresh-2", saved.get("ETSY_REFRESH_TOKEN"));
        assertEquals(String.valueOf(NOW.plusSeconds(3600).getEpochSecond()), saved.get("ETSY_TOKEN_EXPIRES_AT"));
        assertEquals("me", saved.get("KEEP"));
        assertEquals("42", saved.get("ETSY_SHOP_ID"));
    }

    @Test
    void refreshKeepsTheOldRefreshTokenWhenEtsyDoesNotRotateIt() {
        stub.on("POST", TOKEN, StubEtsyServer.Reply.ok("{\"access_token\":\"access-2\",\"expires_in\":3600}"));

        client.refreshTokens();

        assertEquals("refresh-1", env.read().get("ETSY_REFRESH_TOKEN"));
        assertEquals("access-2", env.read().get("ETSY_ACCESS_TOKEN"));
    }

    @Test
    void a401TriggersOneRefreshAndRetry() {
        stub.on("GET", API + "/listings/9", StubEtsyServer.Reply.status(401, "{\"error\":\"invalid_token\"}"),
                        StubEtsyServer.Reply.ok(listingJson(9, "Hello")))
            .on("POST", TOKEN, StubEtsyServer.Reply.ok(
                    "{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}"));

        assertEquals("Hello", client.getListing(9).title());
        assertEquals("Bearer access-2", stub.requests.get(2).header("authorization"));
    }

    @Test
    void expiredRefreshTokenTellsTheOwnerToLoginAgain() {
        stub.on("POST", TOKEN, StubEtsyServer.Reply.status(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}"));

        ReauthRequiredException e = assertThrows(ReauthRequiredException.class, () -> client.refreshTokens());
        assertTrue(e.getMessage().contains("login"), e.getMessage());
    }

    @Test
    void exchangeCodeSendsPkceFormAndStoresTokens() {
        stub.on("POST", TOKEN, StubEtsyServer.Reply.ok(
                "{\"access_token\":\"A\",\"refresh_token\":\"R\",\"expires_in\":3600}"));

        client.exchangeCode("the-code", "the-verifier");

        String body = stub.requests.get(0).body();
        assertTrue(body.contains("grant_type=authorization_code"), body);
        assertTrue(body.contains("code=the-code"), body);
        assertTrue(body.contains("code_verifier=the-verifier"), body);
        assertTrue(body.contains("redirect_uri=https%3A%2F%2Fexample.test%2Fcb"), body);
        assertNull(stub.requests.get(0).header("authorization"));
        assertEquals("A", env.read().get("ETSY_ACCESS_TOKEN"));
        assertEquals("R", env.read().get("ETSY_REFRESH_TOKEN"));
    }

    @Test
    void secretsNeverAppearInErrorMessages() {
        stub.on("GET", API + "/listings/9", StubEtsyServer.Reply.status(500, "{\"error\":\"boom\"}"));
        EtsyException e = assertThrows(EtsyException.class, () -> client.getListing(9));
        assertFalse(e.getMessage().contains("access-1") || e.getMessage().contains("refresh-1")
                || e.getMessage().contains("key:secret"));
    }
}
