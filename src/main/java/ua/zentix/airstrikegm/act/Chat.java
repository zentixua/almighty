package ua.zentix.airstrikegm.act;

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
import ua.zentix.airstrikegm.GmConfig;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Слова ведущего игрокам: в чат с именем ({@code [Claude] …}), заголовком посреди экрана или строкой над хотбаром.
 * Текст — строкой ({@code text}) или текстовым компонентом JSON ({@code component}, как у {@code /tellraw}).
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
            if (!missing.isEmpty()) throw RpcException.notFound("Нет в игре: " + String.join(", ", missing));
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
                        // подзаголовок виден только вместе с заголовком: пустой заголовок его показывает
                        p.connection.send(new ClientboundSetSubtitleTextPacket(text));
                        p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
                    }
                }
            }
            case "actionbar" -> to.forEach(p -> p.sendSystemMessage(text, true));
            default -> throw RpcException.badRequest("style: chat, title, subtitle или actionbar");
        }
        if (!style.equals("chat") || args.has("to")) LOG.info("Ведущий ({}): {}", style, text.getString());
        JsonObject out = new JsonObject();
        out.addProperty("players", to.size());
        return out;
    }

    private static Component text(MinecraftServer server, Args args) throws RpcException {
        if (args.has("component")) {
            JsonElement json = args.raw().get("component");
            return ComponentSerialization.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
                    .getOrThrow(error -> RpcException.badRequest("component: " + error));
        }
        return Component.literal(args.string("text"));
    }
}
