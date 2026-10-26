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
 * Bot channel endpoint instead of a network: consumes everything the server writes to the "client", before
 * the encoder (absent from the channel). Packets enter a queue read by the bot on the server thread; other
 * messages ({@code Connection} protocol change tasks) are simply accepted. Always complete the write promise:
 * protocol changes wait for it ({@code syncUninterruptibly}); otherwise the server thread would block forever.
 * Other threads (mod networking) also write here, so the queue is lock-free.
 */
final class BotSink extends ChannelOutboundHandlerAdapter {
    /** The server stalls while packets arrive: above this limit, discard oldest packets the bot will no longer read. */
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

    /** Next server packet for the bot; null if empty. Server thread only. */
    Packet<?> poll() {
        Packet<?> p = packets.poll();
        if (p != null) size.decrementAndGet();
        return p;
    }
}
