package ua.zentix.almighty.mcp.signin;

import ua.zentix.almighty.policy.Policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * The owner's own app (plan 4.2 "owner client"): at approval the owner may say the app is theirs, and it gets their
 * rights, ceiling {@code owner}, written where every agent's rights live, {@code config/almighty/policy.toml}, as
 * {@code [agents.<name>] ceiling = "owner"}. Only the owner can type the approval (console or level-4 operator), so
 * this is the owner raising a ceiling in the file, as the plan allows. The new file is validated before it replaces
 * the old one; an agent the file already lists is left to the owner to edit.
 */
public final class OwnerApp {
    private OwnerApp() {}

    /** Adds the agent with ceiling owner; returns null, or why nothing was changed. */
    public static String grant(Path policyFile, String agent) throws IOException {
        String old = Files.isRegularFile(policyFile) ? Files.readString(policyFile, StandardCharsets.UTF_8) : "";
        if (Pattern.compile("(?m)^\\s*\\[\\s*agents\\.\"?" + Pattern.quote(agent) + "\"?\\s*]").matcher(old).find()) {
            return "Agent " + agent + " is already listed in " + policyFile.getFileName() + ": set its ceiling there, then /almighty reload";
        }
        String text = old + (old.isEmpty() || old.endsWith("\n") ? "" : "\n")
                + "\n# Added by /almighty approve ... owner: the owner's own app, with the owner's rights.\n"
                + "[agents." + agent + "]\nceiling = \"owner\"\n";
        try {
            Policy.parse(text);
        } catch (Policy.Invalid e) {
            return policyFile.getFileName() + " would not be valid (" + e.getMessage() + "); edit it by hand";
        }
        Files.createDirectories(policyFile.toAbsolutePath().getParent());
        Path tmp = policyFile.resolveSibling(policyFile.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, policyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return null;
    }
}
