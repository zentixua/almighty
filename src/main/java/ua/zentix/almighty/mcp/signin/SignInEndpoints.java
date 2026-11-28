package ua.zentix.almighty.mcp.signin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import ua.zentix.almighty.mcp.McpEndpoint;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The sign-in's HTTP side on the bridge port (no bearer token: this is how a client gets one):
 * <ul>
 * <li>{@code /.well-known/oauth-protected-resource[/mcp]} (RFC 9728): the {@code /mcp} resource and its issuer;</li>
 * <li>{@code /.well-known/oauth-authorization-server} (RFC 8414) and {@code /.well-known/openid-configuration}:
 * endpoints, PKCE S256, public clients, CIMD and DCR, {@code iss} in responses;</li>
 * <li>{@code POST /oauth/register}: Dynamic Client Registration;</li>
 * <li>{@code GET /oauth/authorize}: the page with the four-digit code; it polls {@code /oauth/status} and goes on to
 * the client's callback once the owner typed the code;</li>
 * <li>{@code POST /oauth/token}: codes and refresh tokens, form-encoded, answered at once.</li>
 * </ul>
 * The issuer is {@code bridge.public_url} when set, else the request's own address, accepted only for loopback
 * names or the bridge's configured host (a page reached through another name is refused).
 */
public final class SignInEndpoints implements HttpHandler {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BODY = 64 * 1024;

    private final SignIn signIn;
    private final String scheme;
    private final java.util.Set<String> allowedOrigins;
    /** Client metadata fetches in flight: at most two bridge threads ever wait on a remote server. */
    private final java.util.concurrent.Semaphore fetches = new java.util.concurrent.Semaphore(2);
    private final String publicUrl;
    private final String bridgeHost;
    private final int port;
    private final Consumer<SignIn.Pending> notifyOwners;

    public SignInEndpoints(SignIn signIn, String publicUrl, String bridgeHost, int port, Consumer<SignIn.Pending> notifyOwners) {
        this(signIn, publicUrl, bridgeHost, port, java.util.Set.of(), notifyOwners);
    }

    /** {@code allowedOrigins}: browser origins that may register clients and redeem codes (bridge.allowed_origins). */
    public SignInEndpoints(SignIn signIn, String publicUrl, String bridgeHost, int port, java.util.Set<String> allowedOrigins,
                           Consumer<SignIn.Pending> notifyOwners) {
        this(signIn, publicUrl, bridgeHost, port, false, allowedOrigins, notifyOwners);
    }

    /** {@code https}: the bridge itself speaks HTTPS, so its own addresses are https. */
    public SignInEndpoints(SignIn signIn, String publicUrl, String bridgeHost, int port, boolean https, java.util.Set<String> allowedOrigins,
                           Consumer<SignIn.Pending> notifyOwners) {
        this.scheme = https ? "https://" : "http://";
        this.allowedOrigins = java.util.Set.copyOf(allowedOrigins);
        this.signIn = signIn;
        this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : SignIn.stripSlash(publicUrl.strip());
        this.bridgeHost = bridgeHost;
        this.port = port;
        this.notifyOwners = notifyOwners;
    }

    /** {@code WWW-Authenticate} for a 401 from {@code /mcp}: where the client finds how to sign in. */
    public String challenge(HttpExchange exchange) {
        String base = base(exchange);
        if (base == null) return null;
        return "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\", scope=\"almighty\"";
    }

