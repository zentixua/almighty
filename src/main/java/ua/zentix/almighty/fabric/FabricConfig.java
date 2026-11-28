package ua.zentix.almighty.fabric;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.platform.Setting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link GmConfig} as {@code config/almighty-common.toml} on Fabric, the same file NeoForge writes: sections, keys,
 * comments and ranges from the declared {@link Setting}s. Missing or invalid values get their default and the file is
 * rewritten; values are read once at load (as NeoForge does for common config) and {@link Setting#set} saves.
 */
final class FabricConfig {
    private static final Logger LOG = LoggerFactory.getLogger("almighty");

    private FabricConfig() {}

    /** One loaded setting: where it is in the file and the value the game reads. */
    private record Bound(Setting<?> setting, List<String> path, java.util.concurrent.atomic.AtomicReference<Object> cell) {}

    private static CommentedFileConfig file;
    private static final List<Bound> BOUND = new ArrayList<>();
    private static long modified;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void load(Path file) {
        CommentedFileConfig config = CommentedFileConfig.builder(file).sync().preserveInsertionOrder().build();
        config.load();
        FabricConfig.file = config;
        boolean changed = false;
        for (Setting<?> s : GmConfig.ALL) {
            List<String> path = path(s);
            Object raw = config.get(path);
            Object value = valid(s, raw);
            if (value == null) {
                value = s.fallback();
                config.set(path, value);
                changed = true;
                if (raw != null) LOG.warn("config/almighty-common.toml: {} = {} is invalid; using {}", String.join(".", path), raw, value);
            }
            String comment = comment(s);
            if (!comment.equals(config.getComment(path))) {
                config.setComment(path, comment);
                changed = true;
            }
            java.util.concurrent.atomic.AtomicReference<Object> cell = new java.util.concurrent.atomic.AtomicReference<>(
                    s.kind() == Setting.Kind.STRING_LIST ? List.copyOf((List<String>) value) : value);
            BOUND.add(new Bound(s, path, cell));
            ((Setting) s).bind(cell::get, v -> {
                cell.set(v);
                synchronized (config) {
                    config.set(path, v instanceof List<?> l ? new ArrayList<>(l) : v);
                    config.save();
                }
            });
        }
        if (changed) config.save();
        modified = lastModified();
    }

    private static long lastModified() {
        try {
            return java.nio.file.Files.getLastModifiedTime(file.getNioPath()).toMillis();
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Server thread, now and then: an owner's edit of the file applies without a restart, as NeoForge's config watcher
     * does (the emergency switch {@code scripts.enabled} included). An invalid value keeps the current one.
     */
    static void reloadIfChanged() {
        if (file == null) return;
        long now = lastModified();
        if (now == modified) return;
        modified = now;
        synchronized (file) {
            // A file with a mistake (or caught half-written) keeps the current values; never an exception in the tick.
            try {
                file.load();
            } catch (RuntimeException e) {
                LOG.error("config/almighty-common.toml cannot be read ({}); keeping the current settings until it is fixed", e.toString());
                return;
            }
            for (Bound b : BOUND) {
                Object raw = file.get(b.path());
                Object value = valid(b.setting(), raw);
                if (value == null) {
                    LOG.warn("config/almighty-common.toml: {} = {} is invalid; keeping {}", String.join(".", b.path()), raw, b.cell().get());
                    continue;
                }
                b.cell().set(value instanceof List<?> l ? List.copyOf(l) : value);
            }
        }
        LOG.info("config/almighty-common.toml reloaded");
    }

    private static List<String> path(Setting<?> s) {
        return s.section().isEmpty() ? List.of(s.key()) : List.of(s.section(), s.key());
    }

    private static String comment(Setting<?> s) {
        String text = " " + String.join("\n ", s.comment());
        return switch (s.kind()) {
            case INT, DOUBLE -> text + "\n Range: " + s.min() + " ~ " + s.max();
            default -> text;
        };
    }

    /** The value if it fits the setting (type and range), else null. */
    private static Object valid(Setting<?> s, Object raw) {
        if (raw == null) return null;
        return switch (s.kind()) {
            case BOOLEAN -> raw instanceof Boolean ? raw : null;
            case STRING -> raw instanceof String ? raw : null;
            case INT -> raw instanceof Number n && n.doubleValue() == n.intValue()
                    && n.intValue() >= s.min().intValue() && n.intValue() <= s.max().intValue() ? n.intValue() : null;
            case DOUBLE -> raw instanceof Number n && n.doubleValue() >= s.min().doubleValue() && n.doubleValue() <= s.max().doubleValue()
                    ? n.doubleValue() : null;
            case STRING_LIST -> raw instanceof List<?> l && l.stream().allMatch(s.element()) ? l : null;
        };
    }
}
