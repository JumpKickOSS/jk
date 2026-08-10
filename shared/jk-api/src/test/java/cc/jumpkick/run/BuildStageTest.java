// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    /** JK-1613: a typo must not become the one stage that opts out of ordering. */
    @Test
    void task_builder_group_string_rejects_an_unknown_stage() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> Task.builder("y").group("custom-soup-1234"));
        Task explicit = Task.builder("y").stage(BuildStage.OTHER).build();
        assertThat(explicit.stage()).isEqualTo(BuildStage.OTHER);
        assertThat(explicit.group()).contains("other");
    }

    @Test
    void may_require_rejects_later_stages() {
        assertThat(BuildStage.COMPILE.mayRequire(BuildStage.GENERATE)).isTrue();
        assertThat(BuildStage.COMPILE.mayRequire(BuildStage.COMPILE)).isTrue();
        assertThat(BuildStage.COMPILE.mayRequire(BuildStage.TEST)).isFalse();
        assertThat(BuildStage.PACKAGE.mayRequire(BuildStage.TEST)).isTrue();
        assertThat(BuildStage.OTHER.mayRequire(BuildStage.TEST)).isTrue();
    }

    /**
     * JK-1613: an OTHER hop must not launder a backward edge. `mayRequire` says yes to both halves
     * on its own — the plan is what establishes the invariant across the graph.
     */
    @Test
    void build_plan_rejects_a_backward_edge_through_an_other_hop() {
        assertThat(BuildStage.OTHER.mayRequire(BuildStage.IMAGE)).isTrue();
        assertThat(BuildStage.PACKAGE.mayRequire(BuildStage.OTHER)).isTrue();

        Task image = Task.builder("write-image").stage(BuildStage.IMAGE).build();
        Task hop = Task.builder("bridge")
                .stage(BuildStage.OTHER)
                .requires("write-image")
                .build();
        Task pkg = Task.builder("package-jar")
                .stage(BuildStage.PACKAGE)
                .requires("bridge")
                .build();

        assertThatThrownBy(() -> BuildPlan.builder("t")
                        .addTask(image)
                        .addTask(hop)
                        .addTask(pkg)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package-jar")
                .hasMessageContaining("write-image");
    }

    /** The legal direction through the same hop stays legal. */
    @Test
    void an_other_hop_forward_through_the_pipeline_is_fine() {
        Task compile = Task.builder("compile-java").stage(BuildStage.COMPILE).build();
        Task hop = Task.builder("bridge")
                .stage(BuildStage.OTHER)
                .requires("compile-java")
                .build();
        Task image = Task.builder("write-image")
                .stage(BuildStage.IMAGE)
                .requires("bridge")
                .build();
        assertThat(BuildPlan.builder("t")
                        .addTask(compile)
                        .addTask(hop)
                        .addTask(image)
                        .build()
                        .steps())
                .hasSize(3);
    }

    /**
     * JK-1611: assembly and native are both tails of packaging, and the join over them sits at the
     * later of the two. This is the shape every Micronaut and Quarkus scaffold builds.
     */
    @Test
    void a_join_over_a_package_tail_and_a_native_tail_validates() {
        Task assembly =
                Task.builder("package-assembly").stage(BuildStage.PACKAGE).build();
        Task nativeImage = Task.builder("native-image").stage(BuildStage.NATIVE).build();
        Task join = Task.builder("deliver")
                .stage(BuildStage.NATIVE)
                .requires("package-assembly", "native-image")
                .build();
        assertThat(BuildPlan.builder("t")
                        .addTask(assembly)
                        .addTask(nativeImage)
                        .addTask(join)
                        .build()
                        .steps())
                .hasSize(3);

        Task joinTooEarly = Task.builder("deliver")
                .stage(BuildStage.PACKAGE)
                .requires("package-assembly", "native-image")
                .build();
        assertThatThrownBy(() -> BuildPlan.builder("t")
                        .addTask(assembly)
                        .addTask(nativeImage)
                        .addTask(joinTooEarly)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("later BuildStage");
    }

    /** Every stage is reachable — a constant nothing can carry is a constant that lies. */
    @Test
    void every_stage_is_carried_by_some_task() {
        for (BuildStage stage : BuildStage.values()) {
            Task t = Task.builder("t-" + stage.wireName()).stage(stage).build();
            assertThat(t.stage()).isEqualTo(stage);
        }
    }

    @Test
    void build_plan_rejects_backward_stage_edge() {
        Task compile = Task.builder("compile-java").stage(BuildStage.COMPILE).build();
        Task bad = Task.builder("early")
                .stage(BuildStage.GENERATE)
                .requires("compile-java")
                .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BuildPlan.builder("t").addTask(compile).addTask(bad).build());
    }
}
