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
 * Личный канал игрока к ведущему: {@code /gm <текст>} может любой игрок. Текст уходит в ленту событием {@code gm}
 * (игрок, где он, текст), другим игрокам и операторам не показывается; самому игроку — серая строка «→ ведущий: …».
 * Ответить лично ведущий может методом {@code say} с {@code to}.
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
            source.sendFailure(Component.literal("Ведущего на этом сервере нет"));
            return 0;
        }
        JsonObject d = FeedListeners.where(player);
        d.addProperty("text", text);
        gm.feed().add(NAME, d);
        // false: только самому игроку, операторам не рассылается
        source.sendSuccess(() -> Component.literal("→ " + GmConfig.NAME.get() + ": " + text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        return 1;
    }
}
