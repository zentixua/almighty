package ua.zentix.almighty.feed;

import com.google.gson.JsonObject;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import ua.zentix.almighty.GmServer;

import java.util.Locale;
import java.util.Set;

/**
 * События игры → лента ведущего: чат, входы и выходы, смерти игроков с причиной, достижения, смена измерения, команды
 * игроков (кроме личных сообщений). Только наблюдение, последним ({@link EventPriority#LOWEST}): событие, отменённое
 * другим модом, шина сюда не доставляет.
 */
public final class FeedListeners {
    /** Личные сообщения игроков друг другу ведущему не показываются; {@code /gm} — своим событием ({@link GmCommand}). */
    private static final Set<String> PRIVATE = Set.of("msg", "tell", "w", "teammsg", "tm", GmCommand.NAME);

    private FeedListeners() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, FeedListeners::onChat);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onLogin);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onLogout);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onDeath);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onAdvancement);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onDimension);
        bus.addListener(EventPriority.LOWEST, FeedListeners::onCommand);
    }

    private static void onChat(ServerChatEvent e) {
        JsonObject d = player(e.getPlayer());
        d.addProperty("text", e.getRawText());
        add(e.getPlayer(), "chat", d);
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) add(p, "join", where(p));
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) add(p, "leave", where(p));
    }

    private static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = where(p);
        d.addProperty("message", e.getSource().getLocalizedDeathMessage(p).getString());
        Entity killer = e.getSource().getEntity();
        if (killer != null && killer != p) d.addProperty("killer", killer.getName().getString());
        add(p, "death", d);
    }

    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        DisplayInfo display = e.getAdvancement().value().display().orElse(null);
        if (display == null || !display.shouldAnnounceChat()) return;
        JsonObject d = player(p);
        d.addProperty("title", display.getTitle().getString());
        add(p, "advancement", d);
    }

    private static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = player(p);
        d.addProperty("from", e.getFrom().location().toString());
        d.addProperty("to", e.getTo().location().toString());
        add(p, "dimension", d);
    }

    private static void onCommand(CommandEvent e) {
        // только набранное самим игроком: команда ведущего от лица игрока (commandAs) — не его
        if (!(e.getParseResults().getContext().getSource().source instanceof ServerPlayer p)) return;
        String command = e.getParseResults().getReader().getString();
        String root = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) root = root.substring(1);
        if (PRIVATE.contains(root)) return;
        JsonObject d = player(p);
        d.addProperty("command", command);
        add(p, "command", d);
    }

    private static JsonObject player(Player p) {
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        // бот ведущего — игрок для всех, но ведущему важно отличать его действия от действий людей
        if (p instanceof ServerPlayer sp && GmServer.of(sp.server) instanceof GmServer gm && gm.bots().of(sp) != null) d.addProperty("bot", true);
        return d;
    }

    static JsonObject where(ServerPlayer p) {
        JsonObject d = player(p);
        d.addProperty("dimension", p.level().dimension().location().toString());
        d.addProperty("x", p.getBlockX());
        d.addProperty("y", p.getBlockY());
        d.addProperty("z", p.getBlockZ());
        return d;
    }

    private static void add(ServerPlayer p, String type, JsonObject data) {
        GmServer gm = GmServer.of(p.server);
        if (gm != null) gm.feed().add(type, data);
    }
}
