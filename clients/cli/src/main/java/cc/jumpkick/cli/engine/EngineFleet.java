// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every engine running under this {@code JK_HOME} / platform product layout, and the one reliable way to stop them.
 *
 * <p>There can be several. The engine identity is a hash of the state directory <em>and</em> the
 * artifact store, so one machine holds one engine per {@code (state dir, store)} pair — which is what
 * makes {@code JK_STORE_DIR} work, and also what lets a throwaway store leave a daemon behind.
 * {@code jk engine status} and {@code jk engine stop} list and stop every identity, not just the
 * current one.
 *
 * <p>Stopping has to be reliable without the user reaching for {@code kill}. On Windows that means
 * hunting a JVM in Task Manager, which is not a reasonable thing to expect of anyone. So every stop
 * here <em>verifies</em> the process is gone and escalates when it is not.
 *
 * <h2>How a process is identified as an engine</h2>
 *
 * In descending order of evidence: it answers jk's protocol on jk's socket naming its own pid; its
 * command line names {@code EngineMain} or the engine lib directory; jk's own pid file names it and
 * it is alive. Only the first two can find an engine no pointer records, which is why the command
 * line matters at all — and why {@link WindowsCommandLines} exists, since the JDK supplies none on
 * Windows. Where that snapshot is unavailable there, discovery of <em>untracked</em> engines is not
 * possible and this class reports the ones jk recorded rather than guessing at the rest.
 */
public final class EngineFleet {

    private EngineFleet() {}

    /** How long a stop waits for a clean exit before escalating to a hard kill. */
    private static final long EXIT_GRACE_MS = 8_000;

    private static final long POLL_MS = 100;

    /**
     * One running engine. {@code current} marks the one this directory's commands would reach.
     *
     * <p>{@code status} is null for an engine that is alive but not answering. A wedged engine —
     * one that still holds its socket but cannot reply — must still appear in the list, or
     * {@code stop --pid} would answer "no running engine with pid N" about a process that is
     * running. Verified against a SIGSTOP'd engine.
     */
    public record Member(EnginePaths.Paths paths, Path socket, EngineClient.Status status, long pid, boolean current) {

        /**
         * Short, stable handle for a human to refer to: the identity hash, or a pid-derived label for an
         * engine that has no on-disk identity left at all.
         */
        public String id() {
            return paths != null ? paths.key() : "untracked-" + pid;
        }

        /** False when the engine is alive but not answering its socket. */
        public boolean responsive() {
            return status != null;
        }
    }

    /** What a stop did, so the caller can report it honestly rather than assuming success. */
    public enum Outcome {
        /** Exited on request. */
        STOPPED,
        /** Did not exit in time and was killed. */
        KILLED,
        /** Left running deliberately: in-flight jobs and no {@code --now}. */
        DRAINING,
        /** Still alive after a kill — the only case a user may have to act on. */
        SURVIVED
    }

    public record StopResult(Member member, Outcome outcome) {}

    /**
     * Every engine this user is running. Disk pointers under this state dir come first; then any
     * other generation pid still alive; then every other resident {@code EngineMain} JVM we can
     * see. Liveness is the process, not the socket: a draining or rebound engine often answers
     * nowhere and is the one {@code status} must not hide.
     */
    public static List<Member> list() {
        return list(JkDirs.state(), EnginePaths.current().key(), true);
    }

    /**
     * Engines that belong to this {@code JK_HOME} / state dir. {@link #stopAll} uses this so a
     * nested test suite cannot kill the host engine running {@code jk build}.
     */
    public static List<Member> listThisHome() {
        return list(JkDirs.state(), EnginePaths.current().key(), false);
    }

    /** Test seam: enumerate against an explicit state dir (all homes). */
    static List<Member> list(Path stateDir, String currentKey) {
        return list(stateDir, currentKey, true);
    }

