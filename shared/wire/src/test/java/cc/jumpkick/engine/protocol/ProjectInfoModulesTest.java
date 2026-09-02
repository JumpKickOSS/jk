// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The module set rides as one ordered {@code dir → name} object. */
class ProjectInfoModulesTest {

    /** Wrap a body in a real {@code project-info-ack} line — the only shape the engine writes. */
    private static String ack(String body) {
        return "{\"type\":\"" + EngineProtocol.PROJECT_INFO_ACK + "\"," + body + "}";
    }

    @Test
    void modules_map_round_trips_aligned() {
        ProjectInfo in = ProjectInfo.decode(ack("\"modules\":{\"/ws/libs/a\":\"lib-a\",\"/ws/app\":\"app\"}"));
        assertThat(in.moduleDirs()).containsExactly("/ws/libs/a", "/ws/app");
        assertThat(in.moduleNames()).containsExactly("lib-a", "app");

        String reEncoded = in.encode();
        assertThat(EngineProtocol.typeOf(reEncoded)).isEqualTo(EngineProtocol.PROJECT_INFO_ACK);
        ProjectInfo out = ProjectInfo.decode(reEncoded);
        assertThat(out.moduleDirs()).isEqualTo(in.moduleDirs());
        assertThat(out.moduleNames()).isEqualTo(in.moduleNames());
    }

    @Test
    void pipe_and_quote_in_paths_survive() {
        ProjectInfo in = ProjectInfo.decode(ack("\"modules\":{\"/ws/we|ird \\\"dir\\\"\":\"na|me\"}"));
        assertThat(in.moduleDirs()).containsExactly("/ws/we|ird \"dir\"");
        assertThat(in.moduleNames()).containsExactly("na|me");

        ProjectInfo out = ProjectInfo.decode(in.encode());
        assertThat(out.moduleDirs()).isEqualTo(in.moduleDirs());
        assertThat(out.moduleNames()).isEqualTo(in.moduleNames());
    }
}
