package dev.etsymanager.auth;

import dev.etsymanager.etsy.EtsyClient;
import dev.etsymanager.etsy.HttpEtsyClient;
import dev.etsymanager.storage.EnvFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The one-off `login` command: print the authorization URL, read the pasted code, exchange it for tokens. */
public class Login {
    static final String SCOPES = "listings_r listings_w shops_r";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EtsyClient etsy;
    private final EnvFile env;
    private final BufferedReader in;
    private final PrintStream out;
    private final Supplier<String> stateSupplier;
    private final Supplier<String> verifierSupplier;

    public Login(EtsyClient etsy, EnvFile env, BufferedReader in, PrintStream out,
                 Supplier<String> stateSupplier, Supplier<String> verifierSupplier) {
        this.etsy = etsy;
        this.env = env;
        this.in = in;
        this.out = out;
        this.stateSupplier = stateSupplier;
        this.verifierSupplier = verifierSupplier;
    }

    public static Login interactive(EtsyClient etsy, EnvFile env) {
        return new Login(etsy, env, new BufferedReader(new java.io.InputStreamReader(System.in)), System.out,
                () -> randomToken(24), () -> randomToken(48));
    }

    public void run() {
        Map<String, String> cfg = env.read();
        String apiKey = require(cfg, "ETSY_API_KEY");
        String redirect = require(cfg, "ETSY_REDIRECT_URI");
        String state = stateSupplier.get();
        String verifier = verifierSupplier.get();

        out.println("Open this URL in your browser and approve access:");
        out.println();
        out.println(authorizationUrl(HttpEtsyClient.keystring(apiKey), redirect, challengeFor(verifier), state));
        out.println();
        out.println("Then paste the code shown on the redirect page (or the whole redirected URL) and press Enter:");
        etsy.exchangeCode(extractCode(readLine(), state), verifier);
        out.println("Logged in. Tokens were saved to " + env.path() + ".");
    }

    static String authorizationUrl(String apiKey, String redirectUri, String challenge, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", apiKey);
        q.put("redirect_uri", redirectUri);
        q.put("scope", SCOPES);
        q.put("state", state);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        StringBuilder sb = new StringBuilder("https://www.etsy.com/oauth/connect?");
        q.forEach((k, v) -> sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extractCode(String pasted, String expectedState) {
        String v = pasted.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("No code was pasted");
        if (!v.contains("code=")) return v;
        String query = v.contains("?") ? v.substring(v.indexOf('?') + 1) : v;
        String code = null;
        String state = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("code=")) code = pair.substring(5);
            if (pair.startsWith("state=")) state = pair.substring(6);
        }
        if (code == null) throw new IllegalArgumentException("No code found in the pasted text");
        if (state != null && !state.equals(expectedState)) {
            throw new IllegalArgumentException("The pasted state does not match this login attempt; start `login` again");
        }
        return code;
    }

    private String readLine() {
        try {
            String line = in.readLine();
            if (line == null) throw new IllegalArgumentException("No code was pasted");
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String require(Map<String, String> cfg, String key) {
        String v = cfg.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " must be set in .env before login");
        return v;
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