    static List<Member> list(Path stateDir, String currentKey, boolean allHomes) {
        List<Member> out = new ArrayList<>();
        Set<Long> known = new HashSet<>();
        for (EnginePaths.Paths paths : EnginePaths.identitiesIn(stateDir)) {
            Path socket = EnginePaths.activeSocket(paths);
            Optional<EngineClient.Status> status = EngineClient.status(socket);
            if (status.isPresent()) {
                long pid = status.get().pid();
                boolean isCurrent = paths.key().equals(currentKey);
                out.add(new Member(paths, socket, status.get(), pid, isCurrent));
                known.add(pid);
                continue;
            }
            long pid = recordedPid(paths, socket);
            if (pid > 0 && alive(pid) && isEnginePid(pid)) {
                out.add(new Member(paths, socket, null, pid, paths.key().equals(currentKey)));
                known.add(pid);
            }
        }
        addGenerationPids(stateDir, known, out);
        out.addAll(untracked(known, allHomes));
        return List.copyOf(out);
    }

    /**
     * Pid files for every generation under this state dir. A draining predecessor keeps its
     * {@code .genN.pid} until it exits; the endpoint already names the successor, so the
     * identity loop above never sees that pid.
     */
    private static void addGenerationPids(Path stateDir, Set<Long> known, List<Member> out) {
        Path dir = stateDir.resolve("engine");
        if (!Files.isDirectory(dir)) return;
        try (var listing = Files.list(dir)) {
            listing.filter(f -> f.getFileName().toString().endsWith(".pid")).forEach(pidFile -> {
                long pid = EngineClient.readPidFile(pidFile);
                if (pid <= 0 || known.contains(pid) || !alive(pid) || !isEnginePid(pid)) return;
                String stem = pidFile.getFileName().toString();
                stem = stem.substring(0, stem.length() - ".pid".length());
                Path socket = pidFile.resolveSibling(stem + ".sock");
                String key = keyFromPidStem(stem);
                EnginePaths.Paths paths = EnginePaths.forKey(key, stateDir);
                Optional<EngineClient.Status> status = EngineClient.status(socket);
                if (status.isPresent() && status.get().pid() == pid) {
                    out.add(new Member(paths, socket, status.get(), pid, false));
                } else {
                    out.add(new Member(paths, socket, null, pid, false));
                }
                known.add(pid);
            });
        } catch (IOException ignored) {
            // listing is best-effort
        }
    }

    static String keyFromPidStem(String stem) {
        int gen = stem.indexOf(".gen");
        return gen > 0 ? stem.substring(0, gen) : stem;
    }

