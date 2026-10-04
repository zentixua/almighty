package ua.zentix.almighty;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Настройки моста ведущего: {@code config/almighty-common.toml} в каталоге сервера (у каждого сервера Crafty свой).
 * Токен моста — отдельным файлом рядом ({@link ua.zentix.almighty.bridge.Token}).
 */
public final class GmConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_HOST;
    public static final ModConfigSpec.IntValue BRIDGE_PORT;
    public static final ModConfigSpec.DoubleValue WORK_MS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_CHUNKS;
    public static final ModConfigSpec.IntValue LAG_MS;
    public static final ModConfigSpec.BooleanValue SCRIPTS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> NAME;
    public static final ModConfigSpec.IntValue BOTS_MAX;
    public static final ModConfigSpec.ConfigValue<String> BOTS_MARKER;
    public static final ModConfigSpec.BooleanValue BOTS_ALLOW_DISGUISE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("bridge");
        BRIDGE_ENABLED = b.comment("HTTP-мост на выделенном сервере (встроенный сервер одиночной игры его не запускает).")
                .define("enabled", true);
        BRIDGE_HOST = b.comment("Адрес, который слушает мост. Мост даёт права оператора: только петля или сеть, куда есть",
                        "доступ лишь у хоста (например, адрес контейнера Docker, чей порт не опубликован).")
                .define("host", "127.0.0.1");
        BRIDGE_PORT = b.comment("Порт моста.").defineInRange("port", 25650, 1, 65535);
        b.pop();
        b.push("work");
        WORK_MS_PER_TICK = b.comment("Время на тик сервера для построек, откатов и снимков, мс.")
                .defineInRange("ms_per_tick", 3.0, 0.5, 20.0);
        BUILD_MAX_BLOCKS = b.comment("Наибольшее число мест в одной постройке.")
                .defineInRange("build_max_blocks", 2_000_000, 1, 16_000_000);
        MAX_CHUNKS = b.comment("Сколько чанков ведущий держит загруженными сразу (постройки и подготовленные районы вместе",
                        "с кольцом соседей).")
                .defineInRange("max_chunks", 1500, 9, 4000);
        b.pop();
        b.push("feed");
        LAG_MS = b.comment("Событие lag, когда между двумя тиками сервера прошло больше, мс.")
                .defineInRange("lag_ms", 1000, 100, 60_000);
        b.pop();
        b.push("scripts");
        SCRIPTS_ENABLED = b.comment("Скрипты ведущего (Groovy): метод script и правила со скриптом. Выключить, если правило мешает",
                        "серверу: сохранённые с миром правила со скриптом тогда не встанут, но останутся в файле (правила без скрипта — встанут).")
                .define("enabled", true);
        b.pop();
        b.push("bots");
        BOTS_MAX = b.comment("Сколько ботов — игроков, которыми управляет ведущий, — может быть на сервере сразу. Бот нагружает сервер",
                        "как игрок: держит чанки вокруг себя.")
                .defineInRange("max", 8, 0, 100);
        BOTS_MARKER = b.comment("Пометка ботов в табе и в чате перед именем. Пустая — без пометки.").define("marker", "[бот]");
        BOTS_ALLOW_DISGUISE = b.comment("Разрешить боту выглядеть как игрок этого сервера: без пометки, с именем или скином игрока, которого сервер",
                        "знает (в игре, в белом списке, оператор, заходил раньше). UUID у бота всё равно свой: настоящий игрок зайдёт, и",
                        "бот уступит ему имя. Решает владелец сервера.")
                .define("allow_disguise", false);
        b.pop();
        NAME = b.comment("Имя, под которым ведущий пишет в чат и выполняет команды.").define("name", "Claude");
        SPEC = b.build();
    }

    private GmConfig() {}

    public static long workNanosPerTick() {
        return (long) (WORK_MS_PER_TICK.get() * 1_000_000L);
    }
}
