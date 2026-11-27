package ua.zentix.almighty.mcp.signin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.Method;
import ua.zentix.almighty.mcp.McpEndpoint;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole keyless sign-in over loopback HTTP, as a client and a browser do it: the 401 from {@code /mcp} points to
 * the resource metadata, that points to the issuer, the client registers, the browser opens the login page (which
 * shows the code), the owner types the code, the page's status leads to the callback with a code and {@code iss},
 * the token endpoint answers form-encoded, and the token works on {@code /mcp} as the new agent. Owners are told a
 * login waits, without the code.
 */
class SignInHttpTest {
    @TempDir
    Path config;
    private Bridge bridge;
    private SignIn signIn;
    private final List<SignIn.Pending> notified = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        Map<String, Method> methods = Map.of(
                "status", a -> CompletableFuture.completedFuture(new JsonPrimitive("status as " + a.caller().name())),
                "notes", a -> CompletableFuture.completedFuture(JsonParser.parseString("{\"notes\":[]}")));
        Agents agents = Agents.load(config);
        bridge = new Bridge(new InetSocketAddress("127.0.0.1", 0), agents, new Dispatch(methods), Set.of(), Set.of());
        signIn = new SignIn(config.resolve("oauth"), new SignIn.Issuer() {
            @Override
            public String issue(String name) throws java.io.IOException {
                Path file = agents.get(name) != null ? agents.revoke(name) : agents.add(name);
                return Files.readString(file).strip();
            }

            @Override
            public boolean exists(String name) {
                return agents.get(name) != null;
            }

            @Override
            public String current(String name) throws java.io.IOException {
                return agents.get(name) == null ? null : Files.readString(agents.get(name).file()).strip();
            }
        }, System::currentTimeMillis);
        SignInEndpoints endpoints = new SignInEndpoints(signIn, "", "127.0.0.1", 0, notified::add);
        bridge.context("/mcp", new McpEndpoint(bridge, Set.of(), "test", endpoints::challenge));
        bridge.context("/.well-known/", endpoints);
        bridge.context("/oauth/", endpoints);
        bridge.start();
    }

    @AfterEach
    void stop() {
        bridge.stop();
    }

    String base() {
        return "http://127.0.0.1:" + bridge.address().getPort();
    }

    HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String url, String type, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void keylessSignInEndToEnd() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}";
        HttpResponse<String> first = post(base() + "/mcp", "application/json", init, null);
        assertEquals(401, first.statusCode());
        String challenge = first.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertTrue(challenge.contains("resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource/mcp\""), challenge);

        JsonObject resource = JsonParser.parseString(get(base() + "/.well-known/oauth-protected-resource/mcp").body()).getAsJsonObject();
        assertEquals(base() + "/mcp", resource.get("resource").getAsString());
        String issuer = resource.getAsJsonArray("authorization_servers").get(0).getAsString();
        JsonObject meta = JsonParser.parseString(get(issuer + "/.well-known/oauth-authorization-server").body()).getAsJsonObject();
        assertEquals(issuer, meta.get("issuer").getAsString());
        assertTrue(meta.get("client_id_metadata_document_supported").getAsBoolean());
        assertTrue(meta.getAsJsonArray("code_challenge_methods_supported").toString().contains("S256"));

        HttpResponse<String> reg = post(meta.get("registration_endpoint").getAsString(), "application/json",
                "{\"client_name\":\"Codex\",\"redirect_uris\":[\"http://127.0.0.1:7777/callback\"],\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"token_endpoint_auth_method\":\"none\"}", null);
        assertEquals(201, reg.statusCode(), reg.body());
        String clientId = JsonParser.parseString(reg.body()).getAsJsonObject().get("client_id").getAsString();

        String authorize = meta.get("authorization_endpoint").getAsString() + "?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc("http://127.0.0.1:7777/callback") + "&code_challenge=" + SignIn.s256(SignInTest.VERIFIER)
                + "&code_challenge_method=S256&state=s1&resource=" + enc(base() + "/mcp");
        HttpResponse<String> page = get(authorize);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String code = page.body().replaceAll("(?s).*<div class=\"code\">(\\d{4})</div>.*", "$1");
        assertTrue(code.matches("\\d{4}"), "the page shows the code");
        assertTrue(page.body().contains("Codex") && page.body().contains("127.0.0.1"), "the page names the client and where it returns");
        assertEquals(1, notified.size(), "owners are told a login waits");
        String notice = ua.zentix.almighty.mcp.Mcp.notice(notified.get(0));
        assertTrue(notice.contains("Codex") && !notice.contains(code), "the notice names the app but never shows the code: " + notice);
        String id = page.body().replaceAll("(?s).*const id=\"([0-9a-f]+)\".*", "$1");

        JsonObject waiting = JsonParser.parseString(get(base() + "/oauth/status?id=" + id).body()).getAsJsonObject();
        assertEquals("waiting", waiting.get("state").getAsString());
        assertEquals("codex", signIn.approve(code, null));
        JsonObject approved = JsonParser.parseString(get(base() + "/oauth/status?id=" + id).body()).getAsJsonObject();
        String callback = approved.get("redirect").getAsString();
        assertTrue(callback.startsWith("http://127.0.0.1:7777/callback?code="), callback);
        assertTrue(callback.contains("iss=" + enc(issuer)), callback);
        String authCode = callback.replaceAll(".*code=([0-9a-f]+).*", "$1");

        HttpResponse<String> token = post(meta.get("token_endpoint").getAsString(), "application/x-www-form-urlencoded",
                "grant_type=authorization_code&code=" + authCode + "&client_id=" + enc(clientId) + "&redirect_uri=" + enc("http://127.0.0.1:7777/callback")
                        + "&code_verifier=" + SignInTest.VERIFIER + "&resource=" + enc(base() + "/mcp"), null);
        assertEquals(200, token.statusCode(), token.body());
        assertEquals("no-store", token.headers().firstValue("Cache-Control").orElse(""));
        String access = JsonParser.parseString(token.body()).getAsJsonObject().get("access_token").getAsString();
        assertEquals(Files.readString(config.resolve("agents/codex.token")).strip(), access, "the access token is the new agent's token");

        HttpResponse<String> call = post(base() + "/mcp", "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}}}", access);
        assertEquals(200, call.statusCode());
        assertTrue(call.body().contains("status as codex"), call.body());
    }

    @Test
    void unknownHostsAndBadRequestsAreRefused() throws Exception {
        // A page reached through another name (DNS rebinding) gets no issuer: raw socket, since HttpClient fixes Host.
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", bridge.address().getPort())) {
            s.getOutputStream().write(("GET /.well-known/oauth-authorization-server HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String status = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).lines().findFirst().orElse("");
            assertTrue(status.contains(" 400 "), status);
        }
        assertEquals(400, post(base() + "/oauth/register", "application/json", "{\"redirect_uris\":[\"http://evil.example/cb\"]}", null).statusCode());
        assertEquals(415, post(base() + "/oauth/register", "text/plain", "{\"redirect_uris\":[\"https://x.example/cb\"]}", null).statusCode(),
                "a web page's no-cors text/plain registration is refused");
        HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/register")).header("Content-Type", "application/json")
                .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{\"redirect_uris\":[\"https://x.example/cb\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreign.statusCode(), "a registration from a foreign web origin is refused");
        HttpResponse<String> image = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/authorize?client_id=x&response_type=code"))
                .header("Sec-Fetch-Dest", "image").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, image.statusCode(), "the sign-in page loaded as an image by another site is refused");
        assertEquals(400, get(base() + "/oauth/authorize?client_id=nope&response_type=code").statusCode());
        HttpResponse<String> badToken = post(base() + "/oauth/token", "application/x-www-form-urlencoded", "grant_type=authorization_code&code=x", null);
        assertEquals(400, badToken.statusCode());
        assertEquals("invalid_grant", JsonParser.parseString(badToken.body()).getAsJsonObject().get("error").getAsString());
    }
}
