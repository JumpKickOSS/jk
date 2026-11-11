// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.In;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step that declares {@code source-roots} is handed the module's configured roots, not a fixed
 * {@code src/main/java}: the layout's roots that exist and the {@code extra-src} ones, and its key
 * moves with what they hold.
 */
class PluginSourceRootsTest {

    private static JkBuild module(Path dir, String tail) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                name    = "app"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                """ + tail);
        return JkBuildParser.parse(toml);
    }

    @Test
    void the_roots_are_the_layouts_and_the_extra_source_dirs(@TempDir Path dir) throws Exception {
        Path main = Files.createDirectories(dir.resolve("src/main/java"));
        Path kotlin = Files.createDirectories(dir.resolve("src/main/kotlin"));
        Path generated = Files.createDirectories(dir.resolve("src/gen/java"));
        Path test = Files.createDirectories(dir.resolve("src/test/java"));
        Path testExtra = Files.createDirectories(dir.resolve("src/it/java"));
        JkBuild project = module(dir, """
                [build]
                extra-src = ["src/gen/java", "src/absent/java"]

                [test]
                extra-src = ["src/it/java"]
                """);

        PluginSourceRoots roots = PluginSourceRoots.of(dir, project);

        assertThat(roots.main()).containsExactly(main, kotlin, generated);
        assertThat(roots.test()).containsExactly(test, testExtra);
    }

    @Test
    void a_compact_module_has_its_src_root(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path test = Files.createDirectories(dir.resolve("test/src"));
        JkBuild project = module(dir, "layout = \"simple\"\n");

        PluginSourceRoots roots = PluginSourceRoots.of(dir, project);

        assertThat(roots.main()).containsExactly(src);
        assertThat(roots.test()).containsExactly(test);
    }

    @Test
    void only_a_step_that_declares_them_is_handed_them(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        JkBuild project = module(dir, "");

        assertThat(PluginSourceRoots.forInputs(List.of("classes"), dir, project))
                .isEqualTo(PluginSourceRoots.NONE);
        assertThat(PluginSourceRoots.forInputs(List.of(In.sourceRoots().wireName()), dir, project)
                        .main())
                .isNotEmpty();
    }

    @Test
    void the_key_moves_with_what_a_root_holds(@TempDir Path dir) throws Exception {
        Path main = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(main.resolve("App.java"), "class App {}");
        JkBuild project = module(dir, "");
        List<String> inputs = List.of(In.sourceRoots().wireName());

        List<String> before = PlannerPlugin.declaredInputTokens(inputs, sources(dir, project));
        Files.writeString(main.resolve("App.java"), "class App { int x; }");
        List<String> after = PlannerPlugin.declaredInputTokens(inputs, sources(dir, project));

        assertThat(before).singleElement().asString().startsWith("source-roots:main=");
        assertThat(after).isNotEqualTo(before);
    }

    private static PlannerPlugin.InputSources sources(Path dir, JkBuild project) throws Exception {
        return new PlannerPlugin.InputSources(
                Files.createDirectories(dir.resolve("target/classes")),
                List.of(),
                List.of(),
                List.of(),
                new PluginConfig("helidon", Map.of()),
                BuildLayout.of(dir, project),
                dir,
                Map.of(),
                PluginRepositories.NONE,
                false,
                PluginSourceRoots.of(dir, project));
    }
}
