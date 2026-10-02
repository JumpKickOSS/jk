// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.engine.EngineJobControl;
import cc.jumpkick.terminal.Signals;
import cc.jumpkick.terminal.Terminals;

/**
 * Ctrl-Z (SIGTSTP): suspend the engine jobs this process started, hand the shell its terminal, and
 * stop; on {@code fg} take the terminal back and resume them. A suspended job starts no new step
 * and its workers are stopped, so the build waits for the user rather than running on unseen.
 */
public final class GlobalSuspend {

    private GlobalSuspend() {}

    public static void install() {
        Signals.register("TSTP", GlobalSuspend::stop);
    }

    private static void stop() {
        EngineJobControl.suspendActive();
        Terminals.suspendForStop();
        boolean stopped = Signals.raise("STOP");
        // Continued (fg), or this runtime cannot stop itself: either way the jobs go on.
        Terminals.resumeAfterStop();
        EngineJobControl.resumeActive();
        if (!stopped) System.err.println("jk: could not stop; the build continues");
    }
}
