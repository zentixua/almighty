package ua.zentix.almighty.world;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import ua.zentix.almighty.script.Scripts;

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
 * пока в этом потоке идёт скрипт ведущего ({@link #guard}), тикет, чьи полные чанки за пределом, — исключение
 * {@link OutsideBorder}. Проверяется только то, что вызвал сам скрипт: задачи сервера, которые выполнило ожидание его
 * загрузки (тикеты игроков, запросы других модов из своих потоков), — нет ({@link #fromScript}).
 * Команды проверяет сама игра ({@code /tp}, {@code /forceload} и {@code /summon} не идут за предел мира ±30 млн
 * блоков), и внутри них проверка снята ({@code CommandRunner}): {@code /forceload} сперва пишет чанк в сохраняемый
 * список, {@code /tp} сущности (не игрока) — её место, и только потом грузят чанк; отказ на загрузке оставил бы их
 * сделанными наполовину. Из скрипта {@code ServerLevel.setChunkForced} за пределом откатывается здесь же
 * ({@link #unforce}), а перенос сущности ({@code teleportTo}, {@code setPos}) так и остаётся наполовину: переносить —
 * командой {@code /tp}. Игроков, другие моды и отложенную работу (генерацию, замыкания скрипта, вызванные игрой
 * потом) проверка не касается.
 */
public final class Border {
    /** Проверять ли тикеты в этом потоке: да — пока идёт скрипт ведущего, нет — внутри его команд и вне скриптов. */
    private static final ThreadLocal<Boolean> GUARDED = new ThreadLocal<>();
    private static final StackWalker STACK = StackWalker.getInstance();
    private static final String SCRIPTS = Scripts.class.getName();
    private static final String EVENT_LOOP = BlockableEventLoop.class.getName();

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
     * Из {@code DistanceManagerMixin}: тикет {@code ticket} на чанк {@code chunk} — до того, как он записан;
     * {@code held} — тикеты, которые уже стоят на этом чанке ({@code null} — нет). Тикет уровня меньше 33 грузит
     * полными и соседей (уровень растёт на 1 за чанк): проверяются все его полные чанки. Тикет, который ничего нового не
     * грузит, можно и за пределом: чанк уже держат не слабее, или он уже полный, а тикет не сильнее полного
     * ({@code getChunk} загруженного чанка ставит такой {@code UNKNOWN} мимо кэша из 4 чанков; чтение там, где стоит
     * игрок за границей).
     */
    public static void ticket(DistanceManager distances, long chunk, Ticket<?> ticket, SortedArraySet<Ticket<?>> held) {
        if (!Boolean.TRUE.equals(GUARDED.get())) return;
        int ticketLevel = ticket.getTicketLevel();
        if (held != null && !held.isEmpty() && held.first().getTicketLevel() <= ticketLevel) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().chunkMap.getDistanceManager() != distances) continue;
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            int full = ChunkLevel.byStatus(FullChunkStatus.FULL);
            int r = Math.max(0, full - ticketLevel);
            if (allows(level, x - r, z - r) && allows(level, x + r, z + r)) return;
            if (r == 0 && level.getChunkSource().getChunkNow(x, z) != null) return;
            if (!fromScript()) return;
            if (held == null || held.stream().noneMatch(t -> t.getType() == TicketType.FORCED)) unforce(level, chunk);
            throw new OutsideBorder((r == 0 ? "чанк [" + x + ", " + z + "]" : "чанки [" + (x - r) + "…" + (x + r) + ", " + (z - r) + "…" + (z + r) + "]")
                    + " (от блока x " + SectionPos.sectionToBlockCoord(x) + ", z " + SectionPos.sectionToBlockCoord(z)
                    + ") за границей мира " + limits(level));
        }
    }

    /**
     * Тикет ставит сам скрипт, а не задача сервера, которую выполнило ожидание его синхронной загрузки
     * ({@code managedBlock} в {@code getChunk} выполняет очередь чанков: отложенные тикеты игроков, запросы других
     * модов из своих потоков; отказ там терял бы чужую работу): ближе к вершине стека {@code Scripts.run}, чем
     * {@code BlockableEventLoop.doRunTask}. Стек смотрится только перед отказом.
     */
    private static boolean fromScript() {
        return STACK.walk(frames -> frames
                .filter(f -> f.getMethodName().equals("run") && f.getClassName().equals(SCRIPTS)
                        || f.getMethodName().equals("doRunTask") && f.getClassName().equals(EVENT_LOOP))
                .findFirst()
                .map(f -> f.getClassName().equals(SCRIPTS))
                .orElse(true));
    }

    /**
     * Отказ внутри {@code ServerLevel.setChunkForced}: чанк уже в сохраняемом списке, тикета {@code FORCED} на нём
     * нет — убрать: иначе он сохранился бы со следующей записью списка, и при запуске сервер грузил бы его сам
     * ({@code MinecraftServer.prepareLevels}, без проверки) — за ±30 млн сервер не запустился бы.
     */
    private static void unforce(ServerLevel level, long chunk) {
        ForcedChunksSavedData forced = level.getDataStorage().get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        if (forced != null) forced.getChunks().remove(chunk);
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
