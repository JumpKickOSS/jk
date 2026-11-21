// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.PlannerPlugin.SiblingRead;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A plugin step that reads its siblings' artifacts waits for them, whichever window it runs in: the
 * schedule admits a module once its siblings have compiled, before they package, so a step that ran
 * ahead of the compile and read a sibling's AAR would read none, or the last build's.
 */
class PluginSiblingWaitTest {

    private static TaskDecl decl(String name, List<String> inputs, List<String> sources, List<String> testCp) {
        return new TaskDecl(
                name, List.of(), inputs, List.of(), List.of(), List.of(), sources, List.of(), testCp, List.of(), null,
                null, false, null);
    }

    @Test
    void a_step_before_the_compile_that_reads_the_runtime_entries_waits_for_the_siblings_to_package() {
        TaskDecl resDeps = decl("android-res-deps", List.of("runtime-entries"), List.of(), List.of());
        TaskDecl manifest = decl("android-manifest", List.of("project-files", "runtime-entries"), List.of(), List.of());
        TaskDecl res = decl("android-res", List.of("project-files", "runtime-entries"), List.of("gen"), List.of());

        for (TaskDecl step : List.of(resDeps, manifest, res)) {
            assertThat(PlannerPlugin.beforeCompile(step)).as(step.name()).isTrue();
            assertThat(PlannerPlugin.siblingRead(step)).as(step.name()).isEqualTo(SiblingRead.ARTIFACTS);
        }
    }

    @Test
    void a_step_before_the_compile_that_reads_the_test_runtime_entries_waits_for_the_built_siblings() {
        TaskDecl gen = decl("test-gen", List.of("test-runtime-entries"), List.of("gen"), List.of());

        assertThat(PlannerPlugin.beforeCompile(gen)).isTrue();
        assertThat(PlannerPlugin.siblingRead(gen)).isEqualTo(SiblingRead.ARTIFACTS);
    }

    @Test
    void a_source_generator_that_reads_no_entries_does_not_wait() {
        TaskDecl gen = decl("buildconfig", List.of("config"), List.of("gen"), List.of());

        assertThat(PlannerPlugin.siblingRead(gen)).isEqualTo(SiblingRead.NONE);
    }

    @Test
    void a_step_after_the_compile_waits_for_the_runtime_its_stage_reads() {
        TaskDecl dex = decl("android-dex", List.of("classes", "runtime-entries", "config"), List.of(), List.of());
        TaskDecl testConfig = decl(
                "android-test-config", List.of("test-runtime-entries", "step:android-res"), List.of(), List.of("cp"));

        assertThat(PlannerPlugin.siblingRead(dex)).isEqualTo(SiblingRead.ARTIFACTS);
        assertThat(PlannerPlugin.siblingRead(testConfig)).isEqualTo(SiblingRead.TEST_RUNTIME);
    }
}
