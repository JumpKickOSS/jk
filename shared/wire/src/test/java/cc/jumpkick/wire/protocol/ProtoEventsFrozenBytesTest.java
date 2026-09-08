// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The bytes the event factories produced before they became records, spelled out. The wire is frozen
 * pre-1.0: a record that reorders a field, changes a default or drops the leading schema is a
 * different line to every client that decodes it, and the round-trip contract cannot see that.
 */
class ProtoEventsFrozenBytesTest {
    @Test
    void plan_burst_and_preflight_lines() {
        assertThat(ProtoEvents.preflight("resolve", 2, 5, "libs"))
                .isEqualTo("{\"type\":\"preflight\",\"stage\":\"resolve\",\"done\":2,\"total\":5,\"label\":\"libs\"}");
        assertThat(ProtoEvents.invocationPhase("plan", "start"))
                .isEqualTo("{\"type\":\"invocation-phase\",\"phase\":\"plan\",\"status\":\"start\"}");
        assertThat(ProtoEvents.planModule("a/b", "g:a", "build", 7, true))
                .isEqualTo(
                        "{\"type\":\"plan-module\",\"dir\":\"a/b\",\"coord\":\"g:a\",\"planName\":\"build\",\"weight\":7,\"fullyCached\":true}");
        assertThat(ProtoEvents.planStep("a/b", "compile-java", "Compile", "compile"))
                .isEqualTo(
                        "{\"type\":\"plan-task\",\"dir\":\"a/b\",\"name\":\"compile-java\",\"label\":\"Compile\",\"stage\":\"compile\"}");
        assertThat(ProtoEvents.planDone(12)).isEqualTo("{\"type\":\"plan-done\",\"count\":12}");
        assertThat(ProtoEvents.moduleStart("a/b")).isEqualTo("{\"type\":\"module-start\",\"dir\":\"a/b\"}");
    }

    @Test
    void eta_lines_carry_millis_twice_and_the_full_wall_only_when_known() {
        assertThat(ProtoEvents.eta(1500)).isEqualTo("{\"type\":\"eta\",\"millis\":1500,\"remainingMs\":1500}");
        assertThat(ProtoEvents.eta(1500, 9000))
                .isEqualTo("{\"type\":\"eta\",\"millis\":1500,\"remainingMs\":1500,\"fullMillis\":9000}");
    }

    @Test
    void the_hot_events_lead_with_the_schema() {
        assertThat(ProtoEvents.stepStart("a/b", "compile-java", "compile", 40))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"task-start\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"stage\":\"compile\",\"ticks\":40}");
        assertThat(ProtoEvents.progress("a/b", "compile-java", 3, 50, 200, 9, 2, false))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"progress\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"delta\":3,\"numerator\":50,\"denominator\":200,\"progress\":25,\"tasksTotal\":9,\"tasksComplete\":2,\"cancelled\":false}");
        assertThat(ProtoEvents.tickUpdate("a/b", "run-tests", 1, 1, 3, 9, 8, true))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"tick-update\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"delta\":1,\"numerator\":1,\"denominator\":3,\"progress\":33.3,\"tasksTotal\":9,\"tasksComplete\":8,\"cancelled\":true}");
        assertThat(ProtoEvents.progress("a/b", "x", 0, 0, 0, 1, 0, false)).contains("\"progress\":null,");
        assertThat(ProtoEvents.label("a/b", "run-tests", "3 of 9"))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"label\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"label\":\"3 of 9\"}");
        assertThat(ProtoEvents.output("a/b", "run-tests", "he said \"hi\"\n"))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"output\",\"dir\":\"a/b\",\"task\":\"run-tests\",\"line\":\"he said \\\"hi\\\"\\n\"}");
        assertThat(ProtoEvents.planStart("a/b", "build", 0, 200, 9, 0, false))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"buildplan-start\",\"dir\":\"a/b\",\"planName\":\"build\",\"numerator\":0,\"denominator\":200,\"progress\":0,\"tasksTotal\":9,\"tasksComplete\":0,\"cancelled\":false}");
        assertThat(ProtoEvents.workspaceProgress("a/b", 3, 4, "execute", 1, 2))
                .isEqualTo(
                        "{\"schema\":1,\"type\":\"workspace-progress\",\"dir\":\"a/b\",\"numerator\":3,\"denominator\":4,\"progress\":75,\"phase\":\"execute\",\"modulesComplete\":1,\"modulesTotal\":2,\"remainingMs\":-1,\"R0\":0}");
    }

    @Test
    void task_finish_carries_wall_and_wait() {
        assertThat(ProtoEvents.stepFinish("a/b", "compile-java", "compile", "SUCCESS", 1200, 300))
                .isEqualTo(
                        "{\"type\":\"task-finish\",\"dir\":\"a/b\",\"task\":\"compile-java\",\"stage\":\"compile\",\"status\":\"SUCCESS\",\"millis\":1200,\"waitMillis\":300}");
    }
}
