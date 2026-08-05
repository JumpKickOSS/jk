// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** InvocationPhase visibility and wire names (replaces the old fixed PhaseGraph lifecycle). */
class PhaseGraphTest {

    @Test
    void user_visible_phases_are_resolve_plan_build() {
        assertThat(InvocationPhase.RESOLVE.userVisible()).isTrue();
        assertThat(InvocationPhase.PLAN.userVisible()).isTrue();
        assertThat(InvocationPhase.BUILD.userVisible()).isTrue();
        assertThat(InvocationPhase.INITIALIZE.userVisible()).isFalse();
        assertThat(InvocationPhase.TOOLCHAIN.userVisible()).isFalse();
        assertThat(InvocationPhase.FINALIZE.userVisible()).isFalse();
    }

    @Test
    void wire_names_are_lowercase() {
        assertThat(InvocationPhase.RESOLVE.wireName()).isEqualTo("resolve");
        assertThat(InvocationPhase.fromWire("build")).isEqualTo(InvocationPhase.BUILD);
    }
}
