// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guard the frozen wire field names. Renames here break every client build.
 */
class WireEnvelopeGoldenTest {

    @Test
    void hello_and_ack_carry_version_and_proto() {
        String hello = EngineProtocol.hello("0.12.0", "connect");
        assertThat(hello).contains("\"type\":\"hello\"");
        assertThat(hello).contains("\"version\":");
        assertThat(hello).contains("\"proto\":" + EngineProtocol.PROTOCOL);
        assertThat(hello).contains("\"purpose\":\"connect\"");

        String ack = EngineProtocol.helloAck("0.12.0", 1L, 100L, false, "bid");
        assertThat(ack).contains("\"type\":\"hello-ack\"");
        assertThat(ack).contains("\"startedAt\":");
        assertThat(ack).contains("\"proto\":" + EngineProtocol.PROTOCOL);
    }

    @Test
    void error_envelope_has_code_and_message() {
        String err = EngineProtocol.error(EngineProtocol.ERR_DEADLINE, "too long");
        assertThat(err).contains("\"type\":\"error\"");
        assertThat(err).contains("\"code\":\"deadline\"");
        assertThat(err).contains("\"message\":");
    }

    @Test
    void protocol_version_is_frozen_at_1() {
        assertThat(EngineProtocol.PROTOCOL).isEqualTo(1);
    }

    @Test
    void workspace_progress_carries_aggregate_fields() {
        String line = EngineProtocol.workspaceProgress("/ws", 150, 200, "execute", 1, 3);
        assertThat(line).contains("\"type\":\"workspace-progress\"");
        assertThat(line).contains("\"progress\":75");
        assertThat(line).contains("\"numerator\":150");
        assertThat(line).contains("\"denominator\":200");
        assertThat(line).contains("\"phase\":\"execute\"");
        assertThat(line).contains("\"modulesComplete\":1");
        assertThat(line).contains("\"modulesTotal\":3");
        assertThat(line).contains("\"schema\":1");
    }
}
