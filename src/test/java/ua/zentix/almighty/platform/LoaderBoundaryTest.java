package ua.zentix.almighty.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The common core names no mod loader (AGENTS.md "Common core and loaders"): only a loader's own package
 * ({@code neoforge/}, {@code fabric/}) and mod compatibility ({@code compat/}) mention {@code net.neoforged} or
 * {@code net.fabricmc}, so a new loader needs only its package and the {@link Platform}.
 */
class LoaderBoundaryTest {
    private static final Path ROOT = Path.of("src/main/java/ua/zentix/almighty");
    private static final Pattern LOADER = Pattern.compile("\\bnet\\.(neoforged|fabricmc)\\.");

    @Test
    void onlyLoaderPackagesNameALoader() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "run from the project directory");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(ROOT)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String top = ROOT.relativize(p).getName(0).toString();
                        return !top.equals("neoforge") && !top.equals("fabric") && !top.equals("compat");
                    })
                    .filter(p -> {
                        try {
                            return LOADER.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(p -> ROOT.relativize(p).toString())
                    .sorted().toList();
        }
        assertEquals(List.of(), offenders);
    }
}
