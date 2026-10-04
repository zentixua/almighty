package ua.zentix.almighty.bot;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.Packet;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Конец канала бота вместо сети: всё, что сервер пишет «клиенту», забирается здесь, до кодировщика (его в канале нет).
 * Пакеты — в очередь, её разбирает бот в потоке сервера; прочее (задачи смены протокола {@code Connection}) — просто
 * принимается. Обещание записи выполняется всегда: смена протокола ждёт его ({@code syncUninterruptibly}), без него
 * поток сервера встал бы навсегда. Пишут сюда и чужие потоки (сеть модов), поэтому очередь — без блокировок.
 */
final class BotSink extends ChannelOutboundHandlerAdapter {
    /** Сервер стоит, а пакеты идут: дальше столько — старые выбрасываются, бот их уже не прочтёт. */
    static final int MAX_QUEUED = 20_000;

    private final Queue<Packet<?>> packets = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            if (msg instanceof Packet<?> packet) {
                packets.add(packet);
                if (size.incrementAndGet() > MAX_QUEUED && packets.poll() != null) size.decrementAndGet();
            }
        } finally {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }
    }

    /** Следующий пакет сервера боту; null — пусто. Только поток сервера. */
    Packet<?> poll() {
        Packet<?> p = packets.poll();
        if (p != null) size.decrementAndGet();
        return p;
    }
}
