// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeSourceRootsTest {

    @Test
    void simple_layout_includes_integration_as_test(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "simple"
                """);
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("test"));
        Files.writeString(tmp.resolve("test/UnitTest.java"), "class UnitTest {}");
        Files.createDirectories(tmp.resolve("integration"));
        Files.writeString(tmp.resolve("integration/SlowIT.java"), "class SlowIT {}");

        List<IdeSourceRoots.Root> roots = IdeSourceRoots.of(tmp);
        assertThat(roots.stream().map(IdeSourceRoots.Root::relative)).contains("src", "test", "integration");
        assertThat(roots.stream().filter(r -> "integration".equals(r.relative())))
                .allMatch(IdeSourceRoots.Root::test);
        assertThat(IdeSourceRoots.discoveredSuites(tmp)).containsExactly("test", "integration");
    }

    @Test
    void traditional_layout_includes_src_integration_java(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "traditional"
                """);
        Path main = tmp.resolve("src/main/java");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Main.java"), "class Main {}");
        Path test = tmp.resolve("src/test/java");
        Files.createDirectories(test);
        Files.writeString(test.resolve("UTest.java"), "class UTest {}");
        Path integ = tmp.resolve("src/integration/java");
        Files.createDirectories(integ);
        Files.writeString(integ.resolve("ITest.java"), "class ITest {}");

        List<IdeSourceRoots.Root> roots = IdeSourceRoots.of(tmp);
        assertThat(roots.stream().map(IdeSourceRoots.Root::relative))
                .contains("src/main/java", "src/test/java", "src/integration/java");
        assertThat(roots.stream().filter(r -> r.relative().contains("integration")))
                .allMatch(IdeSourceRoots.Root::test);
    }

    @Test
    void traditional_groovy_module_surfaces_groovy_roots(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                groovy = "5.0.4"
                layout = "traditional"
                """);
        Path main = tmp.resolve("src/main/groovy");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Main.groovy"), "class Main {}");
        Path test = tmp.resolve("src/test/groovy");
        Files.createDirectories(test);
        Files.writeString(test.resolve("MainTest.groovy"), "class MainTest {}");

        List<IdeSourceRoots.Root> roots = IdeSourceRoots.of(tmp);
        assertThat(roots.stream().map(IdeSourceRoots.Root::relative))
                .contains("src/main/groovy", "src/test/groovy");
        assertThat(roots.stream().filter(r -> "src/test/groovy".equals(r.relative())))
                .allMatch(IdeSourceRoots.Root::test);
    }

    @Test
    void reserved_top_level_dirs_are_not_suites(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "simple"
                """);
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("docs/Note.java"), "class Note {}");
        assertThat(IdeSourceRoots.discoveredSuites(tmp)).doesNotContain("docs");
    }

    @Test
    void intellij_iml_contains_integration_root(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "simple"
                """);
        Files.createDirectories(tmp.resolve("src"));
        Files.createDirectories(tmp.resolve("test"));
        Files.writeString(tmp.resolve("test/T.java"), "class T {}");
        Files.createDirectories(tmp.resolve("integration"));
        Files.writeString(tmp.resolve("integration/I.java"), "class I {}");

        // Unit-level: IdeSourceRoots is what imlXml consumes.
        assertThat(IdeSourceRoots.of(tmp).stream().map(IdeSourceRoots.Root::relative)).contains("integration");
    }
}
