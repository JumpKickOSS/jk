// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tool's classpath becomes a fork's {@code -cp}, so its order decides which copy of a duplicated
 * class the tool sees. It has to follow the contents, not how the filesystem enumerates them.
 */
class ToolJarsTest {

    @Test
    void the_closure_is_sorted_regardless_of_creation_order(@TempDir Path tmp) throws Exception {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path second = Files.createDirectories(tmp.resolve("second"));
        for (String jar : List.of("zeta.jar", "alpha.jar", "mid.jar")) Files.writeString(first.resolve(jar), jar);
        for (String jar : List.of("alpha.jar", "mid.jar", "zeta.jar")) Files.writeString(second.resolve(jar), jar);

        assertThat(names(ToolJars.classpath(first)))
                .containsExactly("alpha.jar", "mid.jar", "zeta.jar")
                .isEqualTo(names(ToolJars.classpath(second)));
    }

    @Test
    void nested_dirs_are_walked_and_non_jars_ignored(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("nested"));
        Files.writeString(tmp.resolve("nested/inner.jar"), "x");
        Files.writeString(tmp.resolve("top.jar"), "x");
        Files.writeString(tmp.resolve("notes.txt"), "x");
        // A directory whose name ends in .jar is not a classpath entry.
        Files.createDirectories(tmp.resolve("exploded.jar"));

        assertThat(names(ToolJars.classpath(tmp))).containsExactly("inner.jar", "top.jar");
    }

    @Test
    void a_tool_that_is_one_jar_is_its_own_classpath(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("tool.jar"), "x");
        assertThat(ToolJars.classpath(jar)).containsExactly(jar);
    }

    @Test
    void a_class_names_the_jar_or_directory_it_was_loaded_from() {
        assertThat(ToolJars.codeSource(ToolJars.class)).exists();
    }

    @Test
    void only_entries_without_a_jar_or_zip_name_are_aliased(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("lib.jar"), "jar-bytes");
        Path zip = Files.writeString(tmp.resolve("kit.zip"), "zip-bytes");
        Path bare = Files.writeString(tmp.resolve("0a1b2c3d"), "cas-bytes");
        Path aliases = tmp.resolve("aliases");

        assertThat(ToolJars.jarSuffixed(aliases, List.of(jar, zip))).containsExactly(jar, zip);
        assertThat(aliases).doesNotExist();

        List<Path> out = ToolJars.jarSuffixed(aliases, List.of(jar, bare, zip));
        assertThat(out).containsExactly(jar, aliases.resolve("rt-1-0a1b2c3d.jar"), zip);
        assertThat(Files.readString(out.get(1))).isEqualTo("cas-bytes");
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }
}
