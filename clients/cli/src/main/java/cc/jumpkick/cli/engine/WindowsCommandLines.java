// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Command lines of this machine's processes, on Windows.
 *
 * <p>{@link ProcessHandle}{@code .info()} cannot supply them there: Windows keeps a process's
 * command line in its PEB, and the JDK does not read it — {@code commandLine()} and
 * {@code arguments()} come back empty for every process, including this JVM's own children, while
 * {@code command()} (the executable path) is populated. So every command-line predicate is blind on
 * Windows unless the answer comes from somewhere else, and {@code jk engine status} could not see a
 * stray engine that no pid file named.
 *
 * <p>The answer here is one {@code Win32_Process} query over CIM, shared by the lookups of one
 * command and taken again for a process newer than it. It is best-effort by construction — a machine that blocks PowerShell, or is slow enough to
 * hit the timeout, yields an empty snapshot rather than an error, and callers fall back to what the
 * JDK does give them. That is affordable because the only callers are user-invoked diagnostics
 * ({@code jk engine status}, {@code jk engine stop}, the nukes), never a build path.
 */
final class WindowsCommandLines {

    /** One query serves a whole command: {@code jk engine status} enumerates the fleet three times. */
    private static volatile @Nullable Snapshot snapshot;

    /**
     * The command lines of every process the query saw. {@code takenAt} is read before the query
     * runs, so a process that started during it still counts as newer; the TTL runs from
     * {@code answeredNanos}, so a slow query does not hand back a snapshot already expired.
     */
    private record Snapshot(Map<Long, String> rows, long answeredNanos, Instant takenAt) {}

    /**
     * How long a snapshot stands. Every caller today is a short-lived CLI command, so this only
     * ever coalesces one command's repeated enumerations; a process that started after the snapshot
     * re-queries regardless, so a long-lived caller never misses a new process.
     */
    private static final long SNAPSHOT_TTL_NANOS = 5_000_000_000L;

    /** Bounded so a wedged or blocked PowerShell cannot hang a status command. */
    private static final long QUERY_TIMEOUT_SECONDS = 10;

    /**
     * Emits {@code <pid>\t<command line>} per process. UTF-8 so a path with non-ASCII does not
     * arrive mangled through the console code page.
     *
     * <p><strong>No double quotes.</strong> Java's {@code ProcessBuilder} re-quotes each argument
     * for the Windows command line and the {@code "} characters do not survive into PowerShell's
     * parser — an interpolated {@code "$($_.ProcessId)…"} arrives as a bare {@code $(…)} and the
     * whole script dies on "Unexpected token". String concatenation with {@code [char]9} needs no
     * quoting at all, so it survives the trip intact.
     */
    private static final String SCRIPT = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
            + " Get-CimInstance Win32_Process -Property ProcessId,CommandLine |"
            + " ForEach-Object { if ($_.CommandLine) { $_.ProcessId.ToString() + [char]9 + $_.CommandLine } }";

    private WindowsCommandLines() {}

    /**
     * This process's command line, or {@code ""} — off Windows, unknown, or the query failed. A pid
     * that started after the snapshot is looked up again.
     */
    static String of(long pid) {
        return new Scan().of(pid);
    }

    /**
     * Lookups over the whole process table: the first process newer than the snapshot re-queries
     * and later ones read that answer, so a busy machine's new processes cannot chain query after
     * query.
     */
    static class Scan {
        private boolean requeried;

        /** As {@link WindowsCommandLines#of}, re-querying at most once for this scan. */
        String of(long pid) {
            if (!Os.isWindows()) return "";
            Snapshot current = current();
            String cmd = current.rows().get(pid);
            if (cmd != null) return cmd;
            if (requeried || !startedAfter(pid, current.takenAt())) return "";
            requeried = true;
            return refresh(current).rows().getOrDefault(pid, "");
        }
    }

    private static Snapshot current() {
        Snapshot local = snapshot;
        if (local != null && Clock.SYSTEM.nanos() - local.answeredNanos() < SNAPSHOT_TTL_NANOS) return local;
        return refresh(local);
    }

    /** Re-query unless another thread already replaced {@code stale}. */
    private static Snapshot refresh(@Nullable Snapshot stale) {
        synchronized (WindowsCommandLines.class) {
            Snapshot local = snapshot;
            if (local == null || local == stale) {
                Instant at = Clock.SYSTEM.instant();
                Map<Long, String> rows = query();
                local = new Snapshot(rows, Clock.SYSTEM.nanos(), at);
                snapshot = local;
            }
            return local;
        }
    }

    /** Whether {@code pid} is a live process that started after {@code instant}. */
    private static boolean startedAfter(long pid, Instant instant) {
        return ProcessHandle.of(pid)
                .flatMap(h -> h.info().startInstant())
                .map(started -> !started.isBefore(instant))
                .orElse(false);
    }

    private static Map<Long, String> query() {
        try {
            Processes.Captured c = Processes.capture(
                    new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT)
                            .redirectError(ProcessBuilder.Redirect.DISCARD),
                    Duration.ofSeconds(QUERY_TIMEOUT_SECONDS));
            return c.ok() ? parse(c.output()) : Map.of();
        } catch (IOException e) {
            return Map.of(); // no PowerShell, or policy refused it
        }
    }

    /**
     * Parse {@code <pid>\t<command line>} lines. A line that is not that shape is skipped rather
     * than guessed at — a command line cannot normally contain a newline, but nothing about this
     * snapshot is worth a wrong pid.
     */
    static Map<Long, String> parse(String output) {
        if (output == null || output.isBlank()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        for (String line : output.split("\r?\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String cmd = line.substring(tab + 1).trim();
            if (cmd.isEmpty()) continue;
            try {
                long pid = Long.parseLong(line.substring(0, tab).trim());
                if (pid > 0) out.put(pid, cmd);
            } catch (NumberFormatException notAPid) {
                // a continuation line, or chrome PowerShell added — not a row
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Whether {@code command} (an executable path from {@code ProcessHandle.info().command()}) is a
     * JVM launcher. The weakest corroboration this class's callers use, and only where jk's own
     * state already named the pid.
     */
    static boolean isJvmExecutable(String command) {
        if (command == null || command.isBlank()) return false;
        String name = command.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
