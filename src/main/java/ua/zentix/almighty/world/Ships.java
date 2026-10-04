package ua.zentix.almighty.world;

import com.google.gson.JsonArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.compat.Compat;

import java.util.ArrayList;
import java.util.List;

/** Корабли модов физики ({@link Ship}): источник — совместимость с модом, без мода — кораблей нет. Поток сервера. */
public final class Ships {
    /** Корабли мода в одном измерении. */
    public interface Source {
        List<Ship> all(ServerLevel level);
    }

    private static final Source NONE = level -> List.of();
    private static Source source;

    private Ships() {}

    private static Source source() {
        if (source == null) {
            Source s = Compat.ships();
            source = s == null ? NONE : s;
        }
        return source;
    }

    public static boolean available() {
        return source() != NONE;
    }

    public static List<Ship> all(ServerLevel level) {
        return source().all(level);
    }

    /** Все корабли всех измерений. */
    public static List<Ship> all(MinecraftServer server) {
        List<Ship> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) out.addAll(all(level));
        return out;
    }

    /** Корабль по имени (верхний мир — первым); нет — null. */
    public static Ship find(MinecraftServer server, String name) {
        for (Ship s : all(server)) if (name.equals(s.name())) return s;
        return null;
    }

    /** Метод {@code ships}: {@code name} — один по имени; {@code center} + {@code radius} — рамкой рядом; измерение. */
    public static JsonArray describe(MinecraftServer server, Args a) throws RpcException {
        if (!available()) throw RpcException.unavailable("Кораблей нет: мод кораблей (Sable) не стоит");
        ServerLevel level = Dims.level(server, a);
        String name = a.string("name", null);
        Vec3 center = null;
        double radius = 0;
        if (a.has("center")) {
            int[] c = a.ints("center", 3);
            center = new Vec3(c[0], c[1], c[2]);
            radius = a.number("radius", 512, 1, 30_000_000);
        }
        JsonArray out = new JsonArray();
        for (Ship s : all(level)) {
            if (name != null && !name.equals(s.name())) continue;
            if (center != null && s.pos().distanceTo(center) > radius && !s.box().inflate(radius).contains(center)) continue;
            out.add(s.describe());
        }
        return out;
    }
}
