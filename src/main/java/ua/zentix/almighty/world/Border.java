package ua.zentix.almighty.world;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Где ведущий грузит чанки: внутри границы мира измерения ({@link WorldBorder}) и в кольце соседей в один чанк вокруг
 * неё — как у аренды ({@link Areas#MARGIN}): запись блока на краю читает соседей. Дальше — отказ с ошибкой, без
 * подмены места: за границей генератор строит мир, которого в карте нет, а за ±30 млн блоков генерация падает
 * (04.10.2026 скрипт ведущего поставил тикет в 37 млн блоков от центра — AIOOBE в {@code Aquifer}, сервер встал).
 * Граница ванили не шире ±29 999 984 блоков, с кольцом — ровно предел мира ({@code Level.isInWorldBounds}).
 *
 * <p>Две точки проверки. Загрузка самого моста — аренда ({@link Areas}: {@code area.prepare}, постройка, вход бота) —
 * проверяется до записи тикетов ({@link #allows}). Скрипт — любой код Java, общей точки в моде у его загрузок нет; в
 * ванили все тикеты и синхронные загрузки ({@code getChunk} ставит тикет {@code UNKNOWN}) сходятся в
 * {@code DistanceManager.addTicket}: {@code DistanceManagerMixin} спрашивает {@link #ticket} до записи тикета, и,
 * пока в этом потоке идёт скрипт ведущего ({@link #guard}), чанк за пределом — исключение {@link OutsideBorder}.
 * Команды проверяет сама игра ({@code /tp}, {@code /forceload} и {@code /summon} не идут за предел мира ±30 млн
 * блоков), и внутри них проверка снята ({@code CommandRunner}): ванильные команды сперва меняют состояние, потом
 * грузят чанк, и отказ на загрузке оставил бы их сделанными наполовину. Так же устроены и вызовы, которые скрипт может
 * сделать сам ({@code ServerLevel.setChunkForced}, {@code Entity.teleportTo}): переносить и держать чанки — командами.
 * Игроков, другие моды и отложенную работу (тикеты игроков, генерацию) проверка не касается.
 */
public final class Border {
    /** Проверять ли тикеты в этом потоке: да — пока идёт скрипт ведущего, нет — внутри его команд и вне скриптов. */
    private static final ThreadLocal<Boolean> GUARDED = new ThreadLocal<>();

    private Border() {}

    /**
     * Включить ({@code on}) или снять проверку тикетов в этом потоке; вернуть прежнее состояние — {@link #restore} с
     * тем, что вернул этот вызов (в {@code finally}).
     */
    public static Boolean guard(boolean on) {
        Boolean outer = GUARDED.get();
        GUARDED.set(on);
        return outer;
    }

    public static void restore(Boolean outer) {
        if (outer == null) GUARDED.remove();
        else GUARDED.set(outer);
    }

    /** Тикет кода ведущего за пределом. */
    public static final class OutsideBorder extends RuntimeException {
        OutsideBorder(String message) {
            super(message);
        }
    }

    /** Можно ли грузить чанк: в нём или в соседнем чанке есть блоки внутри границы мира. */
    public static boolean allows(ServerLevel level, int chunkX, int chunkZ) {
        return allows(level.getWorldBorder(), chunkX, chunkZ);
    }

    static boolean allows(WorldBorder border, int chunkX, int chunkZ) {
        // блок чанка, ближайший к центру границы (центр, прижатый к чанку): если внутри он, то и чанк задевает границу
        double x = Mth.clamp(border.getCenterX(), SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkX, 15));
        double z = Mth.clamp(border.getCenterZ(), SectionPos.sectionToBlockCoord(chunkZ), SectionPos.sectionToBlockCoord(chunkZ, 15));
        return border.isWithinBounds(x, z, SectionPos.sectionToBlockCoord(Areas.MARGIN));
    }

    /**
     * Из {@code DistanceManagerMixin}: тикет уровня {@code ticketLevel} на чанк {@code chunk} — до того, как он записан.
     * Тикет, который ничего нового не грузит, — можно и за пределом: чанк уже полный, тикет не сильнее полного
     * ({@code getChunk} уже загруженного чанка ставит такой {@code UNKNOWN}; чтение там, где стоит игрок за границей).
     */
    public static void ticket(DistanceManager distances, long chunk, int ticketLevel) {
        if (!Boolean.TRUE.equals(GUARDED.get())) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().chunkMap.getDistanceManager() != distances) continue;
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            boolean loaded = ticketLevel >= ChunkLevel.byStatus(FullChunkStatus.FULL) && level.getChunkSource().getChunkNow(x, z) != null;
            if (!loaded && !allows(level, x, z)) {
                throw new OutsideBorder("чанк [" + x + ", " + z + "] (от блока x " + SectionPos.sectionToBlockCoord(x)
                        + ", z " + SectionPos.sectionToBlockCoord(z) + ") за границей мира " + limits(level));
            }
            return;
        }
    }

    /** Измерение и его граница в блоках — для ошибки. */
    public static String limits(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        // блок внутри границы, если его угол >= min и < max
        return level.dimension().location() + " (блоки x " + Mth.ceil(border.getMinX()) + "…" + (Mth.ceil(border.getMaxX()) - 1)
                + ", z " + Mth.ceil(border.getMinZ()) + "…" + (Mth.ceil(border.getMaxZ()) - 1)
                + "): ведущий грузит чанки внутри неё и кольцо соседей в чанк";
    }
}
