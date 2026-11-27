package ua.zentix.almighty.mcp.signin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client ID Metadata Documents: a client whose id is an HTTPS URL (Claude Code
 * {@code https://claude.ai/oauth/claude-code-client-metadata}, ChatGPT, Codex) is described by the JSON document at
 * that URL. Fetching it is the mod's one outbound request, so it is narrow: HTTPS only, no private, loopback or
 * link-local addresses (no reaching into the owner's network), at most 16 KB, 5 s, no redirects.
 */
public final class ClientMetadata {
    static final int MAX_BYTES = 16 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private ClientMetadata() {}

    /** Whether a client id is a metadata document URL (and not a registered id). */
    public static boolean isUrl(String clientId) {
        return clientId.startsWith("https://");
    }

    public static JsonObject fetch(String url) throws SignIn.OAuthError {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SignIn.OAuthError(400, "invalid_client", "client_id is not a URL");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                || uri.getRawPath().equals("/") || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new SignIn.OAuthError(400, "invalid_client", "A client id URL is https with a path and no fragment");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || uniqueLocal(a)) {
                    throw new SignIn.OAuthError(400, "invalid_client", "The client id URL points to a private address");
                }
            }
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document answered " + resp.statusCode());
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document is over 16 KB");
                JsonElement doc = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                if (!doc.isJsonObject()) throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document is not a JSON object");
                return doc.getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            throw new SignIn.OAuthError(400, "invalid_client", "Could not read the client metadata document: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignIn.OAuthError(400, "invalid_client", "Interrupted");
        }
    }

    private static boolean uniqueLocal(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }
}
