package ua.zentix.almighty.act;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The game master's words to players: chat with a name ({@code [Claude] ...}), a title in the middle of the screen,
 * or a line above the hotbar. Text is a string ({@code text}) or a JSON text component ({@code component}, as in {@code /tellraw}).
 */
public final class Chat {
    private static final Logger LOG = LogUtils.getLogger();

    private Chat() {}

    public static JsonElement say(MinecraftServer server, Args args) throws RpcException {
        Component text = text(server, args);
        String style = args.string("style", "chat").toLowerCase(Locale.ROOT);
        List<ServerPlayer> to = new ArrayList<>();
        if (args.has("to")) {
            List<String> missing = new ArrayList<>();
            for (String name : args.strings("to")) {
                ServerPlayer p = server.getPlayerList().getPlayerByName(name);
                if (p == null) missing.add(name);
                else to.add(p);
            }
            if (!missing.isEmpty()) throw RpcException.notFound("Not online: " + String.join(", ", missing));
        } else {
            to.addAll(server.getPlayerList().getPlayers());
        }
        switch (style) {
            case "chat" -> {
                Component line = Component.literal("[" + GmConfig.NAME.get() + "] ").withStyle(ChatFormatting.GOLD).append(text);
                if (args.has("to")) to.forEach(p -> p.sendSystemMessage(line));
                else server.getPlayerList().broadcastSystemMessage(line, false);
            }
            case "title", "subtitle" -> {
                int fadeIn = args.integer("fade_in", 10, 0, 200), stay = args.integer("stay", 70, 0, 1200), fadeOut = args.integer("fade_out", 20, 0, 200);
                for (ServerPlayer p : to) {
                    p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
                    if (style.equals("title")) {
                        p.connection.send(new ClientboundSetTitleTextPacket(text));
                    } else {
                        // A subtitle is visible only alongside a title: an empty title makes it visible.
                        p.connection.send(new ClientboundSetSubtitleTextPacket(text));
                        p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
                    }
                }
            }
            case "actionbar" -> to.forEach(p -> p.sendSystemMessage(text, true));
            default -> throw RpcException.badRequest("style: chat, title, subtitle or actionbar");
        }
        if (!style.equals("chat") || args.has("to")) LOG.info("GM ({}): {}", style, text.getString());
        // A reply opens a conversation: the players' next lines reach the GM without its name.
        if (GmServer.of(server) instanceof GmServer gm) gm.inbox().replied(args.has("to") ? to : null);
        JsonObject out = new JsonObject();
        out.addProperty("players", to.size());
        return out;
    }

    /** The prefix of the server's own confirmations and receipts; agent text below owner may not carry it. */
    public static final String RESERVED = "[Almighty]";

    private static Component text(MinecraftServer server, Args args) throws RpcException {
        boolean owner = owner(server, args);
        Component c;
        if (args.has("component")) {
            JsonElement json = args.raw().get("component").deepCopy();
            // Below owner (plan 4.8): no buttons that run or type commands as the clicker (on 1.21.1 a run_command
            // click runs without a prompt). Stripped in the JSON, so translation arguments are covered too.
            if (!owner) strip(json);
            c = ComponentSerialization.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
                    .getOrThrow(error -> RpcException.badRequest("component: " + error));
        } else {
            c = Component.literal(args.string("text"));
        }
        if (!owner && ua.zentix.almighty.policy.Forgery.prefix(c.getString())) {
            throw new RpcException(403, "policy", "Text may not contain " + RESERVED + " (reserved for the server's own messages)");
        }
        return c;
    }

    /** Whether this call speaks with owner rights (in-process calls do). */
    private static boolean owner(MinecraftServer server, Args args) throws RpcException {
        if (args.caller().agent() == null) return true;
        GmServer gm = GmServer.of(server);
        return gm != null && gm.gate().rights(args.caller(), args.raw()).effective() == ua.zentix.almighty.policy.Role.OWNER;
    }

    /** Click actions that never act as whoever clicks: everything else (run_command, suggest_command, show_dialog, custom) goes. */
    static final java.util.Set<String> SAFE_CLICKS = java.util.Set.of("open_url", "copy_to_clipboard", "change_page");

    /** Remove click events that act as whoever clicks, at any depth of a JSON text component (plan 4.8). */
    static void strip(JsonElement e) {
        if (e instanceof com.google.gson.JsonArray a) {
            for (JsonElement x : a) strip(x);
        } else if (e instanceof JsonObject o) {
            for (String key : new String[]{"clickEvent", "click_event"}) {
                if (!o.has(key)) continue;
                JsonElement action = o.get(key) instanceof JsonObject click ? click.get("action") : null;
                String id = action != null && action.isJsonPrimitive() ? action.getAsString().toLowerCase(java.util.Locale.ROOT) : "";
                if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
                if (!SAFE_CLICKS.contains(id)) o.remove(key);
            }
            for (var entry : o.entrySet()) strip(entry.getValue());
        }
    }
}
