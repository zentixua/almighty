package ua.zentix.almighty.mcp.signin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Keyless sign-in: the mod as a minimal OAuth 2.1 authorization server (the MCP authorization profile: authorization
 * code with PKCE S256, public clients, refresh rotation, RFC 9207 {@code iss}), whose consent step is the owner
 * typing, in the game or the server console, the four-digit code that the login page shows in the browser. Typing the
 * code, not clicking a prompt, proves the approver sees the same request as the browser: a stranger's login attempt
 * cannot be approved by mistake, and the in-game notice never shows the code.
 * <p>
 * An approved client becomes an ordinary agent ({@code ua.zentix.almighty.bridge.Agents}): its access token is that
 * agent's bearer token, so {@code /almighty agents} lists it, and audit and policy see its name. A refresh rotates the
 * agent's token. Ownership is proved, not remembered by name: the sign-in keeps the hash of the token it last issued
 * to each of its agents, and touches an agent only while the agent's current token is still that one. So
 * {@code /almighty revoke} or {@code agent remove} ends a signed-in client for good (its refresh token dies with the
 * old token), and an agent the owner made by hand, even under a name the sign-in once used, is never rotated.
 * <p>
 * Clients register by Dynamic Client Registration or identify with a Client ID Metadata Document (an HTTPS URL as the
 * client id, fetched once per login with {@link ClientMetadata}). State: clients and refresh tokens (hashed) in
 * {@code config/almighty/oauth/}; pending logins and codes in memory (they last minutes).
 */
public final class SignIn {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final long PENDING_MS = 10 * 60_000L;
    public static final long CODE_MS = 5 * 60_000L;
    /** What the token endpoint tells clients; the token itself lasts until rotated (refresh or revoke). */
    public static final long EXPIRES_SECONDS = 30L * 24 * 3600;
    static final int MAX_PENDING = 5;
    static final int MAX_CLIENTS = 200;
    static final int MAX_METADATA_CLIENTS = 50;
    /** Waiting sign-ins per client: one app cannot hold every slot. */
    static final int MAX_PENDING_PER_CLIENT = 2;
    private static final Pattern AGENT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern NAME_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> BAD_SCHEMES = Set.of("javascript", "data", "file", "about", "blob", "vbscript");

    /** Issues agents for approved logins: create or rotate, and read the token. */
    public interface Issuer {
        /** A fresh token for agent {@code name}, creating the agent if absent. */
        String issue(String name) throws IOException;

        /** Whether an agent with this name exists (made by anyone). */
        boolean exists(String name);

        /** The agent's current token, or null if there is no such agent. */
        String current(String name) throws IOException;
    }

    /** A registered client: DCR id or CIMD URL, display name, allowed redirects. */
    public record Client(String id, String name, List<String> redirects, boolean metadataDocument) {}

    /** A login waiting for the owner: shown in the browser with its code, approved by that code. */
    public static final class Pending {
        final String id;
        final String code;
        final Client client;
        final String redirect;
        final String state;
        final String challenge;
        final String resource;
        final String address;
        final long created;
        volatile String agent;
        volatile String authCode;
        volatile boolean denied;

        Pending(String id, String code, Client client, String redirect, String state, String challenge, String resource, String address, long created) {
            this.id = id;
            this.code = code;
            this.client = client;
            this.redirect = redirect;
            this.state = state;
            this.challenge = challenge;
            this.resource = resource;
            this.address = address;
            this.created = created;
        }

        public String id() {
            return id;
        }

        public String code() {
            return code;
        }

        public Client client() {
            return client;
        }

        public String redirect() {
            return redirect;
        }

        public String address() {
            return address;
        }

        /** Where the browser goes now: the client's callback with the code (or the denial), else null while waiting. */
        public String outcome(String issuer) {
            if (denied) return redirect + (redirect.contains("?") ? "&" : "?") + "error=access_denied" + param("state", state) + param("iss", issuer);
            if (authCode == null) return null;
            return redirect + (redirect.contains("?") ? "&" : "?") + "code=" + enc(authCode) + param("state", state) + param("iss", issuer);
        }
    }

    /** OAuth errors: {@code error} code for the client, a message for people. */
    public static final class OAuthError extends Exception {
        final String error;
        final int status;

        OAuthError(int status, String error, String message) {
            super(message);
            this.status = status;
            this.error = error;
        }

        public String error() {
            return error;
        }

