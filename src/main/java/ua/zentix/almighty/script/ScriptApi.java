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
import ua.zentix.almighty.world.Ship;
import ua.zentix.almighty.world.Ships;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code gm} variable in scripts and rules: operations awkward to write through game classes.
 * All on the server thread. Rule commands log at DEBUG (a rule may call them every tick); one-shot scripts at INFO.
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

    /** Commands as the GM at Overworld spawn: output for each, like the {@code command} method. */
    public Object command(String... commands) {
        return command(Arrays.asList(commands));
    }

    public Object command(List<String> commands) {
        ServerLevel level = server.overworld();
        return Json.toJava(CommandRunner.run(server, level, Vec3.atBottomCenterOf(level.getSharedSpawnPos()), null, commands, quiet));
    }

    /** Commands as an entity: {@code @s}, position, rotation and dimension belong to it. */
    public Object commandAs(Entity entity, String... commands) {
        return commandAs(entity, Arrays.asList(commands));
    }

    public Object commandAs(Entity entity, List<String> commands) {
        if (entity == null) throw new IllegalArgumentException("commandAs: entity is null");
        ServerLevel level = (ServerLevel) entity.level();
        return Json.toJava(CommandRunner.run(server, level, entity.position(), entity, commands, quiet));
    }

    /** Write an {@code emit} event to the feed with this data (like a script response: position, entity, event...). */
    public void emit(Object data) {
        emit.accept(Json.from(data));
    }

    /** Object properties as in the feed (event {@code getX()} methods), as Groovy values. */
    public Object fields(Object event) {
        return Json.toJava(Json.fields(event));
    }

    /** Online player by name; null if absent. */
    public ServerPlayer player(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    /**
     * GM bot by name; null if absent. Bot methods: {@code player()} is its current entity; {@code send(packet)}
     * sends any client-to-server packet ({@code new ServerboundRenameItemPacket("name")});
     * {@code act([[hold: "forward"], [wait: 20]])} runs a step program like {@code bot.act}; {@code act(steps, true)}
     * removes ongoing programs first. If the controlling rule turns off, the bot stops; autopilot controls should
     * use {@code [hold: "forward", ticks: 10]} on each run.
     */
    public Bot bot(String name) {
        GmServer gm = GmServer.of(server);
        return gm == null ? null : gm.bots().find(name);
    }

    /** All GM bots. */
    public List<Bot> bots() {
        GmServer gm = GmServer.of(server);
        return gm == null ? List.of() : gm.bots().all();
    }

    /**
     * Ship (Sable, Create Aeronautics) by its player-assigned name; null if absent. Ship methods: {@code pos()},
     * {@code orientation()}, {@code velocity()} (blocks per second), {@code angularVelocity()}, {@code box()},
     * {@code plotBox()}, {@code toWorld(plot point)}, {@code toPlot(world point)}, {@code toWorldDir(direction)},
     * {@code blockEntities("create:...")} for ship block entities in ready plot chunks, {@code describe()}.
     */
    public Ship ship(String name) {
        return Ships.find(server, name);
    }

    /** All ships in all dimensions (empty without a ship mod). */
    public List<Ship> ships() {
        return Ships.all(server);
    }

    /** Dimension: {@code minecraft:the_nether} or without a namespace ({@code the_nether}); null if absent. */
    public ServerLevel level(String dimension) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        } catch (ResourceLocationException e) {
            return null;
        }
    }
}
