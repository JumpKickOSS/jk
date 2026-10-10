// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Root discovery over both layouts, every suite, the guard suite, fixtures, and the layout key. */
class JkSourceRootsTest {

    @TempDir
    Path tmp;

    @Test
    void a_simple_module_lists_main_default_suite_and_named_suites() throws IOException {
        AcmeFixture acme = AcmeFixture.create(tmp);
        List<JkSourceRoots.Root> roots = JkSourceRoots.of(acme.ws.resolve("core"), acme.ws);
        assertThat(roots)
                .isEqualTo(List.of(
                        new JkSourceRoots.Root("src", JkSourceRoots.Kind.SOURCE),
                        new JkSourceRoots.Root("resources", JkSourceRoots.Kind.RESOURCE),
                        new JkSourceRoots.Root("test/src", JkSourceRoots.Kind.TEST),
                        new JkSourceRoots.Root("test/resources", JkSourceRoots.Kind.TEST_RESOURCE),
                        new JkSourceRoots.Root("integration/src", JkSourceRoots.Kind.TEST)));
    }

    @Test
    void a_traditional_module_lists_maven_dirs_and_the_guard_suite() throws IOException {
        AcmeFixture acme = AcmeFixture.create(tmp);
        List<JkSourceRoots.Root> roots = JkSourceRoots.of(acme.ws.resolve("app"), acme.ws);
        assertThat(roots)
                .isEqualTo(List.of(
                        new JkSourceRoots.Root("src/main/java", JkSourceRoots.Kind.SOURCE),
                        new JkSourceRoots.Root("src/main/resources", JkSourceRoots.Kind.RESOURCE),
                        new JkSourceRoots.Root("src/test/java", JkSourceRoots.Kind.TEST),
                        new JkSourceRoots.Root("src/guard/java", JkSourceRoots.Kind.TEST)));
    }

    @Test
    void fixtures_kotlin_and_a_named_suite_with_resources_are_test_roots() throws IOException {
        Path mod = Files.createDirectories(tmp.resolve("mod"));
        touch(mod.resolve("src/main/java/A.java"));
        touch(mod.resolve("src/main/kotlin/B.kt"));
        touch(mod.resolve("src/fixtures/java/F.java"));
        touch(mod.resolve("src/integration/kotlin/IT.kt"));
        touch(mod.resolve("src/integration/resources/it.txt"));
        Files.createDirectories(mod.resolve("src/empty/java")); // a suite dir without sources is not a suite
        Files.createDirectories(mod.resolve("src/Demo/java"));
        touch(mod.resolve("src/Demo/java/D.java")); // capitalized: not a legal suite name
        assertThat(JkSourceRoots.of(mod, mod))
                .isEqualTo(List.of(
                        new JkSourceRoots.Root("src/main/java", JkSourceRoots.Kind.SOURCE),
                        new JkSourceRoots.Root("src/main/kotlin", JkSourceRoots.Kind.SOURCE),
                        new JkSourceRoots.Root("src/fixtures/java", JkSourceRoots.Kind.TEST),
                        new JkSourceRoots.Root("src/integration/kotlin", JkSourceRoots.Kind.TEST),
                        new JkSourceRoots.Root("src/integration/resources", JkSourceRoots.Kind.TEST_RESOURCE)));
    }

    @Test
    void an_explicit_layout_key_beats_the_tree_and_a_member_inherits_the_roots() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), "layout = \"simple\"\n[workspace]\nmembers = [\"m\"]\n");
        Path m = Files.createDirectories(ws.resolve("m"));
        Files.writeString(m.resolve("jk.toml"), "name = \"m\"\n");
        touch(m.resolve("src/main/java/A.java")); // a Maven marker, overruled by the inherited key
        assertThat(JkSourceRoots.isCompact(m, ws)).isTrue();
        Files.writeString(m.resolve("jk.toml"), "name = \"m\"\nlayout = \"traditional\"\n");
        assertThat(JkSourceRoots.isCompact(m, ws)).isFalse();
        Files.writeString(m.resolve("jk.toml"), "name = \"m\"\n[build]\nlayout = \"simple\"\n"); // not top-level
        Files.writeString(ws.resolve("jk.toml"), "[workspace]\nmembers = [\"m\"]\n");
        assertThat(JkSourceRoots.isCompact(m, ws)).isFalse();
    }

    @Test
    void a_test_only_traditional_module_is_not_read_as_simple() throws IOException {
        Path mod = Files.createDirectories(tmp.resolve("t"));
        touch(mod.resolve("src/test/java/T.java"));
        assertThat(JkSourceRoots.of(mod, mod))
                .isEqualTo(List.of(new JkSourceRoots.Root("src/test/java", JkSourceRoots.Kind.TEST)));
    }

    @Test
    void reserved_top_level_dirs_are_never_simple_suites() throws IOException {
        Path mod = Files.createDirectories(tmp.resolve("s"));
        touch(mod.resolve("src/A.java"));
        touch(mod.resolve("docs/src/D.java"));
        touch(mod.resolve("fixtures/src/F.java"));
        touch(mod.resolve("smoke/src/S.java"));
        assertThat(JkSourceRoots.discoverSuites(mod, true)).isEqualTo(List.of("smoke"));
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }
}
