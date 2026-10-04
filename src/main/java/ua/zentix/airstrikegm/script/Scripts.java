package ua.zentix.airstrikegm.script;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.Script;
import groovy.transform.TimedInterrupt;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.InvokerInvocationException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Скрипты ведущего на Groovy. Компиляция — со сроком ({@link TimedInterrupt}: проверка времени в начале каждого цикла,
 * метода и замыкания), запуск — новым экземпляром: срок считается от его создания. Замыкание, пережившее свой запуск,
 * после срока бросает {@link TimeoutException} — долгоживущее делается правилами. Код Java, который скрипт зовёт
 * (ожидание, синхронная загрузка чанка), срок не прерывает.
 *
 * <p>Каждый скрипт — свой {@link GroovyClassLoader} поверх загрузчика мода: видит классы игры и всех модов, а после
 * {@link Compiled#close} его классы выгружаются.
 */
public final class Scripts {
    /** Вывод {@code println} — не больше, дальше отрезается. */
    static final int MAX_OUTPUT = 64 * 1024;
    private static final AtomicLong NEXT = new AtomicLong(1);
    /** Частые классы — без {@code import} в самом скрипте. */
    private static final String[] IMPORTS = {
            "net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.core.registries.BuiltInRegistries",
            "net.minecraft.nbt.CompoundTag", "net.minecraft.network.chat.Component", "net.minecraft.resources.ResourceLocation",
            "net.minecraft.server.level.ServerLevel", "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.entity.Entity", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
            "net.minecraft.world.level.block.Block", "net.minecraft.world.level.block.Blocks",
            "net.minecraft.world.level.block.state.BlockState", "net.minecraft.world.phys.AABB", "net.minecraft.world.phys.Vec3",
    };

    private Scripts() {}

    /** Скомпилированный скрипт: класс в своём загрузчике и срок одного запуска. */
    public record Compiled(Class<? extends Script> type, GroovyClassLoader loader, String file, long timeoutMs) implements AutoCloseable {
        /** Выгрузить классы скрипта (после последнего запуска). */
        @Override
        public void close() {
            InvokerHelper.removeClass(type);
            loader.clearCache();
            try {
                loader.close();
            } catch (IOException e) {
                // загрузчик без файлов: закрывать нечего
            }
        }
    }

    /** Ошибка разбора: текст компилятора без заголовка «startup failed». */
    public static final class CompileError extends Exception {
        CompileError(String message) {
            super(message);
        }
    }

    /** Итог запуска: значение или ошибка с номером строки скрипта, вывод {@code println}, время. */
    public record Result(Object value, Throwable error, String message, int line, String output, long nanos) {
        public boolean ok() {
            return error == null;
        }

        public boolean timedOut() {
            return error instanceof TimeoutException;
        }
    }

    /** {@code name} — имя в стеке ошибок (буквы и цифры). */
    public static Compiled compile(String name, String code, long timeoutMs) throws CompileError {
        CompilerConfiguration config = new CompilerConfiguration();
        ImportCustomizer imports = new ImportCustomizer();
        imports.addImports(IMPORTS);
        config.addCompilationCustomizers(imports, new ASTTransformationCustomizer(
                Map.of("value", timeoutMs, "unit", TimeUnit.MILLISECONDS), TimedInterrupt.class));
        GroovyClassLoader loader = new GroovyClassLoader(Scripts.class.getClassLoader(), config);
        String file = name + "_" + NEXT.getAndIncrement() + ".groovy";
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Script> type = loader.parseClass(new GroovyCodeSource(code, file, "/groovy/airstrike_gm"));
            if (!Script.class.isAssignableFrom(type)) throw new CompileError("Ожидается скрипт, а не объявление класса " + type.getName());
            return new Compiled(type, loader, file, timeoutMs);
        } catch (CompilationFailedException e) {
            closeQuietly(loader);
            String text = e.getMessage().replaceFirst("^startup failed:\\s*", "");
            throw new CompileError(text.replace(file + ": ", "строка "));
        } catch (CompileError e) {
            closeQuietly(loader);
            throw e;
        }
    }

    /**
     * Запуск в текущем потоке: переменные {@code vars} и {@code out} (вывод {@code println}). Ошибка скрипта — в
     * итоге, не исключением; нехватка памяти и прочие ошибки машины — дальше.
     */
    public static Result run(Compiled script, Map<String, Object> vars) {
        Output text = new Output();
        Binding binding = new Binding();
        vars.forEach(binding::setVariable);
        binding.setVariable("out", new PrintWriter(text, true));
        long start = System.nanoTime();
        try {
            Script instance = InvokerHelper.createScript(script.type(), binding);
            Object value = instance.run();
            return new Result(value, null, null, 0, text.toString(), System.nanoTime() - start);
        } catch (VirtualMachineError e) {
            if (!(e instanceof StackOverflowError)) throw e;
            return failed(script, e, text, start);
        } catch (Throwable e) {
            return failed(script, e, text, start);
        }
    }

    private static Result failed(Compiled script, Throwable e, Output text, long start) {
        Throwable cause = e instanceof InvokerInvocationException && e.getCause() != null ? e.getCause() : e;
        int line = 0;
        for (StackTraceElement frame : cause.getStackTrace()) {
            if (script.file().equals(frame.getFileName()) && frame.getLineNumber() > 0) {
                line = frame.getLineNumber();
                break;
            }
        }
        String message = cause instanceof TimeoutException
                ? "Скрипт шёл дольше " + script.timeoutMs() + " мс и прерван; сделанное до этого места осталось"
                : cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return new Result(null, cause, message, line, text.toString(), System.nanoTime() - start);
    }

    private static void closeQuietly(GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            // загрузчик без файлов
        }
    }

    /** Вывод скрипта с пределом {@link #MAX_OUTPUT}. */
    private static final class Output extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean cut;

        @Override
        public void write(char[] buf, int off, int len) {
            int room = MAX_OUTPUT - text.length();
            if (len > room) cut = true;
            if (room > 0) text.append(buf, off, Math.min(len, room));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return cut ? text + "\n… вывод отрезан на " + MAX_OUTPUT / 1024 + " КБ" : text.toString();
        }
    }
}
