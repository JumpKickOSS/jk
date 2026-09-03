// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildLogicStems;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build logic is arbitrary user code and, on this repo, the largest single step of a small build. It
 * has to be priced from measurement, only when the script exists, and only when the build will
 * actually reach its anchor.
 */
class BuildLogicEffortTest {

    private static final long TOKEN_MS = (long) EffortWeights.TOKEN * EffortWeights.MS_PER_WEIGHT;

    private static Path project(Path dir, String... stems) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                name = "m"
                group = "ex"
                version = "1.0"
                """);
        Path logic = Files.createDirectories(dir.resolve(".jk"));
        for (String stem : stems) {
            Files.writeString(logic.resolve(stem), "// script\n");
        }
        return dir;
    }

    private static TaskForecast.Module module(Path dir, TaskForecast.Task... steps) {
        return new TaskForecast.Module(dir, "ex:m", List.of(steps), 1, 1, true, false);
    }

    private static TaskForecast.Task run(String name) {
        return new TaskForecast.Task(name, TaskForecast.Status.RUN, "", null);
    }

    private static TaskForecast.Task cached(String name) {
        return new TaskForecast.Task(name, TaskForecast.Status.CACHED, "", null);
    }

    /** The point of the whole class: an anchor the build never reaches costs nothing. */
    @Test
    void an_after_compile_script_is_not_priced_when_compile_is_a_cache_hit(@TempDir Path dir) throws Exception {
        project(dir, "after-compile.kts");

        assertThat(BuildLogicEffort.moduleMillis(dir, module(dir, run(TaskNames.COMPILE_JAVA)), null))
                .as("compile runs, so the anchor is reached")
                .isEqualTo(TOKEN_MS);
        assertThat(BuildLogicEffort.moduleMillis(dir, module(dir, cached(TaskNames.COMPILE_JAVA)), null))
                .as("compile is cached, so the script never runs and must not be priced")
                .isZero();
    }

    @Test
    void a_before_package_script_follows_packaging_not_compiling(@TempDir Path dir) throws Exception {
        project(dir, "before-package.kts");

        assertThat(BuildLogicEffort.moduleMillis(
                        dir, module(dir, run(TaskNames.COMPILE_JAVA), cached(TaskNames.PACKAGE_JAR)), null))
                .as("recompiled but the jar is unchanged — packaging is skipped")
                .isZero();
        assertThat(BuildLogicEffort.moduleMillis(
                        dir, module(dir, cached(TaskNames.COMPILE_JAVA), run(TaskNames.PACKAGE_JAR)), null))
                .isEqualTo(TOKEN_MS);
    }

    /** A project with no `.jk/` is the common case and must cost nothing. */
    @Test
    void a_project_with_no_build_logic_is_free(@TempDir Path dir) throws Exception {
        project(dir);
        assertThat(BuildLogicEffort.moduleMillis(dir, module(dir, run(TaskNames.COMPILE_JAVA)), null))
                .isZero();
        assertThat(BuildLogicEffort.rootMillis(dir, null, false)).isZero();
    }

    /**
     * Discovery has to match the engine's, which runs suffixed variants too — pricing a set smaller
     * than the set that runs is the same defect as pricing one that is larger.
     */
    @Test
    void suffixed_stems_are_discovered_like_the_engine_does(@TempDir Path dir) throws Exception {
        project(dir, "before-compile-collections.kts", "after-compile.groovy");

        assertThat(BuildLogicEffort.moduleMillis(dir, module(dir, run(TaskNames.COMPILE_JAVA)), null))
                .as("both anchors reached, both cold")
                .isEqualTo(2 * TOKEN_MS);
    }

    /** Kotlin and Groovy both, anything else neither. */
    @Test
    void only_kts_and_groovy_count(@TempDir Path dir) throws Exception {
        project(dir, "after-compile.txt", "after-compile.kt");
        assertThat(BuildLogicEffort.moduleMillis(dir, module(dir, run(TaskNames.COMPILE_JAVA)), null))
                .isZero();
    }

    /** {@code gate} runs only when it was asked for; {@code after-build} whenever there is work. */
    @Test
    void the_gate_is_priced_only_when_requested(@TempDir Path dir) throws Exception {
        project(dir, "after-build.kts", "gate.kts");

        assertThat(BuildLogicEffort.rootMillis(dir, null, false)).isEqualTo(TOKEN_MS);
        assertThat(BuildLogicEffort.rootMillis(dir, null, true)).isEqualTo(2 * TOKEN_MS);
    }

    /**
     * {@code after-resources} has no task of its own — it runs inside {@code copy-resources}, so its
     * wall is already in that step's recorded wall and pricing it here would double-count it.
     */
    @Test
    void after_resources_is_not_priced_because_copy_resources_already_carries_it(@TempDir Path dir) throws Exception {
        project(dir, "after-resources.kts");
        assertThat(BuildLogicEffort.moduleMillis(
                        dir, module(dir, run(TaskNames.COMPILE_JAVA), run(TaskNames.COPY_RESOURCES)), null))
                .isZero();
    }

    /**
     * Every stem the engine recognizes reaches a pricing decision. The exhaustive switch in
     * {@code taskOf} makes a new {@link BuildLogicAnchor} a compile error rather than a script
     * silently priced at zero, and {@code BuildLogicScripts} makes a new stem without an anchor a
     * class-load error — this pins the third side: a stem the engine runs but pricing never sees.
     */
    @Test
    void every_engine_stem_is_either_priced_or_deliberately_carried_elsewhere(@TempDir Path dir) throws Exception {
        project(dir, BuildLogicStems.ALL.stream().map(s -> s + ".kts").toArray(String[]::new));
        // Every anchor reachable at once: a module that compiles and packages, asked with the gate.
        long module = BuildLogicEffort.moduleMillis(
                dir, module(dir, run(TaskNames.COMPILE_JAVA), run(TaskNames.PACKAGE_JAR)), null);
        long root = BuildLogicEffort.rootMillis(dir, null, true);

        // 4 module stems, of which after-resources is carried by copy-resources; 2 root stems.
        assertThat(module).as("before-compile + after-compile + before-package").isEqualTo(3 * TOKEN_MS);
        assertThat(root).as("after-build + gate").isEqualTo(2 * TOKEN_MS);
        assertThat(BuildLogicStems.ALL).hasSize(6);
    }
}
