// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildStageTest {

    @Test
    void wire_and_display_names() {
        assertThat(BuildStage.COMPILE.wireName()).isEqualTo("compile");
        assertThat(BuildStage.COMPILE.displayName()).isEqualTo("Compile");
        assertThat(BuildStage.fromWire("compile")).isEqualTo(BuildStage.COMPILE);
        assertThat(BuildStage.fromWire("custom-soup")).isEqualTo(BuildStage.OTHER);
    }

    @Test
    void of_task_name_matches_legacy_task_phases() {
        assertThat(BuildStage.ofTaskName("compile-java")).isEqualTo(BuildStage.COMPILE);
        assertThat(BuildStage.ofTaskName("run-tests")).isEqualTo(BuildStage.TEST);
        assertThat(BuildStage.ofTaskName("resolve-deps")).isEqualTo(BuildStage.RESOLVE);
        assertThat(BuildStage.ofTaskName("package-jar")).isEqualTo(BuildStage.PACKAGE);
        assertThat(BuildStage.ofTaskName("build-logic-before-package")).isEqualTo(BuildStage.PACKAGE);
        assertThat(BuildStage.ofTaskName("write-stamp-scala")).isEqualTo(BuildStage.COMPILE);
    }

    @Test
    void task_builder_stage_sets_group_wire() {
        Task t = Task.builder("compile-java").stage(BuildStage.COMPILE).build();
        assertThat(t.stage()).isEqualTo(BuildStage.COMPILE);
        assertThat(t.group()).contains("compile");
    }

    @Test
    void task_builder_infers_stage_from_name_when_unset() {
        Task t = Task.builder("compile-kotlin").build();
        assertThat(t.stage()).isEqualTo(BuildStage.COMPILE);
    }

    @Test
    void task_builder_group_string_maps_to_stage() {
        Task t = Task.builder("x").group("test").build();
        assertThat(t.stage()).isEqualTo(BuildStage.TEST);
        Task other = Task.builder("y").group("custom-soup-1234").build();
        assertThat(other.stage()).isEqualTo(BuildStage.OTHER);
        assertThat(other.group()).contains("other");
    }
}