        public int status() {
            return status;
        }
    }

    private record Code(Pending pending, long expires) {}

    /** A refresh token's grant: valid only while the agent still holds the access token issued with it. */
    private record Refresh(String agent, String client, long issued, String tokenHash) {}

    private final Path dir;
    private final Issuer issuer;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Code> codes = new LinkedHashMap<>();
    private final Map<String, Refresh> refresh = new LinkedHashMap<>();
    /** Agents this sign-in made: name to the hash of the token it last issued them. */
    private final Map<String, String> agents = new LinkedHashMap<>();

    public SignIn(Path dir, Issuer issuer, LongSupplier clock) {
        this.dir = dir;
        this.issuer = issuer;
        this.clock = clock;
        load();
    }

    // ---- clients ----

    /** Dynamic Client Registration (RFC 7591): public clients only, redirects checked. */
    public synchronized JsonObject register(JsonObject request) throws OAuthError {
        if (clients.values().stream().filter(c -> !c.metadataDocument()).count() >= MAX_CLIENTS) {
            // Evict the oldest registration nobody signed in with, so junk registrations cannot lock real clients out.
            Set<String> used = new java.util.HashSet<>();
            refresh.values().forEach(r -> used.add(r.client));
            pending.values().forEach(p -> used.add(p.client.id()));
            String oldest = clients.values().stream().filter(c -> !c.metadataDocument() && !used.contains(c.id())).map(Client::id).findFirst().orElse(null);
            if (oldest == null) throw new OAuthError(400, "invalid_client_metadata", "Too many signed-in clients; remove some agents first");
            clients.remove(oldest);
        }
        List<String> redirects = strings(request.get("redirect_uris"));
        if (redirects.isEmpty()) throw new OAuthError(400, "invalid_redirect_uri", "redirect_uris is required");
        for (String r : redirects) checkRedirect(r);
        String name = displayName(string(request, "client_name"), "an MCP client");
        String method = string(request, "token_endpoint_auth_method");
        if (method == null) method = "none";
        if (!method.equals("none")) {
            // Public clients on the owner's machine: there is no secret to keep. Clients that ask for a secret still
            // get a public registration, which RFC 7591 allows the server to choose.
            LOG.debug("Almighty sign-in: {} asked for {}; registered as a public client", name, method);
        }
        Client client = new Client("almighty-" + randomHex(16), name, redirects, false);
        clients.put(client.id(), client);
        save();
        JsonObject out = new JsonObject();
        out.addProperty("client_id", client.id());
        out.addProperty("client_id_issued_at", clock.getAsLong() / 1000);
        out.addProperty("client_name", name);
        JsonArray uris = new JsonArray();
        redirects.forEach(uris::add);
        out.add("redirect_uris", uris);
        out.addProperty("token_endpoint_auth_method", "none");
        JsonArray grants = new JsonArray();
        grants.add("authorization_code");
        grants.add("refresh_token");
        out.add("grant_types", grants);
        JsonArray types = new JsonArray();
        types.add("code");
        out.add("response_types", types);
        return out;
    }

    /** A Client ID Metadata Document client, as fetched; remembered for this server run only. */
    public synchronized Client metadataClient(String url, JsonObject doc) throws OAuthError {
        if (!url.equals(string(doc, "client_id"))) {
            throw new OAuthError(400, "invalid_client", "The client metadata document's client_id is not its own URL");
        }
        List<String> redirects = strings(doc.get("redirect_uris"));
        if (redirects.isEmpty()) throw new OAuthError(400, "invalid_client", "The client metadata document lists no redirect_uris");
        for (String r : redirects) checkRedirect(r);
        String name = displayName(string(doc, "client_name"), URI.create(url).getHost());
        Client c = new Client(url, name, redirects, true);
        // Remembered for this run only, and bounded: unique URLs cannot grow memory.
        if (clients.values().stream().filter(Client::metadataDocument).count() >= MAX_METADATA_CLIENTS) {
            clients.values().stream().filter(Client::metadataDocument).map(Client::id).findFirst().ifPresent(clients::remove);
        }
        clients.put(url, c);
        return c;
    }

