package ua.zentix.almighty.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.platform.Setting;

import java.util.List;

/** {@link GmConfig} as a NeoForge {@link ModConfigSpec} ({@code config/almighty-common.toml}). */
final class NeoForgeConfig {
    private NeoForgeConfig() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ModConfigSpec build() {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        String open = "";
        for (Setting<?> s : GmConfig.ALL) {
            if (!s.section().equals(open)) {
                if (!open.isEmpty()) b.pop();
                if (!s.section().isEmpty()) b.push(s.section());
                open = s.section();
            }
            b.comment(s.comment().toArray(new String[0]));
            switch (s.kind()) {
                case BOOLEAN -> {
                    ModConfigSpec.BooleanValue v = b.define(s.key(), (boolean) (Boolean) s.fallback());
                    ((Setting<Boolean>) s).bind(v, v::set);
                }
                case STRING -> {
                    ModConfigSpec.ConfigValue<String> v = b.define(s.key(), (String) s.fallback());
                    ((Setting<String>) s).bind(v, v::set);
                }
                case INT -> {
                    ModConfigSpec.IntValue v = b.defineInRange(s.key(), (Integer) s.fallback(), s.min().intValue(), s.max().intValue());
                    ((Setting<Integer>) s).bind(v, v::set);
                }
                case DOUBLE -> {
                    ModConfigSpec.DoubleValue v = b.defineInRange(s.key(), (Double) s.fallback(), s.min().doubleValue(), s.max().doubleValue());
                    ((Setting<Double>) s).bind(v, v::set);
                }
                case STRING_LIST -> {
                    String fresh = s.newElement();
                    ModConfigSpec.ConfigValue<List<? extends String>> v = b.defineListAllowEmpty(s.key(),
                            (List<? extends String>) s.fallback(), () -> fresh, s.element());
                    ((Setting) s).bind(v, (java.util.function.Consumer<List<? extends String>>) v::set);
                }
            }
        }
        if (!open.isEmpty()) b.pop();
        return b.build();
    }
}
