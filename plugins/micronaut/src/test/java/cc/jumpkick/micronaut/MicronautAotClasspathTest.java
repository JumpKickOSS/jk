// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tool closure becomes both {@code --classpath} and the forked JVM's {@code -cp}, so
 * its order decides which copy of a duplicated class AOT sees. It has to be a function of the
 * contents, not of how the filesystem happens to enumerate them.
 */
class MicronautAotClasspathTest {

    @Test
    void the_closure_is_sorted_regardless_of_creation_order(@TempDir Path tmp) throws Exception {
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        for (String jar : List.of("zeta.jar", "alpha.jar", "mid.jar")) {
            Files.writeString(first.resolve(jar), jar);
        }
        for (String jar : List.of("alpha.jar", "mid.jar", "zeta.jar")) {
            Files.writeString(second.resolve(jar), jar);
        }

        assertThat(names(MicronautPlugin.jarsIn(first)))
                .containsExactly("alpha.jar", "mid.jar", "zeta.jar")
                .isEqualTo(names(MicronautPlugin.jarsIn(second)));
    }

    @Test
    void nested_dirs_are_walked_and_non_jars_ignored(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("nested"));
        Files.writeString(tmp.resolve("nested/inner.jar"), "x");
        Files.writeString(tmp.resolve("top.jar"), "x");
        Files.writeString(tmp.resolve("notes.txt"), "x");
        // A directory whose name ends in .jar is not a classpath entry.
        Files.createDirectories(tmp.resolve("exploded.jar"));

        assertThat(names(MicronautPlugin.jarsIn(tmp))).containsExactlyInAnyOrder("inner.jar", "top.jar");
    }

    @Test
    void a_missing_classpath_entry_fails_instead_of_shortening_the_classpath(@TempDir Path tmp) throws Exception {
        Path present = Files.writeString(tmp.resolve("present.jar"), "x");
        Path absent = tmp.resolve("absent.jar");

        assertThatThrownBy(() -> MicronautPlugin.joinCp(List.of(present, absent)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("absent.jar");
    }

    @Test
    void entries_join_with_the_platform_separator(@TempDir Path tmp) throws Exception {
        Path a = Files.writeString(tmp.resolve("a.jar"), "x");
        Path b = Files.writeString(tmp.resolve("b.jar"), "x");

        assertThat(MicronautPlugin.joinCp(List.of(a, b)))
                .isEqualTo(a.toAbsolutePath().normalize()
                        + System.getProperty("path.separator", ":")
                        + b.toAbsolutePath().normalize());
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }
}
