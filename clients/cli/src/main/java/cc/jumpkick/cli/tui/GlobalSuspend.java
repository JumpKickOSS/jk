// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.engine.EngineJobControl;
import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.Signals;
import cc.jumpkick.terminal.Terminals;
import java.io.IOException;

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
        boolean stopped = stopSelf();
        // Continued (fg), or this runtime cannot stop itself: either way the jobs go on.
        Terminals.resumeAfterStop();
        EngineJobControl.resumeActive();
        if (!stopped) System.err.println("jk: could not stop; the build continues");
    }

    /**
     * Stop this process with {@code SIGSTOP}, returning once it is continued. A JVM cannot raise a
     * signal it has no handler for, so a {@code kill} does it. POSIX only.
     */
    private static boolean stopSelf() {
        if (Os.isWindows()) return false;
        try {
            Process kill = new ProcessBuilder(
                            "kill",
                            "-STOP",
                            Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .start();
            kill.getInputStream().readAllBytes();
            return kill.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