    @Override
    public void handle(HttpExchange exchange) {
        // Not try-with-resources: that would close the exchange before an error reply is written in a catch.
        try {
            String path = exchange.getRequestURI().getPath();
            String verb = exchange.getRequestMethod();
            String base = base(exchange);
            if (base == null) {
                json(exchange, 400, error("invalid_request", "Unknown host: use the server's address (127.0.0.1) or bridge.public_url"));
                return;
            }
            if (verb.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            switch (path) {
                case "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp" -> json(exchange, 200, resource(base));
                case "/.well-known/oauth-authorization-server", "/.well-known/openid-configuration",
                     "/.well-known/oauth-authorization-server/mcp", "/.well-known/openid-configuration/mcp" -> json(exchange, 200, metadata(base));
                case "/oauth/register" -> {
                    if (!verb.equals("POST")) throw new SignIn.OAuthError(405, "invalid_request", "POST only");
                    crossSite(exchange);
                    String type = exchange.getRequestHeaders().getFirst("Content-Type");
                    // A web page can only send text/plain or forms without asking first: registrations are JSON.
                    if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                        throw new SignIn.OAuthError(415, "invalid_client_metadata", "Content-Type must be application/json");
                    }
                    JsonElement body = parse(read(exchange));
                    if (!body.isJsonObject()) throw new SignIn.OAuthError(400, "invalid_client_metadata", "Expected a JSON object");
                    json(exchange, 201, signIn.register(body.getAsJsonObject()));
                }
                case "/oauth/authorize" -> authorize(exchange, base);
                case "/oauth/status" -> status(exchange, base);
                case "/oauth/token" -> {
                    if (!verb.equals("POST")) throw new SignIn.OAuthError(405, "invalid_request", "POST only");
                    crossSite(exchange);
                    Map<String, String> form = McpEndpoint.query(new String(read(exchange), StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    json(exchange, 200, signIn.exchange(form, exchange.getRemoteAddress().getAddress().getHostAddress()));
                }
                default -> json(exchange, 404, error("not_found", "No " + path));
            }
        } catch (SignIn.OAuthError e) {
            try {
                json(exchange, e.status(), error(e.error(), e.getMessage()));
            } catch (IOException ignored) {
                // The client disconnected.
            }
        } catch (IOException e) {
            // The client disconnected.
        } catch (RuntimeException e) {
            LOG.warn("Almighty sign-in: request failed", e);
            try {
                json(exchange, 500, error("server_error", e.getClass().getSimpleName()));
            } catch (IOException ignored) {
                // The client disconnected.
            }
        } finally {
            exchange.close();
        }
    }

    /** The base URL for this request, or null if the request came through a name this server does not answer to. */
    String base(HttpExchange exchange) {
        if (publicUrl != null) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            // Behind the owner's reverse proxy or tunnel the public URL is the issuer; local clients may still use loopback.
            if (host == null || !loopbackHost(host)) return publicUrl;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return scheme + "127.0.0.1:" + port;
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.contains(":") ? host.substring(0, host.lastIndexOf(':')) : host;
        if (!SignIn.loopback(name) && !name.equalsIgnoreCase(bridgeHost)) return null;
        return scheme + host;
    }

    private static boolean loopbackHost(String host) {
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.contains(":") ? host.substring(0, host.lastIndexOf(':')) : host;
        return SignIn.loopback(name);
    }

    static JsonObject resource(String base) {
        JsonObject r = new JsonObject();
        r.addProperty("resource", base + "/mcp");
        r.add("authorization_servers", array(base));
        r.add("bearer_methods_supported", array("header"));
        r.add("scopes_supported", array("almighty"));
        r.addProperty("resource_name", "Almighty (Minecraft server)");
        return r;
    }

    static JsonObject metadata(String base) {
        JsonObject m = new JsonObject();
        m.addProperty("issuer", base);
        m.addProperty("authorization_endpoint", base + "/oauth/authorize");
        m.addProperty("token_endpoint", base + "/oauth/token");
        m.addProperty("registration_endpoint", base + "/oauth/register");
        m.add("response_types_supported", array("code"));
        m.add("response_modes_supported", array("query"));
        m.add("grant_types_supported", array("authorization_code", "refresh_token"));
        m.add("code_challenge_methods_supported", array("S256"));
        m.add("token_endpoint_auth_methods_supported", array("none"));
        m.add("scopes_supported", array("almighty"));
        m.addProperty("client_id_metadata_document_supported", true);
        m.addProperty("authorization_response_iss_parameter_supported", true);
        return m;
    }

    /** A request from a web page of another origin (native clients send no Origin). */
    private void crossSite(HttpExchange exchange) throws SignIn.OAuthError {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new SignIn.OAuthError(403, "access_denied", "Origin " + origin + " is not allowed (bridge.allowed_origins)");
        }
    }

    private void authorize(HttpExchange exchange, String base) throws IOException, SignIn.OAuthError {
        // The sign-in page is opened as a page; loaded as an image, script or frame by another site, it is refused, so
        // a web page cannot start sign-ins (and fill the waiting list) behind the owner's back.
        String dest = exchange.getRequestHeaders().getFirst("Sec-Fetch-Dest");
        if (dest != null && !dest.equals("document")) throw new SignIn.OAuthError(403, "access_denied", "Open the sign-in page as a page");
        Map<String, String> q = McpEndpoint.query(exchange.getRequestURI().getRawQuery());
        String clientId = q.get("client_id");
        if (clientId == null) throw new SignIn.OAuthError(400, "invalid_request", "client_id is required");
        SignIn.Client client = signIn.client(clientId);
        if (client == null) {
            if (!ClientMetadata.isUrl(clientId)) throw new SignIn.OAuthError(400, "invalid_client", "Unknown client; register first");
            if (!fetches.tryAcquire()) throw new SignIn.OAuthError(429, "temporarily_unavailable", "Busy reading other apps' details; try again");
            try {
                client = signIn.metadataClient(clientId, ClientMetadata.fetch(clientId));
            } finally {
                fetches.release();
            }
        }
        SignIn.Pending p;
        try {
            p = signIn.authorize(client, q, base + "/mcp", exchange.getRemoteAddress().getAddress().getHostAddress());
        } catch (SignIn.OAuthError e) {
            html(exchange, e.status(), page("Sign-in refused", "<p>" + esc(e.getMessage()) + "</p>", null));
            return;
        }
        notifyOwners.accept(p);
        String host = URI.create(p.redirect()).getHost();
        String body = "<p class=\"who\"><b>" + esc(client.name()) + "</b> wants to connect to this Minecraft server"
                + (host == null ? "" : " and will return to <b>" + esc(host) + "</b>") + ".</p>"
                + "<div class=\"code notranslate\" translate=\"no\">" + p.code() + "</div>"
                + "<p>To allow it, type in the game (as an operator) or in the server console:</p>"
                + "<pre class=\"notranslate\" translate=\"no\">/almighty approve " + p.code() + "</pre>"
                + "<p class=\"small\">Only the person running the server can do this. To refuse: <code class=\"notranslate\" translate=\"no\">/almighty deny " + p.code()
                + "</code>. The code expires in 10 minutes.</p><p id=\"state\" class=\"small\">Waiting for approval…</p>";
        html(exchange, 200, page("Connect to Almighty", body, p.id()));
    }

    private void status(HttpExchange exchange, String base) throws IOException {
        Map<String, String> q = McpEndpoint.query(exchange.getRequestURI().getRawQuery());
        SignIn.Pending p = signIn.pending(q.getOrDefault("id", ""));
        JsonObject out = new JsonObject();
        if (p == null) {
            out.addProperty("state", "expired");
        } else {
            String next = p.outcome(base);
            out.addProperty("state", next == null ? "waiting" : next.contains("error=access_denied") ? "denied" : "approved");
            if (next != null) out.addProperty("redirect", next);
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        json(exchange, 200, out);
    }

    private static String page(String title, String body, String pendingId) {
        String script = pendingId == null ? "" : "<script>const id=" + GSON.toJson(pendingId) + ";"
                + "async function poll(){try{const r=await fetch('/oauth/status?id='+encodeURIComponent(id),{cache:'no-store'});"
                + "const s=await r.json();if(s.redirect){document.getElementById('state').textContent=s.state==='denied'?'Refused.':'Approved — returning to your app…';"
                + "location.replace(s.redirect);return;}if(s.state==='expired'){document.getElementById('state').textContent='This sign-in expired. Start again from your app.';return;}}"
                + "catch(e){}setTimeout(poll,1500);}poll();</script>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>"
                + ":root{color-scheme:light dark;--bg:#f6f5f1;--fg:#1d1c1a;--muted:#6b6862;--card:#fff;--accent:#2f6f4f}"
                + "@media (prefers-color-scheme:dark){:root{--bg:#161614;--fg:#ecebe6;--muted:#a3a097;--card:#22211e;--accent:#7cc79f}}"
                + "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;display:flex;justify-content:center;padding:40px 16px}"
                + "main{background:var(--card);max-width:460px;width:100%;padding:28px;border-radius:14px;box-shadow:0 1px 3px rgba(0,0,0,.12)}"
                + "h1{font-size:20px;margin:0 0 12px}.code{font:700 56px/1 ui-monospace,monospace;letter-spacing:.18em;text-align:center;margin:24px 0;color:var(--accent)}"
                + "pre{background:var(--bg);padding:10px 12px;border-radius:8px;font-size:17px;overflow-x:auto}.small{color:var(--muted);font-size:14px}"
                + "</style></head><body><main><h1>" + esc(title) + "</h1>" + body + "</main>" + script + "</body></html>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static byte[] read(HttpExchange exchange) throws IOException, SignIn.OAuthError {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new SignIn.OAuthError(413, "invalid_request", "Request too large");
        return body;
    }

    private static JsonElement parse(byte[] body) throws SignIn.OAuthError {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new SignIn.OAuthError(400, "invalid_client_metadata", "Not JSON");
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("error", code);
        e.addProperty("error_description", message);
        return e;
    }

    private static JsonArray array(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    private static void json(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void html(HttpExchange exchange, int status, String page) throws IOException {
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
