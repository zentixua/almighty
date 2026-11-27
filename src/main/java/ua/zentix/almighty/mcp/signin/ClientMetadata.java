package ua.zentix.almighty.mcp.signin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client ID Metadata Documents: a client whose id is an HTTPS URL (Claude Code
 * {@code https://claude.ai/oauth/claude-code-client-metadata}, ChatGPT, Codex) is described by the JSON document at
 * that URL. Fetching it is the mod's one outbound request, so it is narrow: HTTPS only, no private, loopback or
 * link-local addresses (no reaching into the owner's network), at most 16 KB, one 6 s deadline for the whole answer,
 * no redirects. The name is checked before the request; the HTTP client resolves it again, so a DNS answer that
 * changes in between could still reach a private address, but only over HTTPS with a valid certificate for that name.
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
                        || a.isMulticastAddress() || uniqueLocal(a) || carrierNat(a)) {
                    throw new SignIn.OAuthError(400, "invalid_client", "The client id URL points to a private address");
                }
            }
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            CompletableFuture<HttpResponse<byte[]>> f = HTTP.sendAsync(req, info -> new Limited());
            HttpResponse<byte[]> resp;
            try {
                // One deadline for connect, headers and body: a server that drips its answer cannot hold a bridge thread.
                resp = f.get(6, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document did not arrive within 6 s");
            } catch (ExecutionException e) {
                throw new SignIn.OAuthError(400, "invalid_client", "Could not read the client metadata document: " + e.getCause().getMessage());
            }
            if (resp.statusCode() != 200) throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document answered " + resp.statusCode());
            JsonElement doc = JsonParser.parseString(new String(resp.body(), StandardCharsets.UTF_8));
            if (!doc.isJsonObject()) throw new SignIn.OAuthError(400, "invalid_client", "The client metadata document is not a JSON object");
            return doc.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new SignIn.OAuthError(400, "invalid_client", "Could not read the client metadata document");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignIn.OAuthError(400, "invalid_client", "Interrupted");
        }
    }

    /** Collects at most {@link #MAX_BYTES}; more cancels the download. */
    static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer b : items) {
                if (out.size() + b.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("over 16 KB"));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            body.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            body.complete(out.toByteArray());
        }
    }

    private static boolean carrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    private static boolean uniqueLocal(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }
}
