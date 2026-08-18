// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The module set rides as one ordered {@code dir → name} object (JK-2168). */
class ProjectInfoModulesTest {

    @Test
    void modules_map_round_trips_aligned() {
        ProjectInfo in = ProjectInfo.decode(
                "{\"type\":\"project-info\",\"modules\":{\"/ws/libs/a\":\"lib-a\",\"/ws/app\":\"app\"}}");
        assertThat(in.moduleDirs()).containsExactly("/ws/libs/a", "/ws/app");
        assertThat(in.moduleNames()).containsExactly("lib-a", "app");

        ProjectInfo out = ProjectInfo.decode(in.encode());
        assertThat(out.moduleDirs()).isEqualTo(in.moduleDirs());
        assertThat(out.moduleNames()).isEqualTo(in.moduleNames());
    }

    @Test
    void pipe_and_quote_in_paths_survive() {
        ProjectInfo in = ProjectInfo.decode(
                "{\"type\":\"project-info\",\"modules\":{\"/ws/we|ird \\\"dir\\\"\":\"na|me\"}}");
        assertThat(in.moduleDirs()).containsExactly("/ws/we|ird \"dir\"");
        assertThat(in.moduleNames()).containsExactly("na|me");
        ProjectInfo out = ProjectInfo.decode(in.encode());
        assertThat(out.moduleDirs()).isEqualTo(in.moduleDirs());
        assertThat(out.moduleNames()).isEqualTo(in.moduleNames());
    }
}
