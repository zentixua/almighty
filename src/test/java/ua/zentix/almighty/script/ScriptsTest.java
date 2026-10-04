package ua.zentix.almighty.script;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Срок скрипта идёт только во время его вызова. На Zearth скрипт отдал замыкание игре сравнением тикетов
 * ({@code TicketType.create}), игра позвала его после срока — и мир упал на {@code TimeoutException}.
 */
class ScriptsTest {
    private static final long TIMEOUT_MS = 50;

    private static Scripts.Compiled compile(String code) throws Scripts.CompileError {
        return Scripts.compile("test", code, TIMEOUT_MS);
    }

    @Test
    void closureHandedToGameWorksAfterTheRun() throws Exception {
        try (Scripts.Compiled script = compile("{ a, b -> for (int i = 0; i < 3; i++) {}; a <=> b } as Comparator")) {
            Scripts.Result result = Scripts.run(script, Map.of());
            assertTrue(result.ok(), result.message());
            @SuppressWarnings("unchecked")
            Comparator<Object> order = (Comparator<Object>) result.value();
            Thread.sleep(3 * TIMEOUT_MS);
            assertEquals(-1, Integer.signum(order.compare(1, 2)));
        }
    }

    @Test
    void scriptObjectWorksAfterTheRun() throws Exception {
        try (Scripts.Compiled script = compile("""
                class Counter { int n; int next() { for (int i = 0; i < 3; i++) n++; n } }
                new Counter()""")) {
            Scripts.Result result = Scripts.run(script, Map.of());
            assertTrue(result.ok(), result.message());
            Thread.sleep(3 * TIMEOUT_MS);
            assertEquals(3, InvokerHelper.invokeMethod(result.value(), "next", null));
        }
    }

    @Test
    void runningScriptIsStillInterrupted() throws Exception {
        for (String code : new String[] {
                "while (true) {}",
                "[1].each { while (true) {} }",
                "def spin() { spin() }\nwhile (true) { try { spin() } catch (StackOverflowError e) {} }",
                "class Spin { void spin() { while (true) {} } }\nnew Spin().spin()",
        }) {
            try (Scripts.Compiled script = compile(code)) {
                Scripts.Result result = Scripts.run(script, Map.of());
                assertTrue(result.timedOut(), code + " → " + result.message());
            }
        }
        assertFalse(Scripts.expired(), "после вызова срока нет");
    }

    /** Правило, сработавшее от команды скрипта, идёт со своим сроком; после него снова действует срок внешнего. */
    @Test
    void nestedRunHasItsOwnDeadline() throws Exception {
        try (Scripts.Compiled inner = compile("1 + 1");
             Scripts.Compiled outer = compile("assert nested.get().ok()\nwhile (true) {}")) {
            Supplier<Scripts.Result> nested = () -> Scripts.run(inner, Map.of());
            Scripts.Result result = Scripts.run(outer, Map.of("nested", nested));
            assertTrue(result.timedOut(), result.message());
        }
        try (Scripts.Compiled inner = compile("for (int i = 0; i < 3; i++) {}\n1 + 1");
             Scripts.Compiled outer = compile("pause.run()\nnested.get().ok()")) {
            // внешний срок вышел в коде Java (его срок не прерывает), вложенный запуск — со своим сроком и успевает
            Runnable pause = () -> {
                try {
                    Thread.sleep(2 * TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Supplier<Scripts.Result> nested = () -> Scripts.run(inner, Map.of());
            Scripts.Result result = Scripts.run(outer, Map.of("pause", pause, "nested", nested));
            assertTrue(result.ok(), result.message());
            assertEquals(true, result.value());
        }
        assertFalse(Scripts.expired(), "после вызова срока нет");
    }
}
