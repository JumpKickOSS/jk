// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1659: the package staging dir is what the jar and its cache key are taken from, so a file
 * left behind by a previous build must never survive into it.
 */
class BuildPlannerStagedClassesTest {

    private static final String MANIFEST = """
            [project]
            group = "com.example"
            name = "svc"
            version = "0.1.0"
            """;

    private static final String AOT_STEP = "micronaut-aot";

    @Test
    void a_leftover_from_a_previous_build_does_not_survive_into_the_stage(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("Kept.class"), "kept");
        Files.writeString(f.contributed.resolve("Generated.class"), "generated");

        // A previous build left a class here that nothing reproduces — a rename, or an optimizer
        // that stopped emitting it.
        Path stage = f.layout.moduleTargetDir().resolve("package-classes");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("Stale.class"), "stale");

        Path staged = BuildPlanner.stageClassesWithContributions(f.classes, f.decls, f.layout);

        assertThat(staged).isEqualTo(stage);
        assertThat(stage.resolve("Stale.class")).doesNotExist();
        assertThat(stage.resolve("Kept.class")).hasContent("kept");
        assertThat(stage.resolve("Generated.class")).hasContent("generated");
    }

    @Test
    void contributed_classes_land_next_to_the_main_classes(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.createDirectories(f.classes.resolve("com/example"));
        Files.writeString(f.classes.resolve("com/example/App.class"), "app");
        Files.createDirectories(f.contributed.resolve("com/example/aot"));
        Files.writeString(f.contributed.resolve("com/example/aot/Optimized.class"), "opt");

        Path staged = BuildPlanner.stageClassesWithContributions(f.classes, f.decls, f.layout);

        assertThat(staged.resolve("com/example/App.class")).hasContent("app");
        assertThat(staged.resolve("com/example/aot/Optimized.class")).hasContent("opt");
    }

    @Test
    void nothing_contributed_leaves_the_classes_dir_alone(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("Kept.class"), "kept");
        // The declared step exists but never ran, so its scratch dir is absent.
        cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(f.contributed);

        Path staged = BuildPlanner.stageClassesWithContributions(f.classes, f.decls, f.layout);

        assertThat(staged).isEqualTo(f.classes);
        assertThat(f.layout.moduleTargetDir().resolve("package-classes")).doesNotExist();
    }

    @Test
    void no_active_plugin_leaves_the_classes_dir_alone(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);

        assertThat(BuildPlanner.stageClassesWithContributions(f.classes, null, f.layout))
                .isEqualTo(f.classes);
    }

    private record Fixture(BuildLayout layout, Path classes, Path contributed, PluginBuild.Declarations decls) {}

    private static Fixture fixture(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("svc"));
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);

        Path classes = Files.createDirectories(layout.classesDir());
        var step = new PluginBuild.TaskDecl(
                AOT_STEP,
                List.of(),
                List.of(),
                List.of("generated"),
                List.of("generated/classes"),
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
        var decls = new PluginBuild.Declarations(List.of(step), null, List.of());
        Path contributed = Files.createDirectories(
                PluginBuild.taskScratch(layout, AOT_STEP).resolve("generated/classes"));
        return new Fixture(layout, classes, contributed, decls);
    }
}
