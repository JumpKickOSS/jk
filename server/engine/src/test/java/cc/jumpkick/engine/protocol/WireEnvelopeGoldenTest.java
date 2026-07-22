// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guard the frozen wire field names (JK-1001 / JK-1063). Renames here break every client build.
 */
class WireEnvelopeGoldenTest {

    @Test
    void hello_and_ack_carry_version_and_proto() {
        String hello = EngineProtocol.hello("0.10.1", "connect");
        assertThat(hello).contains("\"t\":\"hello\"");
        assertThat(hello).contains("\"version\":");
        assertThat(hello).contains("\"proto\":" + EngineProtocol.PROTOCOL);
        assertThat(hello).contains("\"purpose\":\"connect\"");

        String ack = EngineProtocol.helloAck("0.10.1", 1L, 100L, false, "bid");
        assertThat(ack).contains("\"t\":\"hello-ack\"");
        assertThat(ack).contains("\"startedAt\":");
        assertThat(ack).contains("\"proto\":" + EngineProtocol.PROTOCOL);
    }

    @Test
    void error_envelope_has_code_and_message() {
        String err = EngineProtocol.error(EngineProtocol.ERR_DEADLINE, "too long");
        assertThat(err).contains("\"t\":\"error\"");
        assertThat(err).contains("\"code\":\"deadline\"");
        assertThat(err).contains("\"message\":");
    }

    @Test
    void protocol_version_is_frozen_at_1() {
        assertThat(EngineProtocol.PROTOCOL).isEqualTo(1);
    }
}
