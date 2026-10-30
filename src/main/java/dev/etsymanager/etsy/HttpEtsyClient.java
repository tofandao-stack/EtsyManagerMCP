package dev.etsymanager.etsy;

import dev.etsymanager.model.Listing;
import dev.etsymanager.model.ListingPage;
import dev.etsymanager.model.Shop;
import dev.etsymanager.model.TextFields;
import dev.etsymanager.model.TokenSet;
import dev.etsymanager.storage.EnvFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Etsy Open API v3 over the JDK HTTP client. Tokens live in the .env file and are refreshed on expiry
 * (or on a 401). Only the owner's own shop is ever addressed.
 */
public class HttpEtsyClient implements EtsyClient {
    static final String ACCESS_TOKEN = "ETSY_ACCESS_TOKEN";
    static final String REFRESH_TOKEN = "ETSY_REFRESH_TOKEN";
    static final String EXPIRES_AT = "ETSY_TOKEN_EXPIRES_AT";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final long DEFAULT_RETRY_SECONDS = 1;
    private static final String LOGIN_HINT = "Run `java -jar etsy-manager-mcp.jar login` again.";

    private final HttpClient http;
    private final URI apiBase;
    private final URI tokenUri;
    private final EnvFile env;
    private final Supplier<Instant> clock;
    private final Consumer<Duration> sleeper;
    private Long shopId;

    public HttpEtsyClient(HttpClient http, URI apiBase, URI tokenUri, EnvFile env,
                          Supplier<Instant> clock, Consumer<Duration> sleeper) {
        this.http = http;
        this.apiBase = apiBase;
        this.tokenUri = tokenUri;
        this.env = env;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    // ---- shop & listings -------------------------------------------------------------------

    @Override
    public Shop getShop() {
        long id = shopId();
        JsonNode s = send("GET", "/shops/" + id, null, null);
        return new Shop(s.path("shop_id").asLong(id), s.path("shop_name").asString(""),
                s.path("title").asString(""), s.path("listing_active_count").asInt(0));
    }

    @Override
    public ListingPage listListings(String state, int limit, int offset) {
        JsonNode r = send("GET", "/shops/" + shopId() + "/listings",
                Map.of("state", state, "limit", String.valueOf(limit), "offset", String.valueOf(offset)), null);
        List<Listing> listings = new ArrayList<>();
        for (JsonNode l : r.path("results")) listings.add(parseListing(l));
        return new ListingPage(r.path("count").asInt(listings.size()), listings);
    }

    @Override
    public Listing getListing(long listingId) {
        return parseListing(send("GET", "/listings/" + listingId, null, null));
    }

    @Override
    public Listing updateListingText(long listingId, TextFields edit) {
        Map<String, String> form = new LinkedHashMap<>();
        if (edit.title() != null) form.put("title", edit.title());
        if (edit.description() != null) form.put("description", edit.description());
        if (edit.tags() != null) form.put("tags", String.join(",", edit.tags()));
        if (edit.materials() != null) form.put("materials", String.join(",", edit.materials()));
        return parseListing(send("PATCH", "/shops/" + shopId() + "/listings/" + listingId, null, form));
    }

    private long shopId() {
        if (shopId != null) return shopId;
        String configured = env.read().get("ETSY_SHOP_ID");
        if (configured != null && !configured.isBlank()) {
            shopId = Long.parseLong(configured.strip());
        } else {
            shopId = send("GET", "/users/me", null, null).path("shop_id").asLong();
        }
        return shopId;
    }

    private static Listing parseListing(JsonNode l) {
        return new Listing(l.path("listing_id").asLong(), l.path("title").asString(""),
                l.path("description").asString(""), strings(l.path("tags")), strings(l.path("materials")),
                l.path("state").asString(""), l.path("views").asInt(0), l.path("num_favorers").asInt(0));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asString());
        return out;
    }

    // ---- tokens -----------------------------------------------------------------------------

