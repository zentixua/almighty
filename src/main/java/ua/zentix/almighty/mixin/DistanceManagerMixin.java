package ua.zentix.almighty.mixin;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.almighty.world.Border;

/**
 * Тикеты скриптов ведущего — не за границей мира ({@link Border}). Скрипт — любой код Java, и общей точки у его
 * загрузок в моде нет; в ванили все тикеты ({@code addTicket}, {@code addRegionTicket}, {@code updateChunkForced},
 * тикеты игроков) и синхронные загрузки ({@code ServerChunkCache.getChunkFutureMainThread} — тикет {@code UNKNOWN})
 * сходятся сюда, а в начале метода ещё ничего не записано. Вне скрипта ведущего проверка сразу выходит: игроков и
 * другие моды не касается.
 */
@Mixin(DistanceManager.class)
abstract class DistanceManagerMixin {
    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("HEAD"))
    private void almighty$insideBorder(long chunk, Ticket<?> ticket, CallbackInfo ci) {
        Border.ticket((DistanceManager) (Object) this, chunk, ticket.getTicketLevel());
    }
}
