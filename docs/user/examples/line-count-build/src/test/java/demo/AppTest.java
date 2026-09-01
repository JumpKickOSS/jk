// SPDX-License-Identifier: Apache-2.0
package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Proves the build logic ran: the generated resource must agree with a count taken here, in
 * Java, over the same tree. A missing resource fails in {@link App#lineCount()}, a stale or
 * wrong one fails the comparison, and an empty tree fails the guard before either.
 */
class AppTest {
    /** jk runs tests with the module directory as the working directory. */
    private static final Path SRC = Path.of("src");

    @Test
    void generatedResourceCountsEveryJavaLineUnderSrc() throws IOException {
        int expected = countJavaLines();
        assertTrue(expected > 0, "no Java sources under " + SRC.toAbsolutePath());
        assertEquals(expected, App.lineCount());
    }

    private static int countJavaLines() throws IOException {
        try (Stream<Path> tree = Files.walk(SRC)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .mapToInt(AppTest::linesIn)
                    .sum();
        }
    }

    private static int linesIn(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
