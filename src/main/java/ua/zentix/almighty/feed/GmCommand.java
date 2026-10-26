package ua.zentix.almighty.feed;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.GmServer;

/**
 * A player's private channel to the game master: any player can use {@code /gm <text>}. The text enters the feed
 * as a {@code gm} event (player, location, text), hidden from other players and operators; the sender sees a gray
 * line "-> game master: ...". The game master can reply privately with {@code say} and {@code to}.
 */
public final class GmCommand {
    static final String NAME = "gm";

    private GmCommand() {}

    public static void register(IEventBus bus) {
        bus.addListener(GmCommand::onRegister);
    }

    private static void onRegister(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal(NAME)
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> send(c.getSource(), StringArgumentType.getString(c, "text")))));
    }

    static int send(CommandSourceStack source, String text) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) {
            source.sendFailure(GmMessages.unavailable(player.clientInformation().language()));
            return 0;
        }
        JsonObject d = FeedListeners.where(player);
        d.addProperty("text", text);
        gm.feed().add(NAME, d);
        // false: only the sender, never broadcast to operators.
        source.sendSuccess(() -> Component.literal("→ " + GmConfig.NAME.get() + ": " + text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }
}
