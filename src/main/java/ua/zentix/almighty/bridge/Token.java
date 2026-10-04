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
 * Токен моста: файл {@code config/almighty/token} (только владельцу), создаётся при первом запуске. Клиент моста
 * читает его оттуда же ({@code mcp/almighty.py}); в лог токен не пишется.
 */
public final class Token {
    private final byte[] value;

    private Token(String value) {
        this.value = value.getBytes(StandardCharsets.UTF_8);
    }

    public static Token of(String value) {
        return new Token(value);
    }

    /** Токен из файла; нет файла — новый случайный, записанный туда. */
    public static Token load(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (text.length() >= 32) return new Token(text);
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String text = HexFormat.of().formatHex(random);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // не POSIX (Windows): права по умолчанию каталога
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Token(text);
    }

    /** Заголовок {@code Authorization: Bearer <токен>}; сравнение за постоянное время. */
    public boolean accepts(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, value);
    }
}
