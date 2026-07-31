// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.EngineProtocol;
import org.junit.jupiter.api.Test;

/** Heartbeat protocol and env defaults. */
class JobWatchdogConfigTest {

    @Test
    void heartbeat_json_shape() {
        String line = EngineProtocol.heartbeat(12_345L);
        assertThat(line).contains("\"type\":\"heartbeat\"");
        assertThat(line).contains("\"elapsedMillis\":12345");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.HEARTBEAT);
    }

    @Test
    void deadline_error_code() {
        String line = EngineProtocol.error(EngineProtocol.ERR_DEADLINE, "too long");
        assertThat(line).contains("\"code\":\"deadline\"");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.ERROR);
    }

    @Test
    void defaults_without_env() {
        // Defaults when env vars unset (test JVM typically has none).
        assertThat(EngineServer.jobHeartbeatMs()).isEqualTo(30_000L);
        assertThat(EngineServer.jobDeadlineMs()).isEqualTo(0L);
        assertThat(EngineServer.jobDeadlineGraceMs()).isEqualTo(30_000L);
    }
}