    /** A display name: printable, one line, at most 80 characters (it reaches logs and chat). */
    static String displayName(String raw, String fallback) {
        String n = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (n.isEmpty()) n = fallback == null ? "an MCP client" : fallback;
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    public synchronized Client client(String id) {
        return clients.get(id);
    }

    /** Loopback HTTP (any port, RFC 8252), HTTPS, or a private-use scheme; never other plain HTTP or script schemes. */
    static void checkRedirect(String redirect) throws OAuthError {
        URI uri;
        try {
            uri = new URI(redirect);
        } catch (URISyntaxException e) {
            throw new OAuthError(400, "invalid_redirect_uri", "Not a URI: " + redirect);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getFragment() != null) throw new OAuthError(400, "invalid_redirect_uri", "A redirect URI has no fragment: " + redirect);
        if (scheme.equals("http")) {
            if (!loopback(uri.getHost())) throw new OAuthError(400, "invalid_redirect_uri", "Plain http redirects only to loopback: " + redirect);
            return;
        }
        if (scheme.equals("https")) {
            if (uri.getHost() == null) throw new OAuthError(400, "invalid_redirect_uri", "No host: " + redirect);
            return;
        }
        if (scheme.isEmpty() || BAD_SCHEMES.contains(scheme)) throw new OAuthError(400, "invalid_redirect_uri", "Not allowed: " + redirect);
    }

    /** {@code localhost} or a loopback IP literal; a name such as {@code 127.evil.example} is not. */
    public static boolean loopback(String host) {
        if (host == null) return false;
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) return true;
        if (h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        return h.matches("127\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
                && java.util.Arrays.stream(h.split("\\.")).allMatch(x -> Integer.parseInt(x) <= 255);
    }

    /** Exact match, except that a loopback redirect may use any port (RFC 8252 section 7.3). */
    static boolean redirectMatches(String registered, String given) {
        if (registered.equals(given)) return true;
        try {
            URI r = new URI(registered), g = new URI(given);
            return "http".equalsIgnoreCase(r.getScheme()) && "http".equalsIgnoreCase(g.getScheme()) && loopback(r.getHost())
                    && r.getHost().equalsIgnoreCase(g.getHost()) && java.util.Objects.equals(r.getRawPath(), g.getRawPath())
                    && java.util.Objects.equals(r.getRawQuery(), g.getRawQuery());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ---- authorization ----

    /** A new login waiting for the owner, with a four-digit code unique among waiting logins. */
    public synchronized Pending authorize(Client client, Map<String, String> q, String resource, String address) throws OAuthError {
        expire();
        if (!"code".equals(q.get("response_type"))) throw new OAuthError(400, "unsupported_response_type", "response_type must be code");
        String redirect = q.get("redirect_uri");
        if (redirect == null) {
            if (client.redirects().size() != 1) throw new OAuthError(400, "invalid_request", "redirect_uri is required");
            redirect = client.redirects().get(0);
        }
        String r = redirect;
        if (client.redirects().stream().noneMatch(reg -> redirectMatches(reg, r))) {
            throw new OAuthError(400, "invalid_request", "redirect_uri is not registered for this client");
        }
        String challenge = q.get("code_challenge");
        if (challenge == null || !"S256".equals(q.get("code_challenge_method"))) {
            throw new OAuthError(400, "invalid_request", "PKCE with code_challenge_method S256 is required");
        }
        if (q.containsKey("resource") && !resource.equals(stripSlash(q.get("resource")))) {
            throw new OAuthError(400, "invalid_target", "resource must be " + resource);
        }
        if (pending.values().stream().filter(x -> x.client.id().equals(client.id())).count() >= MAX_PENDING_PER_CLIENT) {
            throw new OAuthError(429, "temporarily_unavailable", "This app already has sign-ins waiting; finish or wait for them");
        }
        if (pending.size() >= MAX_PENDING) {
            throw new OAuthError(429, "temporarily_unavailable", "Too many logins are waiting for the owner; try again in a few minutes");
        }
        String code;
        do {
            code = String.format(Locale.ROOT, "%04d", random.nextInt(10_000));
        } while (codeTaken(code));
        Pending p = new Pending(randomHex(16), code, client, redirect, q.get("state"), challenge, resource, address, clock.getAsLong());
        pending.put(p.id, p);
        return p;
    }

    private boolean codeTaken(String code) {
        return pending.values().stream().anyMatch(p -> p.code.equals(code));
    }

    public synchronized Pending pending(String id) {
        expire();
        return pending.get(id);
    }

    /** Waiting logins (for {@code /almighty approve} without a code, which only lists them). */
    public synchronized List<Pending> waiting() {
        expire();
        return pending.values().stream().filter(p -> p.agent == null && !p.denied).toList();
    }

    /**
     * The owner approves the login showing {@code code}: the client becomes agent {@code name} (default: from the
     * client's name), with a fresh token; returns the agent name.
     */
    public synchronized String approve(String code, String name) throws OAuthError, IOException {
        expire();
        Pending p = pending.values().stream().filter(x -> x.code.equals(code) && x.agent == null && !x.denied).findFirst()
                .orElseThrow(() -> new OAuthError(404, "not_found", "No login is waiting with code " + code + " (codes last 10 minutes)"));
        String agent = name != null ? name : agentName(p.client.name());
        if (agent.equals("gm")) throw new OAuthError(400, "invalid_request", "gm is the default agent; choose another name");
        if (!AGENT_NAME.matcher(agent).matches()) {
            throw new OAuthError(400, "invalid_request", "Agent names are 1-32 characters: lowercase letters, digits, - and _");
        }
        if (!free(agent)) {
            if (name != null) throw new OAuthError(400, "invalid_request", "Agent " + agent + " exists and was not made by sign-in; choose another name");
            int n = 2;
            while (!free(agent + "-" + n)) n++;
            agent = agent + "-" + n;
        }
        p.agent = agent;
        p.authCode = randomHex(32);
        codes.put(p.authCode, new Code(p, clock.getAsLong() + CODE_MS));
        return agent;
    }

    /** An agent name the sign-in may (re)issue: absent, or still holding the token the sign-in last gave it. */
    private boolean free(String name) throws IOException {
        String current = issuer.current(name);
        if (current == null) {
            agents.remove(name);
            return true;
        }
        return hash(current).equals(agents.get(name));
    }

    public synchronized boolean deny(String code) {
        expire();
        for (Pending p : pending.values()) {
            if (p.code.equals(code) && p.agent == null && !p.denied) {
                p.denied = true;
                return true;
            }
        }
        return false;
    }

    /** Agent name from a client name: lowercase letters and digits joined by {@code -}, at most 32 characters. */
    static String agentName(String clientName) {
        String n = NAME_CHARS.matcher(clientName.toLowerCase(Locale.ROOT)).replaceAll("-").replaceAll("^-+|-+$", "");
        if (n.isEmpty()) n = "agent";
        if (n.length() > 28) n = n.substring(0, 28).replaceAll("-+$", "");
        return n;
    }

    // ---- token endpoint ----

    /** {@code grant_type=authorization_code}: the code once, with the PKCE verifier and the same redirect. */
    public synchronized JsonObject exchange(Map<String, String> form) throws OAuthError, IOException {
        expire();
        String grant = form.get("grant_type");
        if ("refresh_token".equals(grant)) return refresh(form);
        if (!"authorization_code".equals(grant)) throw new OAuthError(400, "unsupported_grant_type", "grant_type is authorization_code or refresh_token");
        Code c = codes.remove(form.getOrDefault("code", ""));
        if (c == null || c.expires < clock.getAsLong()) throw new OAuthError(400, "invalid_grant", "The code is unknown, used or expired");
        Pending p = c.pending;
        pending.remove(p.id);
        if (!p.client.id().equals(form.get("client_id"))) throw new OAuthError(400, "invalid_grant", "The code was issued to another client");
        if (form.containsKey("redirect_uri") && !form.get("redirect_uri").equals(p.redirect)) {
            throw new OAuthError(400, "invalid_grant", "redirect_uri differs from the authorization request");
        }
        String verifier = form.get("code_verifier");
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}") || !s256(verifier).equals(p.challenge)) throw new OAuthError(400, "invalid_grant", "PKCE verification failed");
        if (form.containsKey("resource") && !p.resource.equals(stripSlash(form.get("resource")))) {
            throw new OAuthError(400, "invalid_target", "resource must be " + p.resource);
        }
        // Re-checked here: the owner may have made an agent with this name since the approval.
        if (!free(p.agent)) throw new OAuthError(400, "invalid_grant", "Agent " + p.agent + " was created by hand meanwhile; sign in again");
        String token = issuer.issue(p.agent);
        agents.put(p.agent, hash(token));
        // A new login of this agent ends the earlier one: its refresh tokens die with its old access token.
        refresh.values().removeIf(r -> r.agent.equals(p.agent));
        LOG.info("Almighty sign-in: {} connected as agent {} from {}", p.client.name(), p.agent, p.address);
        return tokens(p.agent, p.client.id(), token);
    }

    /** {@code grant_type=refresh_token}: rotates both tokens; a used refresh token is dead ({@code invalid_grant}). */
    private JsonObject refresh(Map<String, String> form) throws OAuthError, IOException {
        String given = form.get("refresh_token");
        Refresh r = given == null ? null : refresh.remove(hash(given));
        if (r == null) throw new OAuthError(400, "invalid_grant", "The refresh token is unknown or already used; sign in again");
        if (form.containsKey("client_id") && !form.get("client_id").equals(r.client)) {
            throw new OAuthError(400, "invalid_grant", "The refresh token was issued to another client");
        }
        String current = issuer.current(r.agent);
        if (current == null || !hash(current).equals(r.tokenHash) || !hash(current).equals(agents.get(r.agent))) {
            // Revoked, removed or re-made by hand since: the grant ended with the token it came with.
            refresh.values().removeIf(x -> x.agent.equals(r.agent));
            save();
            throw new OAuthError(400, "invalid_grant", "Agent " + r.agent + " was revoked or removed; sign in again");
        }
        String token = issuer.issue(r.agent);
        agents.put(r.agent, hash(token));
        return tokens(r.agent, r.client, token);
    }

    private JsonObject tokens(String agent, String client, String access) {
        String refreshToken = randomHex(32);
        refresh.put(hash(refreshToken), new Refresh(agent, client, clock.getAsLong(), hash(access)));
        save();
        JsonObject out = new JsonObject();
        out.addProperty("access_token", access);
        out.addProperty("token_type", "Bearer");
        out.addProperty("expires_in", EXPIRES_SECONDS);
        out.addProperty("refresh_token", refreshToken);
        out.addProperty("scope", "almighty");
        return out;
    }

    private void expire() {
        long now = clock.getAsLong();
        pending.values().removeIf(p -> now - p.created > PENDING_MS);
        codes.values().removeIf(c -> c.expires < now);
    }

    // ---- persistence ----

    private void load() {
        if (dir == null) return;
        try {
            Path c = dir.resolve("clients.json");
            if (Files.isRegularFile(c)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(c)).getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    clients.put(o.get("id").getAsString(), new Client(o.get("id").getAsString(), o.get("name").getAsString(),
                            strings(o.get("redirect_uris")), false));
                }
            }
            Path r = dir.resolve("refresh.json");
            if (Files.isRegularFile(r)) {
                JsonObject o = JsonParser.parseString(Files.readString(r)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("agents").entrySet()) agents.put(e.getKey(), e.getValue().getAsString());
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tokens").entrySet()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    refresh.put(e.getKey(), new Refresh(t.get("agent").getAsString(), t.get("client").getAsString(), t.get("issued").getAsLong(),
                            t.get("token").getAsString()));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Almighty sign-in: could not read {} ({}); signed-in clients will have to sign in again", dir, e.toString());
        }
    }

