// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.JobControlAckFrame;
import cc.jumpkick.wire.protocol.JobControlFrame;
import java.nio.file.Path;

/**
 * Suspend or resume the jobs this CLI process started: its Ctrl-Z and {@code fg}. Best-effort and
 * bounded, like the Ctrl-C cancel: it never starts an engine and never throws.
 */
public final class EngineJobControl {

    private EngineJobControl() {}

    /** Ask the engine to suspend every job this process started. Returns how many it suspended. */
    public static int suspendActive() {
        return send(true);
    }

    /** Ask the engine to resume every job this process started. Returns how many it resumed. */
    public static int resumeActive() {
        return send(false);
    }

    private static int send(boolean suspend) {
        int applied = 0;
        try {
            Path socket = EnginePaths.activeSocket(EnginePaths.current());
            for (long jid : ActiveJobs.snapshot()) {
                try {
                    var ack = EngineCancel.requestOnce(
                            socket, new JobControlFrame(jid, suspend).encode(), EngineProtocol.JOB_CONTROL_ACK);
                    if (ack.isPresent() && JobControlAckFrame.decode(ack.get()).applied()) applied++;
                } catch (Exception ignored) {
                    // best-effort: the job may have finished
                }
            }
        } catch (Throwable ignored) {
            // never throw into a signal handler
        }
        return applied;
    }
}
