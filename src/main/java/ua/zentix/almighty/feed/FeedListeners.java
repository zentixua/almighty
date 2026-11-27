package ua.zentix.almighty.feed;

import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.policy.Identity;

import java.util.Locale;
import java.util.Set;

/**
 * Game events to the GM feed: chat, joins and leaves, player deaths with cause, advancements, dimension changes
 * and player commands (except private messages). The loader calls these as an observer, last
 * (NeoForge: {@code LOWEST} priority; the bus does not deliver events canceled by another mod there).
 */
public final class FeedListeners {
    /** Players' private messages to each other are hidden from the GM; {@code /gm} has its own event ({@link GmCommand}). */
    private static final Set<String> PRIVATE = Set.of("msg", "tell", "w", "teammsg", "tm", GmCommand.NAME, "almighty");

    private FeedListeners() {}

    public static void onChat(ServerPlayer player, String raw) {
        JsonObject d = player(player);
        d.addProperty("text", raw);
        d.add("untrusted", Inbox.untrusted("text"));
        GmServer gm = GmServer.of(Mc.server(player));
        if (gm != null) gm.inbox().chat(player, d, raw);
    }

    public static void onLogin(ServerPlayer p) {
        add(p, "join", where(p));
    }

    public static void onLogout(ServerPlayer p) {
        add(p, "leave", where(p));
        GmServer gm = GmServer.of(Mc.server(p));
        if (gm != null) gm.inbox().left(p);
    }

    public static void onDeath(ServerPlayer p, DamageSource source) {
        JsonObject d = where(p);
        d.addProperty("message", source.getLocalizedDeathMessage(p).getString());
        // Death messages carry names, which players choose (an item or a named mob).
        d.add("untrusted", Inbox.untrusted("message", "killer"));
        Entity killer = source.getEntity();
        if (killer != null && killer != p) d.addProperty("killer", killer.getName().getString());
        add(p, "death", d);
    }

    public static void onAdvancement(ServerPlayer p, AdvancementHolder advancement) {
        DisplayInfo display = advancement.value().display().orElse(null);
        if (display == null || !ua.zentix.almighty.platform.Mc.announces(display)) return;
        JsonObject d = player(p);
        d.addProperty("title", ua.zentix.almighty.platform.Mc.title(display).getString());
        add(p, "advancement", d);
    }

    public static void onDimension(ServerPlayer p, ResourceKey<Level> from, ResourceKey<Level> to) {
        JsonObject d = player(p);
        d.addProperty("from", Mc.id(from).toString());
        d.addProperty("to", Mc.id(to).toString());
        add(p, "dimension", d);
    }

    /** A command about to run (the loader's command event, before execution). */
    public static void onCommand(ParseResults<CommandSourceStack> parse) {
        // Only commands typed by the player: a GM command as the player (commandAs) is not their command.
        if (!(Mc.typedBy(parse.getContext().getSource()) instanceof ServerPlayer p)) return;
        String command = parse.getReader().getString();
        String root = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) root = root.substring(1);
        if (PRIVATE.contains(root)) return;
        JsonObject d = player(p);
        d.addProperty("command", command);
        d.add("untrusted", Inbox.untrusted("command"));
        add(p, "command", d);
    }

    /**
     * Who: name, UUID, whether the identity is verified ({@link Identity}), Bedrock through Floodgate, and the client
     * language (so the GM answers in it).
     */
    static JsonObject player(Player p) {
        JsonObject d = new JsonObject();
        d.addProperty("player", Mc.name(p.getGameProfile()));
        d.addProperty("uuid", p.getUUID().toString());
        d.addProperty("verified", Identity.verified(p.getUUID()));
        if (Identity.bedrock(p.getUUID())) d.addProperty("bedrock", true);
        if (p instanceof ServerPlayer sp) d.addProperty("lang", sp.clientInformation().language());
        // A GM bot is a player to everyone, but the GM needs to distinguish its actions from human actions.
        if (p instanceof ServerPlayer sp && GmServer.of(Mc.server(sp)) instanceof GmServer gm && gm.bots().of(sp) != null) d.addProperty("bot", true);
        return d;
    }

    static JsonObject where(ServerPlayer p) {
        JsonObject d = player(p);
        d.addProperty("dimension", Mc.id(p.level().dimension()).toString());
        d.addProperty("x", p.getBlockX());
        d.addProperty("y", p.getBlockY());
        d.addProperty("z", p.getBlockZ());
        return d;
    }

    private static void add(ServerPlayer p, String type, JsonObject data) {
        GmServer gm = GmServer.of(Mc.server(p));
        if (gm != null) gm.feed().add(type, data);
    }
}
