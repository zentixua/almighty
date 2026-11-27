package ua.zentix.almighty.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * One agent's bearer token, kept in a file readable only by its owner (32 random bytes in hex). Tokens are never
 * logged or printed; {@link Agents} keeps one per agent.
 */
public final class Token {
    private final byte[] value;

    private Token(String value) {
        this.value = value.getBytes(StandardCharsets.UTF_8);
    }

    public static Token of(String value) {
        return new Token(value);
    }

    /** Token from the file; if absent or too short, generate a random token and write it there. */
    public static Token load(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (text.length() >= 32) return new Token(text);
        }
        return rotate(file);
    }

    /** Write a fresh random token to the file (atomically, owner-only) and return it; the old one stops working. */
    public static Token rotate(Path file) throws IOException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String text = HexFormat.of().formatHex(random);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        try {
            // Restrict before writing: the secret never sits in a world-readable file.
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX (Windows): use the directory's default permissions.
        }
        Files.writeString(tmp, text + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Token(text);
    }

    /** The bearer value of an {@code Authorization} header, or null if the header is not a bearer header. */
    static byte[] bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
    }

    /** Constant-time comparison with a presented bearer value. */
    boolean matches(byte[] given) {
        return given != null && MessageDigest.isEqual(given, value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Token t && MessageDigest.isEqual(t.value, value);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(value);
    }

    /** {@code Authorization: Bearer <token>} header; constant-time comparison. */
    public boolean accepts(String header) {
        return matches(bearer(header));
    }
}
