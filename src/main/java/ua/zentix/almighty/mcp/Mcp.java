package ua.zentix.almighty.mcp;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.Api;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.mcp.signin.SignIn;
import ua.zentix.almighty.mcp.signin.SignInEndpoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The agent side of the bridge port: {@code /mcp}, the sign-in endpoints, and what {@code /almighty connect} shows.
 * Mounted on the running bridge at server start.
 */
public final class Mcp {
    private static final Logger LOG = LogUtils.getLogger();

    private final SignIn signIn;
    private final String mcpUrl;
    private final boolean publicHttps;

    private Mcp(SignIn signIn, String mcpUrl, boolean publicHttps) {
        this.signIn = signIn;
        this.mcpUrl = mcpUrl;
        this.publicHttps = publicHttps;
    }

    public static Mcp mount(MinecraftServer server, Bridge bridge, Path configDir, String host, String publicUrl, java.util.Set<String> allowedOrigins) {
        Agents agents = bridge.agents();
        SignIn signIn = new SignIn(configDir.resolve("oauth"), new SignIn.Issuer() {
            @Override
            public String issue(String name) throws IOException {
                Path file = agents.get(name) != null ? agents.revoke(name) : agents.add(name);
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            }

            @Override
            public boolean exists(String name) {
                return agents.get(name) != null;
            }

            @Override
            public boolean elevated(String name) {
                ua.zentix.almighty.GmServer gm = ua.zentix.almighty.GmServer.of(server);
                if (gm == null) return false;
                return gm.policies().current().ceiling(name).compareTo(ua.zentix.almighty.policy.Policy.defaultCeiling(name)) > 0;
            }

            @Override
            public String current(String name) throws IOException {
                var a = agents.get(name);
                return a == null || a.file() == null ? null : Files.readString(a.file(), StandardCharsets.UTF_8).strip();
            }
        }, System::currentTimeMillis);
        int port = bridge.address().getPort();
        SignInEndpoints endpoints = new SignInEndpoints(signIn, publicUrl, host, port, bridge.tls(), allowedOrigins, p -> notifyOwners(server, p));
        bridge.context("/mcp", new McpEndpoint(bridge, Api.LONG, Almighty.version(), endpoints::challenge));
        bridge.context("/.well-known/", endpoints);
        bridge.context("/oauth/", endpoints);
        String base = publicUrl != null && !publicUrl.isBlank() ? stripSlash(publicUrl.strip())
                : (bridge.tls() ? "https://" : "http://") + (host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        Mcp mcp = new Mcp(signIn, base + "/mcp", base.startsWith("https://"));
        LOG.info("Almighty ready - tell your AI agent: \"connect to my Minecraft server at {}\" (all ways: /almighty connect)", mcp.mcpUrl);
        return mcp;
    }

    public SignIn signIn() {
        return signIn;
    }

    public String mcpUrl() {
        return mcpUrl;
    }

    public List<Connect.Way> ways() {
        return Connect.ways(mcpUrl, publicHttps);
    }

    /**
     * Tells online operators and the console that a login waits, without its code: the approver must read the code
     * from the browser page, which is what proves the request is theirs.
     */
    private static final java.util.concurrent.atomic.AtomicLong lastNotice = new java.util.concurrent.atomic.AtomicLong();

    static void notifyOwners(MinecraftServer server, SignIn.Pending p) {
        String text = notice(p);
        LOG.info("Almighty sign-in: {}", text);
        long now = System.currentTimeMillis(), last = lastNotice.get();
        // At most one in-game notice every 30 s: a flood of sign-in attempts cannot flood the operators' chat.
        if (now - last < 30_000 || !lastNotice.compareAndSet(last, now)) return;
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (server.getProfilePermissions(player.getGameProfile()) >= 4) {
                    player.sendSystemMessage(Component.literal("[Almighty] " + text).withStyle(ChatFormatting.GOLD));
                }
            }
        });
    }

    /** The owners' notice for a waiting sign-in: who and from where, never the code. */
    public static String notice(SignIn.Pending p) {
        return p.client().name() + " (from " + p.address() + ") asks to connect to this server as an agent. If you started it, "
                + "type the code shown on its sign-in page: /almighty approve <code>";
    }

    static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

}
