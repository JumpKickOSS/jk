// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.ActiveJobs;
import cc.jumpkick.cli.engine.ScriptedEngine;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A slow IDE pre-sync gives up on its own job; the engine every other client shares stays up. */
class IdeSyncTimeBoxTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jki-");

    @Test
    void a_sync_past_its_time_box_cancels_its_own_job_and_never_stops_the_engine() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        CountDownLatch held = new CountDownLatch(1);
        try (ScriptedEngine engine = new ScriptedEngine(
                socket,
                (type, connection) ->
                        EngineProtocol.CANCEL_REQUEST.equals(type) ? ProtoLifecycle.cancelAck(41, true, null) : null)) {
            String warning = IdeSupport.timeBoxed(
                    () -> {
                        ActiveJobs.note(41);
                        held.await();
                    },
                    200,
                    socket);
            held.countDown();
            ActiveJobs.forget(41);

            assertThat(warning).startsWith("dependency sync timed out");
            assertThat(engine.received(EngineProtocol.CANCEL_REQUEST)).isEqualTo(1);
            assertThat(engine.received(EngineProtocol.SHUTDOWN)).isZero();
        }
    }

    @Test
    void a_sync_that_finishes_in_time_warns_nothing() throws Exception {
        assertThat(IdeSupport.timeBoxed(() -> {}, 5_000, tempDirs.create().resolve("gen1.sock")))
                .isNull();
    }
}
