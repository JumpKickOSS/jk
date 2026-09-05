// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Forked workers run in their own session, with no controlling terminal. A worker inherits the
 * engine's terminal otherwise, and a library that probes it — JLine building a system terminal
 * for a test that never asked for one — can block on a live or dead PTY for as long as the
 * watchdog allows. Under Gradle a worker has no terminal and every such probe fails fast; this
 * makes the self-hosted build behave the same way.
 *
 * <p>Linux only, through {@code setsid(1)}: a child that is not already a process-group leader
 * (a freshly forked JVM never is) makes {@code setsid} {@code exec} the worker in place rather than
 * fork, so the pid the caller tracks and kills is still the worker's own. Where the tool is absent
 * — macOS ships none — the command runs as it is, and the tests' own terminal-type pins remain the
 * defence.
 */
public final class WorkerSession {

    private static final Path SETSID = Path.of("/usr/bin/setsid");

    private static final boolean AVAILABLE = available(Os.isWindows(), Files::isExecutable);

    private WorkerSession() {}

    /** The command to fork: the worker under {@code setsid} when that is possible, else unchanged. */
    public static List<String> detached(List<String> command) {
        return AVAILABLE ? detached(command, true) : command;
    }

    static List<String> detached(List<String> command, boolean available) {
        if (!available || command.isEmpty() || SETSID.toString().equals(command.getFirst())) return command;
        List<String> out = new ArrayList<>(command.size() + 1);
        out.add(SETSID.toString());
        out.addAll(command);
        return List.copyOf(out);
    }

    static boolean available(boolean windows, Predicate<Path> executable) {
        return !windows && executable.test(SETSID);
    }
}
