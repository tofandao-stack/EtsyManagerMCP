package dev.etsymanager;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Read-only checks against the real Etsy API. Opt-in: {@code mvn test -Plive} with a logged-in .env
 * (path in ETSY_ENV_FILE, default ./.env). Never part of the default build.
 */
@Tag("live")
class LiveEtsyTest {
    private final EtsyClient etsy = new HttpEtsyClient(HttpClient.newHttpClient(),
            URI.create("https://api.etsy.com/v3/application"), URI.create("https://api.etsy.com/v3/public/oauth/token"),
            new EnvFile(Path.of(System.getenv().getOrDefault("ETSY_ENV_FILE", ".env"))),
            Instant::now, d -> { try { Thread.sleep(d); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });

    @Test
    void shopAndListingsAreReadable() {
        Shop shop = etsy.getShop();
        assertFalse(shop.name().isBlank());
        ListingPage page = etsy.listListings("active", 5, 0);
        assertTrue(page.totalCount() >= page.listings().size());
        if (!page.listings().isEmpty()) {
            Listing first = page.listings().get(0);
            assertEquals(first.id(), etsy.getListing(first.id()).id());
        }
    }

    @Test
    void refreshTokenRotationBehaviourIsObservable() {
        // Unverified assumption from the spec: does Etsy rotate the refresh token? Run once and inspect .env.
        TokenSet before = etsy.refreshTokens();
        assertNotNull(before.accessToken());
        assertTrue(Duration.between(Instant.now(), before.expiresAt()).toMinutes() > 30);
    }
}
