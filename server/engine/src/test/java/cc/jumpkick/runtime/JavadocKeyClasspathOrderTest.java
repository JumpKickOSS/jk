// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The javadoc jar's key follows its classpath's order. javadoc resolves a type from the first
 * entry that holds it, as javac does, so when two jars hold one class, trading their places
 * changes the signatures and inherited members the pages show.
 */
class JavadocKeyClasspathOrderTest {

    /** Each entry's ABI token stands in as its file name: the two jars differ, the test asks only about order. */
    private static final ActionKey.EntryToken BY_NAME =
            entry -> entry.getFileName().toString();

    @Test
    void two_jars_trading_places_change_the_key(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("lib"));
        Path source = Files.createDirectories(module.resolve("src/main/java/p")).resolve("Foo.java");
        Files.writeString(source, "package p; public class Foo extends q.Base {}\n");
        Path a = tmp.resolve("a.jar");
        Path b = tmp.resolve("b.jar");
        Path jar = module.resolve("target/lib-javadoc.jar");

        String ab = key(jar, module, source, List.of(a, b));
        String ba = key(jar, module, source, List.of(b, a));

        assertThat(ab).isNotEqualTo(ba);
        assertThat(key(jar, module, source, List.of(a, b))).isEqualTo(ab);
    }

    private static String key(Path jar, Path module, Path source, List<Path> classpath) throws Exception {
        return PackagingKeys.javadoc(
                        jar, module, List.of(source), List.of(), classpath, BY_NAME, List.of("-quiet"), null, null)
                .key();
    }
}
