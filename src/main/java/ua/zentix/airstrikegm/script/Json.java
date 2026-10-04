package ua.zentix.airstrikegm.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Значения скриптов и событий → JSON для ведущего, параметры JSON → значения Groovy. Игровое — коротко и так, чтобы
 * вернуть его в команду: позиция — {@code [x, y, z]}, блок — строкой как в {@code /setblock}, NBT — SNBT, сущность —
 * тип, имя, uuid и место. Событие — его свойства (методы {@code getX}/{@code isX}). Незнакомое — {@code toString()}.
 * Вложенность, длина списков и строк ограничены: ответ не разрастается от случайного большого объекта.
 */
public final class Json {
    static final int MAX_DEPTH = 8;
    static final int MAX_ITEMS = 1000;
    static final int MAX_TEXT = 4000;
    /** Свойства события раскрываются на столько уровней: сущность, позиция, список выпавших предметов. */
    static final int EVENT_DEPTH = 2;

    private Json() {}

    public static JsonElement from(Object value) {
        return from(value, MAX_DEPTH);
    }

    static JsonElement from(Object v, int depth) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof JsonElement e) return e;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? new JsonPrimitive(n.toString()) : new JsonPrimitive(n);
        }
        if (v instanceof CharSequence || v instanceof Character || v instanceof UUID) return text(v.toString());
        if (v instanceof Optional<?> o) return from(o.orElse(null), depth);
        if (v instanceof Component c) return text(c.getString());
        if (v instanceof ResourceLocation r) return text(r.toString());
        if (v instanceof ResourceKey<?> k) return text(k.location().toString());
        if (v instanceof Holder<?> h) return h.unwrapKey().<JsonElement>map(k -> text(k.location().toString())).orElseGet(() -> from(h.value(), depth));
        if (v instanceof Level l) return text(l.dimension().location().toString());
        if (v instanceof Entity e) return entity(e);
        if (v instanceof Vec3i p) return numbers(p.getX(), p.getY(), p.getZ());
        if (v instanceof Vec3 p) return numbers(round(p.x), round(p.y), round(p.z));
        if (v instanceof ChunkPos p) return numbers(p.x, p.z);
        if (v instanceof BlockState s) return text(BlockStateParser.serialize(s));
        if (v instanceof Block b) return text(BuiltInRegistries.BLOCK.getKey(b).toString());
        if (v instanceof Item i) return text(BuiltInRegistries.ITEM.getKey(i).toString());
        if (v instanceof EntityType<?> t) return text(EntityType.getKey(t).toString());
        if (v instanceof ItemStack s) return stack(s);
        if (v instanceof Tag t) return text(t.toString());
        if (v instanceof DamageSource d) return damage(d);
        if (v instanceof MinecraftServer) return text("server");
        if (v instanceof Enum<?> e) return text(e instanceof StringRepresentable s ? s.getSerializedName() : e.name());
        if (v instanceof Class<?> c) return text(c.getName());
        if (v instanceof Throwable t) return text(t.toString());
        if (depth <= 0) return text(String.valueOf(v));
        if (v instanceof Event e) return fields(e, Math.min(depth, EVENT_DEPTH));
        if (v instanceof Map<?, ?> m) {
            JsonObject out = new JsonObject();
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ == MAX_ITEMS) {
                    out.addProperty("…", "ещё " + (m.size() - MAX_ITEMS));
                    break;
                }
                out.add(String.valueOf(e.getKey()), from(e.getValue(), depth - 1));
            }
            return out;
        }
        if (v instanceof Iterable<?> it) {
            JsonArray out = new JsonArray();
            int n = 0;
            for (Object o : it) {
                if (n++ == MAX_ITEMS) {
                    out.add("… и дальше");
                    break;
                }
                out.add(from(o, depth - 1));
            }
            return out;
        }
        if (v.getClass().isArray()) {
            JsonArray out = new JsonArray();
            int length = Array.getLength(v);
            for (int i = 0; i < Math.min(length, MAX_ITEMS); i++) out.add(from(Array.get(v, i), depth - 1));
            if (length > MAX_ITEMS) out.add("… ещё " + (length - MAX_ITEMS));
            return out;
        }
        if (v instanceof Record r) {
            JsonObject out = new JsonObject();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                try {
                    Method m = c.getAccessor();
                    m.trySetAccessible();
                    out.add(c.getName(), from(m.invoke(r), depth - 1));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    out.addProperty(c.getName(), "не прочитать: " + e);
                }
            }
            return out;
        }
        return text(String.valueOf(v));
    }

    /**
     * Свойства события: публичные методы без параметров {@code getX()}/{@code isX()}, объявленные в самом событии и
     * его предках (без {@link Event} и {@link Object}), по алфавиту. Метод, который бросил, пропускается.
     */
    public static JsonObject fields(Object event) {
        return fields(event, EVENT_DEPTH);
    }

    static JsonObject fields(Object event, int depth) {
        JsonObject out = new JsonObject();
        Method[] methods = event.getClass().getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            String name = property(m);
            if (name == null || out.has(name)) continue;
            try {
                m.trySetAccessible();
                out.add(name, from(m.invoke(event), depth - 1));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // свойство, которое не читается без контекста, — не свойство события
            }
        }
        return out;
    }

    private static String property(Method m) {
        if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class) return null;
        Class<?> owner = m.getDeclaringClass();
        if (owner == Object.class || owner == Event.class) return null;
        String n = m.getName();
        String bare = n.startsWith("get") && n.length() > 3 ? n.substring(3)
                : n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class ? n.substring(2) : null;
        if (bare == null || !Character.isUpperCase(bare.charAt(0))) return null;
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    /** JSON → значения Groovy: объект — {@code Map}, массив — {@code List}, целое — {@code Integer}/{@code Long}. */
    public static Object toJava(JsonElement json) {
        if (json == null || json.isJsonNull()) return null;
        if (json.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) out.put(e.getKey(), toJava(e.getValue()));
            return out;
        }
        if (json.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray()) out.add(toJava(e));
            return out;
        }
        JsonPrimitive p = json.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return p.getAsString();
        double d = p.getAsDouble();
        if (d == Math.rint(d) && Math.abs(d) < 9e15) {
            long l = (long) d;
            return l == (int) l ? (Object) (int) l : (Object) l;
        }
        return d;
    }

    public static JsonObject entity(Entity e) {
        JsonObject out = new JsonObject();
        out.addProperty("type", EntityType.getKey(e.getType()).toString());
        if (e.hasCustomName() || e instanceof net.minecraft.world.entity.player.Player) out.addProperty("name", e.getName().getString());
        out.addProperty("uuid", e.getUUID().toString());
        out.addProperty("dimension", e.level().dimension().location().toString());
        out.add("pos", numbers(round(e.getX()), round(e.getY()), round(e.getZ())));
        if (e instanceof LivingEntity living) out.addProperty("health", Math.round(living.getHealth() * 10) / 10.0);
        if (e.isRemoved()) out.addProperty("removed", true);
        return out;
    }

    private static JsonObject stack(ItemStack s) {
        JsonObject out = new JsonObject();
        out.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        out.addProperty("count", s.getCount());
        if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) out.addProperty("name", s.getHoverName().getString());
        return out;
    }

    private static JsonObject damage(DamageSource d) {
        JsonObject out = new JsonObject();
        out.add("type", from(d.typeHolder(), 1));
        if (d.getEntity() != null) out.add("by", entity(d.getEntity()));
        if (d.getDirectEntity() != null && d.getDirectEntity() != d.getEntity()) out.add("direct", entity(d.getDirectEntity()));
        return out;
    }

    private static JsonPrimitive text(String s) {
        return new JsonPrimitive(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "… (" + s.length() + " символов)" : s);
    }

    private static JsonArray numbers(Number... values) {
        JsonArray out = new JsonArray();
        for (Number n : values) out.add(n);
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
