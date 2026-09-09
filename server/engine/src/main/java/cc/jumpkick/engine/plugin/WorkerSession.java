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

    /**
     * The detach tool, as the POSIX string {@code execve} will receive. Not a rendered {@link Path}:
     * {@code Path.toString} uses the platform separator, so on Windows the argv element and the
     * idempotence guard below would both read the Windows-shaped form instead.
     */
    private static final String SETSID = "/usr/bin/setsid";

    private static final Path SETSID_FILE = Path.of(SETSID);

    private static final boolean AVAILABLE = available(Os.isWindows(), Files::isExecutable);

    private WorkerSession() {}

    /** The command to fork: the worker under {@code setsid} when that is possible, else unchanged. */
    public static List<String> detached(List<String> command) {
        return AVAILABLE ? detached(command, true) : command;
    }

    static List<String> detached(List<String> command, boolean available) {
        if (!available || command.isEmpty() || SETSID.equals(command.getFirst())) return command;
        List<String> out = new ArrayList<>(command.size() + 1);
        out.add(SETSID);
        out.addAll(command);
        return List.copyOf(out);
    }

    static boolean available(boolean windows, Predicate<Path> executable) {
        return !windows && executable.test(SETSID_FILE);
    }
}
