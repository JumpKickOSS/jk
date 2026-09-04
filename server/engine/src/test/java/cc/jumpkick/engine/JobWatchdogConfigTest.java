// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import org.junit.jupiter.api.Test;

/** Heartbeat and deadline wire shapes. */
class JobWatchdogConfigTest {

    @Test
    void heartbeat_json_shape() {
        String line = ProtoLifecycle.heartbeat(12_345L);
        assertThat(line).contains("\"type\":\"heartbeat\"");
        assertThat(line).contains("\"elapsedMillis\":12345");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.HEARTBEAT);
    }

    @Test
    void deadline_error_code() {
        String line = ProtoLifecycle.error(EngineProtocol.ERR_DEADLINE, "too long");
        assertThat(line).contains("\"code\":\"deadline\"");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.ERROR);
    }
}
