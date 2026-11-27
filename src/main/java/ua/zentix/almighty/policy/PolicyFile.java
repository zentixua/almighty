package ua.zentix.almighty.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Small, exact edits to {@code policy.toml} that keep the owner's comments and layout: the new text must parse
 * before it replaces the file (atomic move), so an edit never leaves a broken policy.
 */
public final class PolicyFile {
    private static final Pattern OWNERS = Pattern.compile("(?ms)^owners\\s*=\\s*\\[.*?\\]");

    private PolicyFile() {}

    /** Replace the owners list; returns null, or why nothing changed. */
    public static String owners(Path file, Set<UUID> owners) throws IOException {
        String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String list = "owners = [" + owners.stream().map(u -> "\"" + u + "\"").collect(Collectors.joining(", ")) + "]";
        Matcher m = OWNERS.matcher(old);
        String text = m.find() ? old.substring(0, m.start()) + list + old.substring(m.end()) : list + "\n" + old;
        return write(file, text);
    }

    /** Validate and write {@code text} as the new policy; returns null, or the parse error (file untouched). */
    public static String write(Path file, String text) throws IOException {
        try {
            Policy.parse(text);
        } catch (Policy.Invalid e) {
            return e.getMessage();
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return null;
    }
}