    @Override
    public synchronized TokenSet exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", keystring(apiKey()));
        form.put("redirect_uri", env.read().getOrDefault("ETSY_REDIRECT_URI", ""));
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        return tokenRequest(form, null);
    }

    @Override
    public synchronized TokenSet refreshTokens() {
        String refresh = env.read().get(REFRESH_TOKEN);
        if (refresh == null || refresh.isBlank()) {
            throw new ReauthRequiredException("No refresh token is stored. " + LOGIN_HINT);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", keystring(apiKey()));
        form.put("refresh_token", refresh);
        return tokenRequest(form, refresh);
    }

    private TokenSet tokenRequest(Map<String, String> form, String oldRefresh) {
        HttpResponse<String> res = execute("POST", tokenUri, form, false);
        if (res.statusCode() == 429) res = retryAfter(res, () -> execute("POST", tokenUri, form, false));
        if (res.statusCode() / 100 != 2) {
            JsonNode body = parse(res.body());
            if (oldRefresh != null && (res.statusCode() == 400 || res.statusCode() == 401)) {
                throw new ReauthRequiredException(
                        "Etsy rejected the refresh token (" + errorText(body, res.body()) + "). " + LOGIN_HINT);
            }
            throw failure(res);
        }
        JsonNode t = parse(res.body());
        Instant expires = clock.get().plusSeconds(t.path("expires_in").asLong(3600));
        String refresh = t.path("refresh_token").asString(oldRefresh);
        if (refresh == null) throw new EtsyException(502, "Etsy's token response contained no refresh token");
        TokenSet tokens = new TokenSet(t.path("access_token").asString(), refresh, expires);
        env.update(Map.of(ACCESS_TOKEN, tokens.accessToken(), REFRESH_TOKEN, tokens.refreshToken(),
                EXPIRES_AT, String.valueOf(expires.getEpochSecond())));
        return tokens;
    }

    /** The OAuth client_id is the keystring alone; the x-api-key header takes the full "keystring:secret" value. */
    public static String keystring(String apiKey) {
        int colon = apiKey.indexOf(':');
        return colon < 0 ? apiKey : apiKey.substring(0, colon);
    }

    private String apiKey() {
        String key = env.read().get("ETSY_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("ETSY_API_KEY is not set in " + env.path());
        return key;
    }

    private synchronized String accessToken() {
        Map<String, String> v = env.read();
        String token = v.get(ACCESS_TOKEN);
        String expiresAt = v.get(EXPIRES_AT);
        boolean stale = token == null || token.isBlank() || expiresAt == null
                || clock.get().plus(REFRESH_MARGIN).isAfter(Instant.ofEpochSecond(Long.parseLong(expiresAt.strip())));
        if (stale) return refreshTokens().accessToken();
        return token;
    }

    // ---- transport --------------------------------------------------------------------------

    private JsonNode send(String method, String path, Map<String, String> query, Map<String, String> form) {
        URI uri = URI.create(apiBase + path + (query == null ? "" : "?" + encode(query)));
        HttpResponse<String> res = execute(method, uri, form, true);
        if (res.statusCode() == 401) {
            synchronized (this) {
                refreshTokens();
            }
            res = execute(method, uri, form, true);
        }
        if (res.statusCode() == 429) res = retryAfter(res, () -> execute(method, uri, form, true));
        if (res.statusCode() / 100 != 2) throw failure(res);
        return parse(res.body());
    }

    private HttpResponse<String> execute(String method, URI uri, Map<String, String> form, boolean authenticated) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("x-api-key", apiKey());
        if (authenticated) b.header("Authorization", "Bearer " + accessToken());
        if (form != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded");
            b.method(method, HttpRequest.BodyPublishers.ofString(encode(form)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EtsyException(503, "Could not reach Etsy: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EtsyException(503, "Interrupted while calling Etsy");
        }
    }

    /** Waits for Retry-After and retries once; a second 429 is surfaced as a rate-limit error. */
    private HttpResponse<String> retryAfter(HttpResponse<String> limited, Supplier<HttpResponse<String>> retry) {
        sleeper.accept(retryDelay(limited));
        HttpResponse<String> second = retry.get();
        if (second.statusCode() == 429) {
            throw new RateLimitException("Etsy rate limit exceeded and still limited after one retry (retry-after "
                    + retryDelay(second).toSeconds() + "s). Stop and try again later.");
        }
        return second;
    }

    private static Duration retryDelay(HttpResponse<String> res) {
        long seconds = res.headers().firstValue("retry-after").map(v -> {
            try {
                return Long.parseLong(v.strip());
            } catch (NumberFormatException e) {
                return DEFAULT_RETRY_SECONDS;
            }
        }).orElse(DEFAULT_RETRY_SECONDS);
        return Duration.ofSeconds(seconds);
    }

    private static EtsyException failure(HttpResponse<String> res) {
        return new EtsyException(res.statusCode(), errorText(parse(res.body()), res.body()));
    }

    /** Etsy's own message, unchanged: the "error" field when the body is JSON, else the raw body. */
    private static String errorText(JsonNode body, String raw) {
        if (body.isObject() && body.path("error").isString()) return body.path("error").asString();
        return raw == null || raw.isBlank() ? "Etsy returned an empty error response" : raw;
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            return JSON.missingNode();
        }
    }

    private static String encode(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        fields.forEach((k, v) -> parts.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }
}
