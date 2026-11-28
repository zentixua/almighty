package ua.zentix.almighty.bridge;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTPS from the owner's PEM files: the bridge answers over TLS with that certificate, plain HTTP gets no answer, and
 * keys in formats the JDK cannot read say how to convert them, and a key that is not the certificate's is refused at start. The fixture is a throwaway self-signed certificate for
 * localhost and 127.0.0.1 (src/test/resources/tls), not a secret.
 */
class TlsTest {
    @TempDir
    Path tmp;

    private Path copy(String name) throws Exception {
        Path out = tmp.resolve(name);
        try (InputStream in = TlsTest.class.getResourceAsStream("/tls/" + name)) {
            Files.copy(in, out);
        }
        return out;
    }

    @Test
    void bridgeSpeaksHttpsWithTheOwnersCertificate() throws Exception {
        Path cert = copy("cert.pem"), key = copy("key.pem");
        SSLContext tls = Tls.context(cert, key);
        Bridge bridge = new Bridge(new InetSocketAddress("127.0.0.1", 0), Agents.of(Map.of("gm", "secret-tls")),
                new Dispatch(Map.of("status", a -> CompletableFuture.completedFuture(new JsonPrimitive("ok")))), Set.of(), Set.of(), tls);
        bridge.start();
        try {
            assertTrue(bridge.tls());
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream in = Files.newInputStream(cert)) {
                trust.setCertificateEntry("bridge", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, tmf.getTrustManagers(), null);
            HttpClient https = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).sslContext(client).build();
            int port = bridge.address().getPort();
            HttpResponse<String> r = https.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/rpc"))
                    .header("Authorization", "Bearer secret-tls").POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"status\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            HttpClient plain = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(java.time.Duration.ofSeconds(3)).build();
            assertThrows(java.io.IOException.class, () -> plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/rpc"))
                    .timeout(java.time.Duration.ofSeconds(3)).header("Authorization", "Bearer secret-tls")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"status\"}")).build(), HttpResponse.BodyHandlers.ofString()),
                    "no plain HTTP on a TLS bridge");
        } finally {
            bridge.stop();
        }
    }

    @Test
    void unreadableKeysSayHowToConvert() throws Exception {
        Path cert = copy("cert.pem");
        Path pkcs1 = tmp.resolve("ec.pem");
        Files.writeString(pkcs1, "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
        Exception e = assertThrows(java.security.GeneralSecurityException.class, () -> Tls.context(cert, pkcs1));
        assertTrue(e.getMessage().contains("openssl pkcs8"), e.getMessage());
        Exception mismatch = assertThrows(java.security.GeneralSecurityException.class, () -> Tls.context(cert, copy("other-key.pem")));
        assertTrue(mismatch.getMessage().contains("is not the key"), mismatch.getMessage());
        Path empty = tmp.resolve("none.pem");
        Files.writeString(empty, "nothing");
        assertThrows(java.security.GeneralSecurityException.class, () -> Tls.context(empty, copy("key.pem")));
    }

    /** Plan 7.2: a non-loopback bind without TLS is refused, unless the owner opts in by name. */
    @Test
    void plainHttpOffLoopbackIsRefused() {
        for (String host : new String[] {"127.0.0.1", "127.3.4.5", "::1", "[::1]", "localhost"}) {
            assertEquals(null, Tls.plainRefusal(host, false, false), host);
        }
        for (String host : new String[] {"0.0.0.0", "192.168.1.5", "10.0.0.2", "::", "example.org"}) {
            String refusal = Tls.plainRefusal(host, false, false);
            assertTrue(refusal != null && refusal.contains("bridge.allow_plain_http_off_loopback"), host);
            assertEquals(null, Tls.plainRefusal(host, true, false), host + " with TLS");
            assertEquals(null, Tls.plainRefusal(host, false, true), host + " opted in");
        }
    }
}
