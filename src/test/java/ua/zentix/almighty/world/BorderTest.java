package ua.zentix.almighty.world;

import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Предел загрузки ведущего: граница мира и кольцо соседей в один чанк. */
class BorderTest {
    /**
     * Граница ванили по умолчанию — блоки −29 999 984…29 999 983: с кольцом ровно до предела мира ±30 млн, чанк
     * падения 04.10.2026 (2322806, −9) — за ним.
     */
    @Test
    void defaultBorderReachesWorldLimit() {
        WorldBorder border = new WorldBorder();
        assertTrue(Border.allows(border, 0, 0));
        assertTrue(Border.allows(border, -1_875_000, 0), "кольцо: блоки −30 000 000…−29 999 985");
        assertFalse(Border.allows(border, -1_875_001, 0));
        assertTrue(Border.allows(border, 1_874_999, 0), "кольцо: блоки 29 999 984…29 999 999");
        assertFalse(Border.allows(border, 1_875_000, 0));
        assertTrue(Border.allows(border, 0, -1_875_000));
        assertFalse(Border.allows(border, 0, 1_875_000));
        assertFalse(Border.allows(border, 2_322_806, -9));
    }

    /** Граница не по чанкам: блоки −1000…1000 — чанки −63…62, с кольцом −64…63, по каждой оси отдельно. */
    @Test
    void smallBorderWithRing() {
        WorldBorder border = new WorldBorder();
        border.setCenter(0.5, 0.5);
        border.setSize(2001);
        assertTrue(Border.allows(border, -64, -64));
        assertTrue(Border.allows(border, 63, 63));
        assertFalse(Border.allows(border, -65, 0));
        assertFalse(Border.allows(border, 64, 0));
        assertFalse(Border.allows(border, 0, -65));
        assertFalse(Border.allows(border, 0, 64));
        assertFalse(Border.allows(border, 64, 64));
    }

    /** Граница меньше чанка внутри одного чанка: можно он и его соседи. */
    @Test
    void borderInsideOneChunk() {
        WorldBorder border = new WorldBorder();
        border.setCenter(8, 8);
        border.setSize(4);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) assertTrue(Border.allows(border, x, z), x + ", " + z);
        }
        assertFalse(Border.allows(border, 2, 0));
        assertFalse(Border.allows(border, -2, 0));
        assertFalse(Border.allows(border, 0, 2));
    }
}
