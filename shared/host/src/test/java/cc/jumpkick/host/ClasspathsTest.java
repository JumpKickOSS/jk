// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three decisions the eleven private joiners disagreed on, pinned: separator, absolutising, and
 * duplicate handling. Each is asserted on its own so a future "tidy-up" that quietly starts
 * deduplicating, or stops absolutising, fails the one test that names that decision.
 */
class ClasspathsTest {

    @Test
    void entries_join_with_the_platform_separator(@TempDir Path tmp) {
        Path a = tmp.resolve("a.jar");
        Path b = tmp.resolve("b.jar");

        assertThat(Classpaths.join(List.of(a, b))).isEqualTo(a + File.pathSeparator + b);
        assertThat(Classpaths.SEPARATOR).isEqualTo(File.pathSeparator);
    }

    /** A {@code -cp} is read by a child JVM whose working directory is not necessarily this one. */
    @Test
    void relative_entries_are_absolutised() {
        String joined = Classpaths.join(List.of(Path.of("build/classes"), Path.of("lib/dep.jar")));

        for (String entry : joined.split(Pattern.quote(File.pathSeparator))) {
            assertThat(Path.of(entry)).isAbsolute();
        }
        assertThat(joined).contains(Path.of("build/classes").toAbsolutePath().toString());
    }

    /**
     * Duplicates are kept, in order. The JVM resolves a duplicated class from the first entry that
     * carries it, so silently dropping one changes which class loads.
     */
    @Test
    void duplicates_are_kept_in_order(@TempDir Path tmp) {
        Path a = tmp.resolve("a.jar");
        Path b = tmp.resolve("b.jar");

        assertThat(Classpaths.join(List.of(a, b, a))).isEqualTo(a + File.pathSeparator + b + File.pathSeparator + a);
    }

    @Test
    void an_empty_classpath_joins_to_the_empty_string() {
        assertThat(Classpaths.join(List.of())).isEmpty();
    }

    @Test
    void split_drops_blank_entries_and_is_null_safe() {
        String sep = File.pathSeparator;

        assertThat(Classpaths.split("a.jar" + sep + sep + "b.jar" + sep))
                .containsExactly(Path.of("a.jar"), Path.of("b.jar"));
        assertThat(Classpaths.split("")).isEmpty();
        assertThat(Classpaths.split("   ")).isEmpty();
        assertThat(Classpaths.split(null)).isEmpty();
    }

    @Test
    void split_round_trips_a_joined_classpath(@TempDir Path tmp) {
        List<Path> entries = List.of(tmp.resolve("a.jar"), tmp.resolve("nested/b.jar"));

        assertThat(Classpaths.split(Classpaths.join(entries))).containsExactlyElementsOf(entries);
    }

    @Test
    void a_compile_classpath_naming_a_jar_that_is_not_on_disk_is_refused_by_name(@TempDir Path tmp) throws Exception {
        Path present = Files.writeString(tmp.resolve("present.jar"), "x");
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Classpaths.requireArchivesOnDisk(List.of(present, classes, tmp.resolve("empty-classes")), "cp");

        Path gone = tmp.resolve("gone.jar");
        assertThatThrownBy(() -> Classpaths.requireArchivesOnDisk(List.of(present, gone), "the javac classpath"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the javac classpath names 1 jar(s) that are not on disk")
                .hasMessageContaining(gone.toString())
                .hasMessageContaining("jk sync");
    }
}
