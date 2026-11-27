package ua.zentix.almighty.mcp.signin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in as the MCP authorization profile requires it: PKCE S256, exact redirects (loopback port-agnostic), one
 * use per code, refresh rotation with dead old tokens, the owner's typed code as the only consent, and approval never
 * taking over an agent the owner made by hand.
 */
class SignInTest {
    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    /** Agents as the server has them: name to current token. */
    final Map<String, String> made = new HashMap<>(Map.of("gm", "gm-token", "builder", "builder-token"));
    int issued;

    SignIn signIn() {
        return new SignIn(dir, new SignIn.Issuer() {
            @Override
            public String issue(String name) {
                String t = name + "-token-" + (++issued);
                made.put(name, t);
                return t;
            }

            @Override
            public boolean exists(String name) {
                return made.containsKey(name);
            }

            @Override
            public String current(String name) {
                return made.get(name);
            }
        }, now::get);
    }

    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    static Map<String, String> authorizeQuery(String clientId, String redirect) {
        Map<String, String> q = new HashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId);
        q.put("redirect_uri", redirect);
        q.put("code_challenge", SignIn.s256(VERIFIER));
        q.put("code_challenge_method", "S256");
        q.put("state", "xyz");
        q.put("resource", "http://127.0.0.1:25650/mcp");
        return q;
    }

    SignIn.Client register(SignIn s, String name, String redirect) throws Exception {
        JsonObject r = s.register(JsonParser.parseString("{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"" + redirect + "\"]}").getAsJsonObject());
        return s.client(r.get("client_id").getAsString());
    }

    @Test
    void fullLoginGivesAnAgentTokenAndRefreshRotates() throws Exception {
        SignIn s = signIn();
        SignIn.Client c = register(s, "Claude Code", "http://localhost:33418/callback");
        // Loopback redirects may come back on another port (RFC 8252).
        SignIn.Pending p = s.authorize(c, authorizeQuery(c.id(), "http://localhost:54321/callback"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertTrue(p.code().matches("\\d{4}"));
        assertNull(p.outcome("http://127.0.0.1:25650"), "nothing happens until the owner types the code");
        assertEquals("claude-code", s.approve(p.code(), null));
        String redirect = p.outcome("http://127.0.0.1:25650");
        assertTrue(redirect.startsWith("http://localhost:54321/callback?code="), redirect);
        assertTrue(redirect.contains("&state=xyz") && redirect.contains("&iss=http%3A%2F%2F127.0.0.1%3A25650"), redirect);
        String code = redirect.replaceAll(".*code=([0-9a-f]+).*", "$1");

        Map<String, String> form = new HashMap<>(Map.of("grant_type", "authorization_code", "code", code, "client_id", c.id(),
                "redirect_uri", "http://localhost:54321/callback", "code_verifier", VERIFIER));
        JsonObject tokens = s.exchange(form);
        assertEquals("claude-code-token-1", tokens.get("access_token").getAsString());
        assertEquals("Bearer", tokens.get("token_type").getAsString());
        assertThrows(SignIn.OAuthError.class, () -> s.exchange(form), "a code works once");

        Map<String, String> refresh = Map.of("grant_type", "refresh_token", "refresh_token", tokens.get("refresh_token").getAsString(), "client_id", c.id());
        JsonObject next = s.exchange(refresh);
        assertEquals("claude-code-token-2", next.get("access_token").getAsString(), "refresh rotates the agent token");
        SignIn.OAuthError reused = assertThrows(SignIn.OAuthError.class, () -> s.exchange(refresh));
        assertEquals("invalid_grant", reused.error(), "a used refresh token is dead");

        // State survives a restart: the refresh token still works with a fresh instance.
        JsonObject again = signIn().exchange(Map.of("grant_type", "refresh_token", "refresh_token", next.get("refresh_token").getAsString()));
        assertEquals("claude-code-token-3", again.get("access_token").getAsString());
        // The owner revokes the agent (its token rotates outside the sign-in): the refresh token dies with the old token.
        made.put("claude-code", "rotated-by-owner");
        assertEquals("invalid_grant", assertThrows(SignIn.OAuthError.class, () -> signIn().exchange(Map.of("grant_type", "refresh_token",
                "refresh_token", again.get("refresh_token").getAsString()))).error(), "revoke ends a signed-in client");
        assertEquals("rotated-by-owner", made.get("claude-code"), "and the owner's new token is left alone");
    }

    @Test
    void aRemovedAgentReMadeByHandIsNeverTakenOver() throws Exception {
        SignIn s = signIn();
        SignIn.Client c = register(s, "Claude Code", "http://localhost:1/callback");
        SignIn.Pending p = s.authorize(c, authorizeQuery(c.id(), "http://localhost:1/callback"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        s.approve(p.code(), null);
        JsonObject tokens = s.exchange(Map.of("grant_type", "authorization_code", "code", p.outcome("x").replaceAll(".*code=([0-9a-f]+).*", "$1"),
                "client_id", c.id(), "code_verifier", VERIFIER));
        // Owner: /almighty agent remove claude-code, later /almighty agent add claude-code for a tool of their own.
        made.remove("claude-code");
        made.put("claude-code", "owner-made");
        assertThrows(SignIn.OAuthError.class, () -> s.exchange(Map.of("grant_type", "refresh_token", "refresh_token",
                tokens.get("refresh_token").getAsString())), "the old client's refresh token cannot take the new agent");
        SignIn.Pending q = s.authorize(c, authorizeQuery(c.id(), "http://localhost:1/callback"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertEquals("claude-code-2", s.approve(q.code(), null), "a new login gets another name");
        assertEquals("owner-made", made.get("claude-code"));
        assertThrows(SignIn.OAuthError.class, () -> s.approve("0000", "Bad Name"));
    }

    @Test
    void aDerivedNameNeverLandsOnAnElevatedAgent() throws Exception {
        Set<String> elevated = new HashSet<>();
        SignIn s = new SignIn(dir, new SignIn.Issuer() {
            @Override
            public String issue(String name) {
                String t = name + "-token-" + (++issued);
                made.put(name, t);
                return t;
            }

            @Override
            public boolean exists(String name) {
                return made.containsKey(name);
            }

            @Override
            public String current(String name) {
                return made.get(name);
            }

            @Override
            public boolean elevated(String name) {
                return elevated.contains(name);
            }
        }, now::get);
        SignIn.Client mine = register(s, "Claude Code", "http://localhost:1/callback");
        SignIn.Pending p = s.authorize(mine, authorizeQuery(mine.id(), "http://localhost:1/callback"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertEquals("claude-code", s.approve(p.code(), "claude-code"));
        s.exchange(Map.of("grant_type", "authorization_code", "code", p.outcome("x").replaceAll(".*code=([0-9a-f]+).*", "$1"),
                "client_id", mine.id(), "code_verifier", VERIFIER));
        elevated.add("claude-code"); // the owner approved it with "owner"
        SignIn.Client other = register(s, "Claude Code", "http://localhost:2/callback");
        SignIn.Pending q = s.authorize(other, authorizeQuery(other.id(), "http://localhost:2/callback"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertEquals("claude-code-2", s.approve(q.code(), null), "another app named alike never inherits the owner's app's rights");
    }

    @Test
    void pkceRedirectAndResourceAreChecked() throws Exception {
        SignIn s = signIn();
        SignIn.Client c = register(s, "Codex", "http://127.0.0.1:1455/callback/x");
        Map<String, String> q = authorizeQuery(c.id(), "http://127.0.0.1:1455/callback/other");
        assertThrows(SignIn.OAuthError.class, () -> s.authorize(c, q, "http://127.0.0.1:25650/mcp", "127.0.0.1"), "path must match");
        Map<String, String> plain = authorizeQuery(c.id(), "http://127.0.0.1:1455/callback/x");
        plain.put("code_challenge_method", "plain");
        assertThrows(SignIn.OAuthError.class, () -> s.authorize(c, plain, "http://127.0.0.1:25650/mcp", "127.0.0.1"), "S256 only");
        Map<String, String> other = authorizeQuery(c.id(), "http://127.0.0.1:1455/callback/x");
        other.put("resource", "http://127.0.0.1:25650/other");
        assertEquals("invalid_target", assertThrows(SignIn.OAuthError.class,
                () -> s.authorize(c, other, "http://127.0.0.1:25650/mcp", "127.0.0.1")).error());

        Map<String, String> withQuery = authorizeQuery(c.id(), "http://127.0.0.1:1455/callback/x");
        withQuery.put("resource", "http://127.0.0.1:25650/mcp?profile=compact&images=false");
        SignIn.Pending p = s.authorize(c, withQuery, "http://127.0.0.1:25650/mcp", "127.0.0.1");
        s.approve(p.code(), "codex");
        String code = p.outcome("x").replaceAll(".*code=([0-9a-f]+).*", "$1");
        assertEquals("invalid_grant", assertThrows(SignIn.OAuthError.class, () -> s.exchange(Map.of("grant_type", "authorization_code",
                "code", code, "client_id", c.id(), "code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier"))).error());
        assertEquals("invalid_grant", assertThrows(SignIn.OAuthError.class, () -> s.exchange(Map.of("grant_type", "authorization_code",
                "code", code, "client_id", c.id(), "code_verifier", VERIFIER))).error(), "a code is spent by a failed attempt too");
    }

    @Test
    void codesExpireAreUniqueAndOnlyTheTypedOneIsApproved() throws Exception {
        SignIn s = signIn();
        Set<String> codes = new HashSet<>();
        SignIn.Client c = null;
        for (int i = 0; i < SignIn.MAX_PENDING; i++) {
            c = register(s, "Cursor " + i, "cursor://anysphere.cursor-retrieval/oauth/callback");
            codes.add(s.authorize(c, authorizeQuery(c.id(), "cursor://anysphere.cursor-retrieval/oauth/callback"), "http://127.0.0.1:25650/mcp", "1.2.3.4").code());
        }
        SignIn.Client last = c;
        assertEquals(429, assertThrows(SignIn.OAuthError.class, () -> s.authorize(last, authorizeQuery(last.id(),
                "cursor://anysphere.cursor-retrieval/oauth/callback"), "http://127.0.0.1:25650/mcp", "1.2.3.4")).status(), "pending logins are capped overall");
        assertEquals(SignIn.MAX_PENDING, codes.size(), "waiting codes are unique");
        SignIn.Client c2 = register(s, "Cursor x", "cursor://anysphere.cursor-retrieval/oauth/callback");
        assertEquals(429, assertThrows(SignIn.OAuthError.class, () -> s.authorize(c2, authorizeQuery(c2.id(),
                "cursor://anysphere.cursor-retrieval/oauth/callback"), "http://127.0.0.1:25650/mcp", "1.2.3.4")).status(), "logins cannot pile up");
        String wrong = "0000";
        while (codes.contains(wrong)) wrong = String.format("%04d", Integer.parseInt(wrong) + 1);
        String w = wrong;
        assertThrows(SignIn.OAuthError.class, () -> s.approve(w, null));
        String one = codes.iterator().next();
        assertTrue(s.deny(one));
        assertThrows(SignIn.OAuthError.class, () -> s.approve(one, null), "a denied login cannot be approved");
        now.addAndGet(SignIn.PENDING_MS + 1);
        assertTrue(s.waiting().isEmpty(), "waiting logins expire after 10 minutes");
    }

    @Test
    void approvalNeverTakesOverAHandMadeAgent() throws Exception {
        SignIn s = signIn();
        SignIn.Client c = register(s, "Builder", "http://127.0.0.1:9/cb");
        SignIn.Pending p = s.authorize(c, authorizeQuery(c.id(), "http://127.0.0.1:9/cb"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertEquals("builder-2", s.approve(p.code(), null), "the owner's agent builder is kept");
        SignIn.Pending q = s.authorize(c, authorizeQuery(c.id(), "http://127.0.0.1:9/cb"), "http://127.0.0.1:25650/mcp", "127.0.0.1");
        assertThrows(SignIn.OAuthError.class, () -> s.approve(q.code(), "builder"));
        assertThrows(SignIn.OAuthError.class, () -> s.approve(q.code(), "gm"));
    }

    @Test
    void redirectsAndNames() {
        for (String ok : new String[]{"http://127.0.0.1:1/cb", "http://localhost/cb", "http://[::1]:5/cb", "https://claude.ai/api/mcp/auth_callback",
                "https://chatgpt.com/connector_platform_oauth_redirect", "cursor://anysphere.cursor-retrieval/oauth/x", "vscode://ms.vscode/cb"}) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> SignIn.checkRedirect(ok), ok);
        }
        for (String bad : new String[]{"http://evil.example/cb", "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "https://x.example/cb#frag", "nonsense"}) {
            assertThrows(SignIn.OAuthError.class, () -> SignIn.checkRedirect(bad), bad);
        }
        assertEquals("claude-code", SignIn.agentName("Claude Code"));
        assertEquals("visual-studio-code", SignIn.agentName("Visual Studio Code"));
        assertEquals("agent", SignIn.agentName("☃"));
        assertTrue(SignIn.loopback("127.0.0.1") && SignIn.loopback("[::1]") && SignIn.loopback("localhost"));
        assertFalse(SignIn.loopback("127.evil.example") || SignIn.loopback("127.0.0.1.nip.io") || SignIn.loopback("127.0.0.999"));
        assertEquals("Evil  name", SignIn.displayName("Evil\n\rname", null), "names reach logs and chat on one line");
        assertFalse(SignIn.agentName("x".repeat(80)).length() > 28);
        assertNotEquals(SignIn.s256("a"), SignIn.s256("b"));
        // RFC 7636 appendix B test vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SignIn.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
}
