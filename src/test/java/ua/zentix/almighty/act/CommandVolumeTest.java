package ua.zentix.almighty.act;

import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 4.11, F3: one call may not fill or clone more than four vanilla-sized boxes in one tick; "use build". */
class CommandVolumeTest {
    @Test
    void readsBoxSizes() {
        assertEquals(27, CommandVolume.volume("fill 0 0 0 2 2 2 stone"));
        assertEquals(27, CommandVolume.volume("minecraft:fill ~1 ~ ~1 ~3 ~2 ~3 stone"));
        assertEquals(8, CommandVolume.volume("clone from minecraft:overworld 0 0 0 1 1 1 to minecraft:the_nether 5 5 5"));
        assertEquals(CommandVolume.VANILLA_MAX, CommandVolume.volume("fill ^ ^ ^ ^5 ^5 ^5 stone"), "local: unknown");
        assertEquals(CommandVolume.VANILLA_MAX, CommandVolume.volume("fill 0 0 0 ~5 5 5 stone"), "mixed: unknown");
        assertEquals(1_000_000_000L, CommandVolume.volume("fill 0 0 0 999 999 999 stone"), "the real size (a raised gamerule)");
        assertEquals(0, CommandVolume.volume("setblock 0 0 0 stone"));
        assertEquals(0, CommandVolume.volume("say fill 0 0 0 9 9 9"));
    }

    @Test
    void refusesHugeCallsOnly() {
        List<String> house = Collections.nCopies(40, "fill 0 64 0 10 70 10 oak_planks");
        assertDoesNotThrow(() -> CommandVolume.check(house), "40 small fills (847 blocks each) are fine");
        RpcException e = assertThrows(RpcException.class,
                () -> CommandVolume.check(Collections.nCopies(10_000, "fill 0 0 0 31 31 31 stone")));
        assertTrue(e.getMessage().contains("use build"), e.getMessage());
        assertThrows(RpcException.class, () -> CommandVolume.check(List.of("execute as @a run fill 0 0 0 31 31 31 stone",
                "fill ^ ^ ^ ^1 ^1 ^1 air", "clone 0 0 0 31 31 31 100 0 0", "fill 0 0 0 31 31 31 air", "fill 0 0 0 1 1 1 air")));
    }

    /** Below owner, a write repeated per matched entity cannot be bounded from the text (stability: execute as @e). */
    @Test
    void repeatedWritesBelowOwner() {
        for (String many : List.of("execute as @e run fill ~ ~ ~ ~1 ~1 ~1 stone", "execute at @a run setblock ~ ~ ~ air",
                "execute as @e[type=zombie, limit=5] at @s run clone 0 0 0 1 1 1 5 5 5", "execute on passengers run fill 0 0 0 1 1 1 air",
                "execute positioned as @e run fillbiome ~ ~ ~ ~ ~ ~ plains", "execute as @r[limit=3] run fill 0 0 0 1 1 1 air",
                "/execute AS @E run execute run fill 0 0 0 1 1 1 air")) {
            if (many.contains("setblock")) {
                assertDoesNotThrow(() -> CommandVolume.check(List.of(many), false), "setblock writes one block: " + many);
                continue;
            }
            assertThrows(RpcException.class, () -> CommandVolume.check(List.of(many), false), many);
            assertDoesNotThrow(() -> CommandVolume.check(List.of(many), true), "the owner's counts once: " + many);
        }
        for (String one : List.of("execute as @p run fill ~ ~ ~ ~1 ~1 ~1 stone", "execute as @e[limit=1,sort=nearest] at @s run fill 0 0 0 1 1 1 air",
                "execute as Steve at @s run fill ~ ~ ~ ~1 ~1 ~1 air", "execute as @a run say hi", "execute if entity @e run fill 0 0 0 1 1 1 air",
                "execute at @s run fill 0 0 0 1 1 1 air")) {
            assertDoesNotThrow(() -> CommandVolume.check(List.of(one), false), one);
        }
    }
}