    /**
     * Resident engine JVMs this user owns that no on-disk pointer named. Match is the engine
     * main class or {@code /lib/jk-engine/} on the command line. Sidecar AOT trainers and other
     * users' processes are excluded. {@code allHomes} includes other {@code JK_HOME}s (status);
     * {@code false} keeps stop scoped to this home.
     *
     * <p>A process whose command line cannot be read is never claimed here: this pass has no
     * jk-owned state corroborating it, so the executable alone ({@code java.exe} — every Gradle
     * daemon on the machine) would be a licence to kill unrelated JVMs.
     */
    private static List<Member> untracked(Set<Long> known, boolean allHomes) {
        long self = ProcessHandle.current().pid();
        String me = ProcessHandle.current().info().user().orElse("");
        Path home = JkDirs.home();
        Path state = JkDirs.state();
        List<Member> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(h -> {
                long pid = h.pid();
                if (pid == self || known.contains(pid) || !h.isAlive()) return;
                if (!sameUser(me, h)) return;
                String cmd = commandLineOf(h);
                if (!isResidentEngineProcess(h, cmd)) return;
                if (!allHomes && !belongsToThisHome(cmd, home, state)) return;
                out.add(memberForProcess(pid, cmd));
            });
        } catch (RuntimeException e) {
            return List.of(); // process enumeration is best-effort; never fail a status over it
        }
        return out;
    }

    /**
     * True when {@code commandLine} names this home or state dir (jar path, AOT path, or the home
     * inferred from the jar). A test JVM under {@code target/test-jk-home} must not match the
     * developer's {@code ~/.jk} engine.
     */
    static boolean belongsToThisHome(String commandLine, Path homeDir, Path state) {
        if (commandLine == null || commandLine.isBlank() || homeDir == null) return false;
        String cmd = commandLine.replace('\\', '/');
        String homeStr = homeDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!homeStr.isEmpty() && cmd.contains(homeStr)) return true;
        if (state != null) {
            String stateStr = state.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!stateStr.isEmpty() && cmd.contains(stateStr)) return true;
        }
        Path inferred = homeFromCommandLine(commandLine);
        return inferred != null
                && inferred.toAbsolutePath()
                        .normalize()
                        .equals(homeDir.toAbsolutePath().normalize());
    }

    /**
     * Best-effort <em>home root</em> from a spawn line ({@code …/<home>/lib/jk-engine/<jar>}). Null
     * when the command line is not that shape.
     */
    static Path homeFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return null;
        String norm = commandLine.replace('\\', '/');
        int marker = norm.indexOf("/lib/jk-engine/");
        if (marker <= 0) return null;
        int start = quotedTokenStart(norm, marker, '"');
        if (start < 0) start = quotedTokenStart(norm, marker, '\'');
        if (start < 0) {
            start = marker;
            while (start > 0 && !Character.isWhitespace(norm.charAt(start - 1))) start--;
        }
        String parent = norm.substring(start, marker);
        return parent.isBlank() ? null : Path.of(parent);
    }

    private static int quotedTokenStart(String commandLine, int before, char quote) {
        boolean inside = false;
        int opening = -1;
        for (int i = 0; i < before; i++) {
            if (commandLine.charAt(i) != quote) continue;
            inside = !inside;
            opening = inside ? i : -1;
        }
        return inside ? opening + 1 : -1;
    }

    private static Member memberForProcess(long pid, String cmd) {
        for (Path state : candidateStateDirs(cmd)) {
            for (EnginePaths.Paths paths : EnginePaths.identitiesIn(state)) {
                Path socket = EnginePaths.activeSocket(paths);
                Optional<EngineClient.Status> status = EngineClient.status(socket);
                if (status.isPresent() && status.get().pid() == pid) {
                    return new Member(paths, socket, status.get(), pid, false);
                }
            }
        }
        return new Member(null, null, null, pid, false);
    }

    /**
     * State dirs worth scanning for an untracked engine's socket: {@code <home>/state} read
     * straight off the spawn line, then this client's own — which differ only when
     * {@code JK_STATE_DIR} moved state out of the home tree. Scanning a directory that does not
     * exist costs nothing ({@link EnginePaths#identitiesIn} returns empty).
     */
    private static List<Path> candidateStateDirs(String commandLine) {
        List<Path> out = new ArrayList<>(2);
        Path home = homeFromCommandLine(commandLine);
        if (home != null) out.add(home.resolve("state"));
        Path own = JkDirs.state();
        if (own != null && !out.contains(own)) out.add(own);
        return out;
    }

    /** True when {@code commandLine} is a resident engine JVM. Trainers pass {@code --aot-training}. */
    static boolean isResidentEngine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return false;
        if (commandLine.contains("--aot-training")) return false;
        return commandLine.contains("cc.jumpkick.engine.EngineMain") || commandLine.contains("/lib/jk-engine/");
    }

    /**
     * True when this <em>process</em> is a resident engine: its command line names one <em>and</em>
     * it is the kind of program that can host one.
     *
     * <p>The command line alone is a substring match, so it says yes to anything that merely
     * mentions the engine — the shell that ran {@code jk engine status}, a {@code grep} over this
     * source tree, an editor with the file open. Those are not engines, and {@code stop --all}
     * hard-kills what this returns; a fleet that lists the user's own terminal is one keystroke
     * from killing it.
     */
    static boolean isResidentEngineProcess(ProcessHandle handle, String commandLine) {
        return isResidentEngine(commandLine) && isEngineExecutable(handle);
    }

    /**
     * Whether the process's own executable can host an engine: a JVM launcher, or a binary inside
     * the engine lib directory ({@code JK_ENGINE_EXE}). An engine behind a wrapper elsewhere is
     * still found by the pointer and pid-file passes above — this only bounds who may be claimed
     * from a bare process scan.
     */
    private static boolean isEngineExecutable(ProcessHandle handle) {
        String exe = handle.info().command().orElse("");
        return WindowsCommandLines.isJvmExecutable(exe)
                || exe.replace('\\', '/').contains("/lib/jk-engine/");
    }

    static String commandLineOf(ProcessHandle handle) {
        // /proc is the full argv; ProcessHandle.commandLine() is sometimes only the executable.
        String proc = procCmdline(handle.pid());
        if (!proc.isBlank()) return proc;
        var info = handle.info();
        String cmd = info.command().orElse("");
        String args = info.arguments().map(a -> String.join(" ", a)).orElse("");
        String joined = (cmd + " " + args).trim();
        // On Windows that joined value is the bare executable path: the JDK populates command() but
        // never arguments()/commandLine() there, so every predicate below would see `java.exe` and
        // nothing else. The CIM snapshot is the only source of the real argv.
        String windows = WindowsCommandLines.of(handle.pid());
        if (!windows.isBlank()) return windows;
        if (!joined.isBlank()) return joined;
        return info.commandLine().orElse("");
    }

    /** {@code /proc/<pid>/cmdline} with NULs turned into spaces; empty off Linux or if unreadable. */
    static String procCmdline(long pid) {
        Path file = Path.of("/proc", Long.toString(pid), "cmdline");
        try {
            byte[] raw = Files.readAllBytes(file);
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) raw[i] = (byte) ' ';
            }
            return new String(raw, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean sameUser(String me, ProcessHandle them) {
        if (me.isEmpty()) return true;
        String other = them.info().user().orElse("");
        return other.isEmpty() || me.equals(other);
    }

    /**
     * Whether {@code pid} — named by one of jk's own pid files — is still an engine rather than a
     * pid the OS recycled onto something else. That guard is what keeps a stale pid file from
     * putting an innocent process in the fleet, where {@code stop --all} would kill it.
     *
     * <p>A command line settles it. When none is available (Windows with the CIM snapshot
     * unavailable — a blocked or absent PowerShell), the corroboration left is jk's own pid file
     * plus a JVM launcher as the executable, which is the same evidence
     * {@link EngineClient#unresponsiveHolderPid} acts on. It narrows recycling to "recycled onto
     * another JVM" rather than ruling it out; the alternative is to stop listing engines jk itself
     * recorded, and a fleet that hides its own engines is the failure this class exists for.
     */
    private static boolean isEnginePid(long pid) {
        return ProcessHandle.of(pid).map(EngineFleet::looksLikeEngine).orElse(false);
    }

    private static boolean looksLikeEngine(ProcessHandle handle) {
        String cmd = commandLineOf(handle);
        if (!cmd.isBlank()) return isResidentEngineProcess(handle, cmd);
        return isEngineExecutable(handle);
    }

    /** The pid this identity recorded, from the active generation's file or the base one. */
    private static long recordedPid(EnginePaths.Paths paths, Path socket) {
        long pid = EngineClient.readPidForSocket(socket);
        if (pid > 0) return pid;
        return EngineClient.readPidFile(paths.pid());
    }

    /**
     * Stop {@code member}, then confirm it actually went.
     *
     * <p>{@code now} forces immediately. Without it, an engine with in-flight jobs is asked to drain and
     * left to finish — killing it would abandon work the user did not ask to abandon — while an engine
     * with nothing running is expected to exit promptly and is killed if it does not. So the escalation
     * only ever targets an engine that should already be going, and {@code --now} is the answer when the
     * caller means "regardless".
     */
    public static StopResult stop(Member member, boolean now) {
        long pid = member.pid();
        // Not answering: there is no clean path to ask for, so go straight to the kill. Asking a wedged
        // engine politely and then reporting success is how one gets left behind.
        if (!member.responsive()) {
            EngineClient.hardKill(pid);
            return new StopResult(member, waitGone(pid, EXIT_GRACE_MS) ? Outcome.KILLED : Outcome.SURVIVED);
        }
        if (now) {
            if (!EngineClient.forceStop(member.socket())) {
                EngineClient.hardKill(pid);
            }
            return new StopResult(member, settle(member, pid, /* mayKill= */ true));
        }
        int jobs = EngineClient.drain(member.socket());
        if (jobs > 0) {
            return new StopResult(member, Outcome.DRAINING);
        }
        return new StopResult(member, settle(member, pid, /* mayKill= */ true));
    }

    /**
     * Stop every engine that belongs to this {@code JK_HOME} / state dir. Other homes stay up —
     * {@code jk engine status} still lists them; {@code --pid} stops one explicitly.
     */
    public static List<StopResult> stopAll(boolean now) {
        List<StopResult> results = new ArrayList<>();
        for (Member m : listThisHome()) {
            results.add(stop(m, now));
        }
        return List.copyOf(results);
    }

    /**
     * Retire engines positively identified in the superseded platform-default product location.
     */
    public static List<StopResult> retireOldDefaultLayoutEngines() {
        RetiredEngineLayouts.Layout layout = RetiredEngineLayouts.currentPlatformDefault();
        List<Member> candidates = list(layout.stateDir(), "", true);
        return retireOldDefaultLayoutEngines(candidates, JkDirs.home(), layout);
    }

    static List<StopResult> retireOldDefaultLayoutEngines(
            List<Member> candidates, Path currentHome, RetiredEngineLayouts.Layout retired) {
        List<StopResult> results = new ArrayList<>();
        for (Member member : candidates) {
            String commandLine = ProcessHandle.of(member.pid())
                    .map(EngineFleet::commandLineOf)
                    .orElse("");
            if (!isRetiredDefaultEngine(commandLine, currentHome, retired)) continue;
            results.add(retire(member));
        }
        return List.copyOf(results);
    }

    static boolean isRetiredDefaultEngine(String commandLine, Path currentHome, RetiredEngineLayouts.Layout retired) {
        if (!isResidentEngine(commandLine)) return false;
        Path engineHome = homeFromCommandLine(commandLine);
        if (engineHome == null) return false;
        Path normalized = engineHome.toAbsolutePath().normalize();
        return normalized.equals(retired.engineHome().toAbsolutePath().normalize())
                && !normalized.equals(currentHome.toAbsolutePath().normalize());
    }

    private static StopResult retire(Member member) {
        StopResult result = stop(member, false);
        if (result.outcome() != Outcome.DRAINING) return result;
        if (waitForExit(member.pid())) return new StopResult(member, Outcome.STOPPED);
        EngineClient.hardKill(member.pid());
        return new StopResult(member, waitForExit(member.pid()) ? Outcome.KILLED : Outcome.SURVIVED);
    }

    /** Stop the engine with this pid, or empty when no running engine has it. */
    public static Optional<StopResult> stopByPid(long pid, boolean now) {
        for (Member m : list()) {
            if (m.pid() == pid) return Optional.of(stop(m, now));
        }
        return Optional.empty();
    }

    /**
     * Wait for the process to disappear, escalating once if it does not.
     *
     * <p>Liveness is checked by pid rather than by socket: a wedged engine can keep a bound socket while
     * being unable to answer, and "the socket stopped responding" is exactly the state where a user would
     * otherwise be told to go find the process themselves.
     */
    private static Outcome settle(Member member, long pid, boolean mayKill) {
        if (waitGone(pid, EXIT_GRACE_MS)) return Outcome.STOPPED;
        if (!mayKill) return Outcome.DRAINING;
        EngineClient.hardKill(pid);
        return waitGone(pid, EXIT_GRACE_MS) ? Outcome.KILLED : Outcome.SURVIVED;
    }

    /** Wait up to the standard grace for {@code pid} to exit. True when it is gone. */
    public static boolean waitForExit(long pid) {
        return waitGone(pid, EXIT_GRACE_MS);
    }

    private static boolean waitGone(long pid, long withinMs) {
        if (pid <= 0) return true; // nothing addressable; treat as gone rather than claim a kill
        long deadline = System.nanoTime() + Duration.ofMillis(withinMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (!alive(pid)) return true;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !alive(pid);
            }
        }
        return !alive(pid);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
