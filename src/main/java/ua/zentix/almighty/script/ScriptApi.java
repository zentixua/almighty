package ua.zentix.almighty.script;

import com.google.gson.JsonElement;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.act.CommandRunner;
import ua.zentix.almighty.bot.Bot;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Переменная {@code gm} скриптов и правил: то, что неудобно писать через классы игры. Всё — в потоке сервера.
 * Команды правил пишутся в лог строкой DEBUG (правило может звать их каждый тик), разового скрипта — INFO.
 */
public final class ScriptApi {
    private final MinecraftServer server;
    private final boolean quiet;
    private final Consumer<JsonElement> emit;

    public ScriptApi(MinecraftServer server, boolean quiet, Consumer<JsonElement> emit) {
        this.server = server;
        this.quiet = quiet;
        this.emit = emit;
    }

    /** Команды от имени ведущего у точки появления верхнего мира: вывод каждой — как у метода {@code command}. */
    public Object command(String... commands) {
        return command(Arrays.asList(commands));
    }

    public Object command(List<String> commands) {
        ServerLevel level = server.overworld();
        return Json.toJava(CommandRunner.run(server, level, Vec3.atBottomCenterOf(level.getSharedSpawnPos()), null, commands, quiet));
    }

    /** Команды от лица сущности: {@code @s} — она, место, поворот и измерение — её. */
    public Object commandAs(Entity entity, String... commands) {
        return commandAs(entity, Arrays.asList(commands));
    }

    public Object commandAs(Entity entity, List<String> commands) {
        if (entity == null) throw new IllegalArgumentException("commandAs: сущность null");
        ServerLevel level = (ServerLevel) entity.level();
        return Json.toJava(CommandRunner.run(server, level, entity.position(), entity, commands, quiet));
    }

    /** Записать в ленту событие {@code emit} с этими данными (как в ответе скрипта: позиция, сущность, событие…). */
    public void emit(Object data) {
        emit.accept(Json.from(data));
    }

    /** Свойства объекта как в ленте (событие — его {@code getX()}), значениями Groovy. */
    public Object fields(Object event) {
        return Json.toJava(Json.fields(event));
    }

    /** Игрок в игре по имени; нет — null. */
    public ServerPlayer player(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    /**
     * Бот ведущего по имени; нет — null. У бота: {@code player()} — его сущность сейчас, {@code send(пакет)} — любой
     * пакет клиента серверу ({@code new ServerboundRenameItemPacket("имя")}), {@code act([[hold: "forward"], [wait: 20]])}
     * — программа шагов, как у {@code bot.act}.
     */
    public Bot bot(String name) {
        GmServer gm = GmServer.of(server);
        return gm == null ? null : gm.bots().find(name);
    }

    /** Все боты ведущего. */
    public List<Bot> bots() {
        GmServer gm = GmServer.of(server);
        return gm == null ? List.of() : gm.bots().all();
    }

    /** Измерение: {@code minecraft:the_nether} или без пространства имён ({@code the_nether}); нет — null. */
    public ServerLevel level(String dimension) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        } catch (ResourceLocationException e) {
            return null;
        }
    }
}
