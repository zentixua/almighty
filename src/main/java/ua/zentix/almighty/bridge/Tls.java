package ua.zentix.almighty.bridge;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTPS for the bridge from the owner's own certificate: a PEM certificate chain (the server certificate first, as
 * reverse proxies and Let's Encrypt write {@code fullchain.pem}) and a PEM PKCS#8 private key (RSA or EC,
 * {@code BEGIN PRIVATE KEY}, as Let's Encrypt writes {@code privkey.pem}). JDK only: no extra library.
 */
public final class Tls {
    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Tls() {}

    public static SSLContext context(Path certFile, Path keyFile) throws IOException, GeneralSecurityException {
        List<Certificate> chain = new ArrayList<>();
        CertificateFactory x509 = CertificateFactory.getInstance("X.509");
        for (byte[] der : blocks(Files.readString(certFile, StandardCharsets.US_ASCII), "CERTIFICATE")) {
            chain.add(x509.generateCertificate(new ByteArrayInputStream(der)));
        }
        if (chain.isEmpty()) throw new GeneralSecurityException(certFile + " holds no PEM certificate");
        String keyText = Files.readString(keyFile, StandardCharsets.US_ASCII);
        if (keyText.contains("BEGIN RSA PRIVATE KEY") || keyText.contains("BEGIN EC PRIVATE KEY") || keyText.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw new GeneralSecurityException(keyFile + ": convert the key to unencrypted PKCS#8 first: "
                    + "openssl pkcs8 -topk8 -nocrypt -in <key> -out <key>.pkcs8.pem");
        }
        List<byte[]> keys = blocks(keyText, "PRIVATE KEY");
        if (keys.size() != 1) throw new GeneralSecurityException(keyFile + " must hold one PEM private key (BEGIN PRIVATE KEY)");
        PrivateKey key = null;
        for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                key = KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(keys.get(0)));
                break;
            } catch (GeneralSecurityException ignored) {
                // Try the next algorithm.
            }
        }
        if (key == null) throw new GeneralSecurityException(keyFile + ": not an RSA, EC or Ed25519 key");
        matches(key, chain.get(0), keyFile, certFile);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] none = new char[0];
        store.setKeyEntry("bridge", key, none, chain.toArray(Certificate[]::new));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, none);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    /** The key signs what the certificate's public key verifies: a mismatch is a clear error at start, not at handshake. */
    private static void matches(PrivateKey key, Certificate cert, Path keyFile, Path certFile) throws GeneralSecurityException {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> "Ed25519";
        };
        byte[] probe = "almighty".getBytes(StandardCharsets.US_ASCII);
        java.security.Signature sign = java.security.Signature.getInstance(algorithm);
        sign.initSign(key);
        sign.update(probe);
        byte[] signature = sign.sign();
        java.security.Signature verify = java.security.Signature.getInstance(algorithm);
        try {
            verify.initVerify(cert.getPublicKey());
            verify.update(probe);
            if (verify.verify(signature)) return;
        } catch (GeneralSecurityException e) {
            // A key of another type than the certificate: a mismatch too.
        }
        throw new GeneralSecurityException(keyFile + " is not the key of the first certificate in " + certFile);
    }

    static List<byte[]> blocks(String pem, String type) {
        List<byte[]> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(pem);
        while (m.find()) {
            if (m.group(1).equals(type)) out.add(Base64.getMimeDecoder().decode(m.group(2)));
        }
        return out;
    }
}
