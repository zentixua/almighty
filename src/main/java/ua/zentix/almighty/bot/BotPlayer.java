package ua.zentix.almighty.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Игрок бота. Транспорт, которым правит игрок (лодка, лошадь), игра двигает там, где водитель «местный»
 * ({@code Entity.isControlledByLocalInstance} → {@code Player.isLocalPlayer}): у живого игрока — на его клиенте, а
 * сервер ждёт от клиента место. Клиент бота — сам сервер, поэтому для транспорта, который ведёт ввод водителя (метка
 * {@code almighty:driven_by_rider}), бот «местный» ({@link Vehicles#local}) и сервер считает его движение по вводу
 * бота. Остальной — нет: код мода за той же проверкой может читать клиент (клавиши), которого на сервере нет; для
 * такого транспорта — привод из совместимости с модом ({@link VehicleDriver}).
 * Возрождение создаёт игрока заново — тем же классом ({@code mixin/PlayerListMixin}).
 */
public final class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    @Override
    public boolean isLocalPlayer() {
        return Vehicles.local(this);
    }
}
