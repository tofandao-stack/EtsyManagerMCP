package dev.etsymanager;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginTest {
    @TempDir Path dir;

    @Test
    void s256ChallengeMatchesTheRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Login.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void authorizationUrlRequestsOnlyTheMinimumScopesWithPkce() {
        URI url = URI.create(Login.authorizationUrl("key:secret", "https://example.test/cb", "CHALLENGE", "STATE"));
        assertEquals("www.etsy.com", url.getHost());
        assertEquals("/oauth/connect", url.getPath());
        String q = url.getRawQuery();
        assertTrue(q.contains("response_type=code"), q);
        assertTrue(q.contains("client_id=key%3Asecret"), q);
        assertTrue(q.contains("redirect_uri=https%3A%2F%2Fexample.test%2Fcb"), q);
        assertTrue(q.contains("scope=listings_r+listings_w+shops_r") || q.contains("scope=listings_r%20listings_w%20shops_r"), q);
        assertTrue(q.contains("code_challenge=CHALLENGE") && q.contains("code_challenge_method=S256"), q);
        assertTrue(q.contains("state=STATE"), q);
        assertFalse(q.contains("listings_d"), "the delete scope is never requested");
    }

    static class CapturingClient extends FakeEtsyClient {
        final List<String> exchanged = new ArrayList<>();

        @Override
        public TokenSet exchangeCode(String code, String codeVerifier) {
            exchanged.add(code + "|" + codeVerifier);
            return new TokenSet("a", "r", java.time.Instant.EPOCH);
        }
    }

    String run(CapturingClient client, String pasted, Path env) throws Exception {
        Files.writeString(env, "ETSY_API_KEY=key\nETSY_REDIRECT_URI=https://example.test/cb\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Login(client, new EnvFile(env), new BufferedReader(new StringReader(pasted + "\n")),
                new PrintStream(out, true, StandardCharsets.UTF_8), () -> "fixed-state", () -> "fixed-verifier").run();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsTheUrlThenExchangesThePastedCodeWithTheVerifier() throws Exception {
        CapturingClient client = new CapturingClient();
        String printed = run(client, "  abc123  ", dir.resolve(".env"));
        assertTrue(printed.contains("https://www.etsy.com/oauth/connect?"), printed);
        assertTrue(printed.contains("state=fixed-state"), printed);
        assertEquals(List.of("abc123|fixed-verifier"), client.exchanged);
    }

    @Test
    void acceptsAPastedRedirectUrlAndChecksState() throws Exception {
        CapturingClient client = new CapturingClient();
        run(client, "https://example.test/cb?code=xyz&state=fixed-state", dir.resolve(".env"));
        assertEquals(List.of("xyz|fixed-verifier"), client.exchanged);

        CapturingClient other = new CapturingClient();
        assertThrows(IllegalArgumentException.class,
                () -> run(other, "https://example.test/cb?code=xyz&state=forged", dir.resolve(".env")));
        assertTrue(other.exchanged.isEmpty());
    }
}