    private void save() {
        if (dir == null) return;
        try {
            JsonArray cs = new JsonArray();
            for (Client c : clients.values()) {
                if (c.metadataDocument()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", c.id());
                o.addProperty("name", c.name());
                JsonArray uris = new JsonArray();
                c.redirects().forEach(uris::add);
                o.add("redirect_uris", uris);
                cs.add(o);
            }
            JsonObject rs = new JsonObject();
            JsonObject as = new JsonObject();
            agents.forEach(as::addProperty);
            rs.add("agents", as);
            JsonObject ts = new JsonObject();
            refresh.forEach((h, r) -> {
                JsonObject t = new JsonObject();
                t.addProperty("agent", r.agent);
                t.addProperty("client", r.client);
                t.addProperty("issued", r.issued);
                t.addProperty("token", r.tokenHash);
                ts.add(h, t);
            });
            rs.add("tokens", ts);
            write(dir.resolve("clients.json"), GSON.toJson(cs));
            write(dir.resolve("refresh.json"), GSON.toJson(rs));
        } catch (IOException e) {
            LOG.warn("Almighty sign-in: could not save {}: {}", dir, e.toString());
        }
    }

    /** Owner-only, atomic: refresh hashes are not secrets, but the file says who may sign in. */
    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows.
        }
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- helpers ----

    static String s256(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    static String hash(String token) {
        return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    private static String param(String k, String v) {
        return v == null ? "" : "&" + k + "=" + enc(v);
    }

    static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
