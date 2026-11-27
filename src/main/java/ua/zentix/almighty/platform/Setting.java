package ua.zentix.almighty.platform;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One value of {@code config/almighty-common.toml}, declared once in {@link ua.zentix.almighty.GmConfig} and bound by
 * the loader to its own config system ({@link #bind}). Until bound (unit tests) it holds its default.
 */
public final class Setting<T> {
    public enum Kind { BOOLEAN, STRING, INT, DOUBLE, STRING_LIST }

    private final Kind kind;
    private final String section;
    private final String key;
    private final T fallback;
    private final Number min;
    private final Number max;
    private final Predicate<Object> element;
    private final String newElement;
    private final List<String> comment;
    private volatile Supplier<T> source;
    private volatile Consumer<T> sink;
    private volatile T override;

    Setting(Kind kind, String section, String key, T fallback, Number min, Number max, Predicate<Object> element, String newElement, List<String> comment) {
        this.kind = kind;
        this.section = section;
        this.key = key;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
        this.element = element;
        this.newElement = newElement;
        this.comment = List.copyOf(comment);
    }

    public T get() {
        T o = override;
        if (o != null) return o;
        Supplier<T> s = source;
        return s == null ? fallback : s.get();
    }

    /** Change the value (GameTest); saved through the loader's config when bound. */
    public void set(T value) {
        Consumer<T> k = sink;
        if (k != null) k.accept(value);
        else override = value;
    }

    /** The loader's config value behind this setting. */
    public void bind(Supplier<T> source, Consumer<T> sink) {
        this.source = source;
        this.sink = sink;
        this.override = null;
    }

    public Kind kind() { return kind; }
    public String section() { return section; }
    public String key() { return key; }
    public T fallback() { return fallback; }
    public Number min() { return min; }
    public Number max() { return max; }
    /** For {@link Kind#STRING_LIST}: whether an element is valid. */
    public Predicate<Object> element() { return element; }
    /** For {@link Kind#STRING_LIST}: the value a config editor offers for a new element. */
    public String newElement() { return newElement; }
    public List<String> comment() { return comment; }

    /** Declares settings in file order; sections in {@link #section}. */
    public static final class Declarer {
        private final List<Setting<?>> all = new java.util.ArrayList<>();
        private String section = "";

        public Declarer section(String name) {
            section = name;
            return this;
        }

        private <T> Setting<T> add(Setting<T> s) {
            all.add(s);
            return s;
        }

        public Setting<Boolean> bool(String key, boolean def, String... comment) {
            return add(new Setting<>(Kind.BOOLEAN, section, key, def, null, null, null, null, List.of(comment)));
        }

        public Setting<String> string(String key, String def, String... comment) {
            return add(new Setting<>(Kind.STRING, section, key, def, null, null, null, null, List.of(comment)));
        }

        public Setting<Integer> integer(String key, int def, int min, int max, String... comment) {
            return add(new Setting<>(Kind.INT, section, key, def, min, max, null, null, List.of(comment)));
        }

        public Setting<Double> real(String key, double def, double min, double max, String... comment) {
            return add(new Setting<>(Kind.DOUBLE, section, key, def, min, max, null, null, List.of(comment)));
        }

        public Setting<List<? extends String>> strings(String key, String newElement, Predicate<Object> element, String... comment) {
            return add(new Setting<>(Kind.STRING_LIST, section, key, List.of(), null, null, element, newElement, List.of(comment)));
        }

        public List<Setting<?>> all() {
            return List.copyOf(all);
        }
    }
}
