package ua.zentix.almighty.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Predicate;

/**
 * The agent's connection check (plan 9.1 step 5): what its first {@code status} shows about this server's setup —
 * preset, who counts as verified, the permission handler, claim mods, TLS, Bedrock — and anything wrong, each with
 * its plain fix, so the agent can suggest a first setup in words. Pure: the facts come in, unit-tested.
 */
public final class ConnectionCheck {
    /** Facts about this server that the policy does not hold. */
    public record Facts(boolean onlineMode, Predicate<String> modLoaded, List<String> claimMods, String bridgeHost,
                        boolean tls, boolean plainAllowed, String publicUrl, boolean scriptsEnabled) {}

    private ConnectionCheck() {}

    public static JsonObject of(Policy policy, Facts f) {
        JsonObject out = new JsonObject();
        out.addProperty("preset", policy.preset().id());
        out.addProperty("owners", policy.owners().size());
        out.addProperty("identity", policy.identity().name().toLowerCase(java.util.Locale.ROOT));
        out.addProperty("online_mode", f.onlineMode());
        String handler = f.modLoaded().test("luckperms") ? "luckperms"
                : f.modLoaded().test("fabric-permissions-api-v0") ? "fabric-permissions-api" : "role ladder (/almighty role)";
        out.addProperty("permissions", handler);
        JsonArray claims = new JsonArray();
        f.claimMods().forEach(claims::add);
        out.add("claims", claims);
        boolean bedrock = f.modLoaded().test("floodgate") || f.modLoaded().test("geyser") || f.modLoaded().test("geyser_fabric");
        out.addProperty("bedrock", bedrock ? "floodgate" : "none");
        out.addProperty("tls", f.tls());
        if (!f.publicUrl().isBlank()) out.addProperty("public_url", f.publicUrl());
        out.addProperty("scripts", f.scriptsEnabled());

        JsonArray problems = new JsonArray();
        if (policy.owners().isEmpty()) {
            problems.add("No owners in policy.toml: nobody is trusted as the owner" + (policy.preset() == Policy.Preset.PRIVATE
                    ? ", and on preset private only owners reach the game master" : "")
                    + ". Fix: the owner types /almighty whoami in game, then /almighty owner add <their name> at the console.");
        }
        Identity.Mode id = policy.identity();
        if (!f.onlineMode() && (id == Identity.Mode.AUTO || id == Identity.Mode.MOJANG)) {
            problems.add("online-mode=false with identity " + id.name().toLowerCase(java.util.Locale.ROOT)
                    + ": no player counts as verified, so everyone acts as a guest. Fix: behind Velocity with modern forwarding set"
                    + " identity = \"proxy\"; on a truly offline server set identity = \"offline\" (anyone can take any name).");
        }
        if (id == Identity.Mode.OFFLINE) {
            problems.add("identity = \"offline\": anyone can join under any name, owners included. Fix: online-mode=true, or a proxy with"
                    + " forwarding and identity = \"proxy\".");
        }
        if (policy.preset() == Policy.Preset.PUBLIC && f.scriptsEnabled()) {
            problems.add("Preset public with scripts on (scripts.enabled): an owner-level agent can run code in the server. Fix: set"
                    + " scripts.enabled = false in config/almighty-common.toml unless you need scripts.");
        }
        if (!f.publicUrl().isBlank() && f.publicUrl().startsWith("http://") && !ua.zentix.almighty.mcp.signin.SignIn.loopback(host(f.publicUrl()))) {
            problems.add("bridge.public_url is plain http: tokens cross the internet readable. Fix: an https address (a reverse proxy or"
                    + " tunnel with a certificate).");
        }
        if (!f.tls() && f.plainAllowed() && !ua.zentix.almighty.mcp.signin.SignIn.loopback(f.bridgeHost())) {
            problems.add("The bridge listens on " + f.bridgeHost() + " without TLS (bridge.allow_plain_http_off_loopback). Fix: keep that"
                    + " address reachable only from this machine, or set bridge.tls_cert and bridge.tls_key.");
        }
        out.add("problems", problems);
        return out;
    }

    private static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
