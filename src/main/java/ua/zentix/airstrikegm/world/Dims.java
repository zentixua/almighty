package ua.zentix.airstrikegm.world;

import net.minecraft.ResourceLocationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;

import java.util.ArrayList;
import java.util.List;

/** Измерение из параметра {@code dimension} (по умолчанию — верхний мир). Поток сервера. */
public final class Dims {
    private Dims() {}

    public static ServerLevel level(MinecraftServer server, Args args) throws RpcException {
        if (!args.has("dimension")) return server.overworld();
        return level(server, args.string("dimension"));
    }

    /** Измерение по id ({@code minecraft:the_nether} или {@code the_nether}). */
    public static ServerLevel level(MinecraftServer server, String id) throws RpcException {
        ResourceLocation location;
        try {
            location = ResourceLocation.parse(id);
        } catch (ResourceLocationException e) {
            throw RpcException.badRequest("dimension: " + e.getMessage());
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
        if (level == null) {
            List<String> known = new ArrayList<>();
            for (ServerLevel l : server.getAllLevels()) known.add(l.dimension().location().toString());
            throw RpcException.notFound("Нет измерения " + id + "; есть: " + String.join(", ", known));
        }
        return level;
    }

    public static String id(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
