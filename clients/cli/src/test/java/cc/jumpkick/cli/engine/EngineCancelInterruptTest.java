// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A signal to one jk cancels that process's jobs, never another client's in the same workspace. */
class EngineCancelInterruptTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkc-");

    @AfterEach
    void reset() {
        ActiveJobs.reset();
    }

    @Test
    void a_signal_cancels_each_tracked_job_by_id_and_nothing_by_directory() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        try (ScriptedEngine engine = new ScriptedEngine(
                socket,
                (type, connection) ->
                        EngineProtocol.CANCEL_REQUEST.equals(type) ? ProtoLifecycle.cancelAck(7, true, null) : null)) {
            ActiveJobs.note(7);
            EngineCancel.cancelTracked(socket);
            assertThat(engine.received(EngineProtocol.CANCEL_REQUEST)).isEqualTo(1);

            EngineCancel.cancelTracked(socket);
            assertThat(engine.received(EngineProtocol.CANCEL_REQUEST))
                    .as("with no job of its own, a signalled jk asks the engine for nothing")
                    .isEqualTo(1);
        }
    }
}
