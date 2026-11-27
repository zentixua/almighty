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
 * parameter names when compiled in, otherwise the argument type's name), and shortcuts {@code player}, {@code level},
 * {@code pos}, {@code entity} for the first argument of each kind. Its getters are its event properties.
 */
public final class FabricEvent {
    private final String callback;
    private final Method method;
    private final Map<String, Object> arguments = new LinkedHashMap<>();
    private ServerPlayer player;
    private Level level;
    private BlockPos pos;
    private Entity entity;
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
            if (a instanceof ServerPlayer p && player == null) player = p;
            else if (a instanceof Entity e && entity == null) entity = e;
            if (a instanceof Level l && level == null) level = l;
            if (a instanceof BlockPos b && pos == null) pos = b;
            if (a instanceof BlockHitResult hit && pos == null) pos = hit.getBlockPos();
        }
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
