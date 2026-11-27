package ua.zentix.almighty.fabric;

import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One call of a Fabric callback as a rule sees it: the callback's name, its arguments by name ({@code arguments};
 * parameter names when compiled in, otherwise the argument type's name; also top-level fields and script properties),
 * and shortcuts {@code server}, {@code player}, {@code level}, {@code pos}, {@code entity} for the first argument of
 * each kind (the server also from the level or entity). Its getters are its event properties.
 */
public final class FabricEvent {
    private final String callback;
    private final Method method;
    private final Map<String, Object> arguments = new LinkedHashMap<>();
    private net.minecraft.server.MinecraftServer server;
    private ServerPlayer player;
    private Level level;
    private BlockPos pos;
    private Entity entity;
    private Entity placeEntity;
    private boolean canceled;

    FabricEvent(String callback, Method method, Object[] args) {
        this.callback = callback;
        this.method = method;
        Parameter[] params = method.getParameters();
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            String name = params[i].isNamePresent() ? params[i].getName() : decapitalize(params[i].getType().getSimpleName());
            if (arguments.containsKey(name)) name = name + i;
            arguments.put(name, a);
            if (a instanceof net.minecraft.server.MinecraftServer m && server == null) server = m;
            if (a instanceof ServerPlayer p && player == null) player = p;
            else if (a instanceof Entity e && entity == null) entity = e;
            if (a instanceof Level l && level == null) level = l;
            if (a instanceof BlockPos b && pos == null) pos = b;
            if (a instanceof BlockHitResult hit && pos == null) pos = hit.getBlockPos();
            if (a instanceof Entity e && placeEntity == null && placing(params[i].getType())) placeEntity = e;
            if (a instanceof net.minecraft.world.phys.EntityHitResult hit && placeEntity == null) placeEntity = hit.getEntity();
        }
    }

    /**
     * Whether an argument of this declared type gives the call a place in the world: a block position or hit, or an
     * entity the callback is about. A parameter declared as a player is the one who acts, not a place.
     */
    static boolean placing(Class<?> declared) {
        if (BlockPos.class.isAssignableFrom(declared) || net.minecraft.world.phys.HitResult.class.isAssignableFrom(declared)) return true;
        return Entity.class.isAssignableFrom(declared) && !net.minecraft.world.entity.player.Player.class.isAssignableFrom(declared);
    }

    /** The entity whose position is the call's place (null: none). */
    Entity placeEntity() {
        return placeEntity;
    }

    private static String decapitalize(String s) {
        return s.isEmpty() ? "arg" : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    public String getCallback() {
        return callback;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    /** A rule script's {@code event.<argument>} ({@code event.server} on a tick callback): Groovy asks this for a name no getter has. */
    public Object propertyMissing(String name) {
        if (arguments.containsKey(name)) return arguments.get(name);
        throw new groovy.lang.MissingPropertyException(name, FabricEvent.class);
    }

    public net.minecraft.server.MinecraftServer getServer() {
        if (server != null) return server;
        Level l = getLevel();
        return l != null ? l.getServer() : null;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public Level getLevel() {
        return level != null ? level : entity != null ? entity.level() : player != null ? player.level() : null;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Entity getEntity() {
        return entity;
    }

    public boolean isCanceled() {
        return canceled;
    }

    /** {@code event.canceled = true} in a rule script; only a callback with a "stop" value can be cancelled. */
    public void setCanceled(boolean canceled) {
        if (canceled && !cancellable()) throw new UnsupportedOperationException(callback + " cannot be canceled");
        this.canceled = canceled;
    }

    boolean canceled() {
        return canceled;
    }

    boolean cancellable() {
        Class<?> r = method.getReturnType();
        return r == boolean.class || r == InteractionResult.class || r == TriState.class;
    }

    void cancel() {
        canceled = true;
    }
}
