// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import org.junit.jupiter.api.Test;

/**
 * a remote cancel must push the terminal the stream's client loop actually ends on.
 * Single-project builds register kind "build" like workspace builds, but their loop only
 * terminates on {@code plan-finish} — a {@code workspace-finish} there is a forward-compat
 * no-op, so the CLI would only see the socket close and report an engine crash.
 */
class CancelledTerminalTest {

    @Test
    void workspace_stream_gets_a_cancelled_workspace_finish() {
        String line = JobEnvelope.cancelledTerminalLine(true, "/ws");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        assertThat(Jsonl.bool(line, "cancelled", false)).isTrue();
        assertThat(Jsonl.bool(line, "success", true)).isFalse();
    }

    @Test
    void single_pipeline_stream_gets_a_cancelled_pipeline_finish() {
        String line = JobEnvelope.cancelledTerminalLine(false, "/proj");
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
        assertThat(Jsonl.bool(line, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(line, "dir")).isEqualTo("/proj");
    }

    @Test
    void a_null_dir_still_encodes() {
        String line = JobEnvelope.cancelledTerminalLine(false, null);
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
    }
}
