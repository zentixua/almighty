package ua.zentix.almighty.mcp.signin;

import ua.zentix.almighty.policy.Policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The owner's own app (plan 4.2 "owner client"): at approval the owner may say the app is theirs, and it gets their
 * rights, ceiling {@code owner}, written where every agent's rights live, {@code config/almighty/policy.toml}, as
 * {@code [agents.<name>] ceiling = "owner"}. Only the owner can type the approval (console or level-4 operator), so
 * this is the owner raising a ceiling in the file, as the plan allows. The new file is validated before it replaces
 * the old one; an agent the file already lists is left to the owner to edit.
 */
public final class OwnerApp {
    private OwnerApp() {}

    /** Why the agent cannot be granted (the file already lists it, or cannot be read as a policy); null if it can. */
    public static String listed(Path policyFile, String agent) throws IOException {
        if (!Files.isRegularFile(policyFile)) return null;
        try {
            Policy p = Policy.parse(Files.readString(policyFile, StandardCharsets.UTF_8));
            if (p.agents().containsKey(agent)) {
                return "Agent " + agent + " is already listed in " + policyFile.getFileName() + ": set its ceiling there, then /almighty reload";
            }
            return null;
        } catch (Policy.Invalid e) {
            return policyFile.getFileName() + " is not valid now (" + e.getMessage() + "); fix it first";
        }
    }

    /** Adds the agent with ceiling owner; returns null, or why nothing was changed. */
    public static String grant(Path policyFile, String agent) throws IOException {
        String old = Files.isRegularFile(policyFile) ? Files.readString(policyFile, StandardCharsets.UTF_8) : "";
        String listed = listed(policyFile, agent);
        if (listed != null) return listed;
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
