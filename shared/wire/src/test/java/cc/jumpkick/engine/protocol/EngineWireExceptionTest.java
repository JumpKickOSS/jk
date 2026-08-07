// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code code()} is for callers to branch on ({@link #alreadyRunning}); {@code getMessage()} must
 * stay the plain human text the engine sent, with no raw wire code leaking into CLI output.
 */
class EngineWireExceptionTest {

    @Test
    void message_carries_no_wire_code_by_default() {
        EngineWireException e = EngineWireException.fromJsonLine(EngineProtocol.error("deadline", "job timed out"));

        assertThat(e.getMessage()).isEqualTo("job timed out");
        assertThat(e.code()).isEqualTo("deadline");
    }

    @Test
    void context_prefix_reads_like_the_pre_typed_error_messages() {
        EngineWireException e = EngineWireException.fromJsonLine(
                EngineProtocol.error("request-failed", "boom"), "jk engine: run failed: ");

        assertThat(e.getMessage()).isEqualTo("jk engine: run failed: boom");
    }

    @Test
    void already_running_is_a_typed_check_not_a_string_match() {
        EngineWireException running =
                EngineWireException.fromJsonLine(EngineProtocol.error(EngineProtocol.ERR_ALREADY_RUNNING, "Build #27 is already running"));
        EngineWireException other = EngineWireException.fromJsonLine(EngineProtocol.error("deadline", "timed out"));

        assertThat(running.alreadyRunning()).isTrue();
        assertThat(other.alreadyRunning()).isFalse();
    }

    @Test
    void blank_code_falls_back_to_request_failed() {
        EngineWireException e = EngineWireException.fromJsonLine(EngineProtocol.error("", "boom"));

        assertThat(e.code()).isEqualTo(EngineProtocol.ERR_REQUEST_FAILED);
    }
}
