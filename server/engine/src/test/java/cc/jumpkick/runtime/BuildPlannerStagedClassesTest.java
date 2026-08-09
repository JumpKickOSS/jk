// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1659: the package staging dir is what the jar and its cache key are taken from, so a file
 * left behind by a previous build must never survive into it. JK-1658: it is staged at most once
 * per build, and never when packaging is going to be restored from cache.
 */
class BuildPlannerStagedClassesTest {

    private static final String MANIFEST = """
            [project]
            group = "com.example"
            name = "svc"
            version = "0.1.0"
            """;

    private static final String AOT_STEP = "micronaut-aot";

    /** Stands in for the plan's shared context: only the {@code put}/{@code get} stash matters. */
    private final TaskContext ctx = new StashContext();

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

        Path staged = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);

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

        Path staged = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);

        assertThat(staged.resolve("com/example/App.class")).hasContent("app");
        assertThat(staged.resolve("com/example/aot/Optimized.class")).hasContent("opt");
    }

    @Test
    void nothing_contributed_leaves_the_classes_dir_alone(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("Kept.class"), "kept");
        // The declared step exists but never ran, so its scratch dir is absent.
        cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(f.contributed);

        Path staged = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);

        assertThat(staged).isEqualTo(f.classes);
        assertThat(f.layout.moduleTargetDir().resolve("package-classes")).doesNotExist();
    }

    @Test
    void no_active_plugin_leaves_the_classes_dir_alone(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);

        assertThat(BuildPlanner.stageClassesWithContributions(ctx, f.classes, List.of(), f.layout))
                .isEqualTo(f.classes);
    }

    @Test
    void the_second_packaging_task_reuses_the_stage_instead_of_recopying(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("App.class"), "app");
        Files.writeString(f.contributed.resolve("Generated.class"), "generated");

        // package-jar stages, publishing the inputs it staged from…
        Path first = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);
        Path marker = first.resolve("App.class");
        Files.writeString(marker, "touched-by-nobody"); // a re-stage would overwrite this

        // …and assembly, running behind package-jar's requires edge, reuses it.
        Path second = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);

        assertThat(second).isEqualTo(first);
        assertThat(marker).hasContent("touched-by-nobody");
    }

    @Test
    void a_changed_input_restages_even_within_one_build(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("App.class"), "app");
        Files.writeString(f.contributed.resolve("Generated.class"), "v1");

        Path stage = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);
        assertThat(stage.resolve("Generated.class")).hasContent("v1");

        Files.writeString(f.contributed.resolve("Generated.class"), "v2");
        BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);

        assertThat(stage.resolve("Generated.class")).hasContent("v2");
    }

    @Test
    void a_deleted_stage_is_rebuilt_even_when_the_inputs_are_unchanged(@TempDir Path tmp) throws Exception {
        // The reuse check is keyed on inputs, but a `jk clean` between the two calls means there
        // is nothing to reuse — existence has to be confirmed too.
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("App.class"), "app");
        Files.writeString(f.contributed.resolve("Generated.class"), "generated");

        Path stage = BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout);
        cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(stage);

        assertThat(BuildPlanner.stageClassesWithContributions(ctx, f.classes, contributed(f), f.layout))
                .isEqualTo(stage);
        assertThat(stage.resolve("App.class")).hasContent("app");
        assertThat(stage.resolve("Generated.class")).hasContent("generated");
    }

    private static List<Path> contributed(Fixture f) {
        return BuildPlanner.existingContributedDirs(f.decls, f.layout);
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

    private static final class StashContext implements TaskContext {
        private final Map<BuildPlanKey<?>, Object> values = new HashMap<>();

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {
            values.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.ofNullable((T) values.get(key));
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            return get(key).orElseThrow(() -> new IllegalStateException("no " + key));
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additionalScope) {}

        @Override
        public void label(String description) {}

        @Override
        public void output(String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }
    }
}
