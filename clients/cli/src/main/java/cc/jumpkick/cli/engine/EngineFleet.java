// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.EnginePaths;
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
 * makes {@code JK_STORE_DIR} work, and also what let a throwaway store leave a daemon behind. Before
 * this existed, {@code jk engine status} and {@code jk engine stop} each addressed exactly one
 * identity, so the rest were invisible and unstoppable: eighteen were once found alive with no
 * supported way to clear them.
 *
 * <p>Stopping has to be reliable without the user reaching for {@code kill}. On Windows that means
 * hunting a JVM in Task Manager, which is not a reasonable thing to expect of anyone. So every stop
 * here <em>verifies</em> the process is gone and escalates when it is not.
 */
public final class EngineFleet {

    private EngineFleet() {}

    /** How long a stop waits for a clean exit before escalating to a hard kill. */
    private static final long EXIT_GRACE_MS = 8_000;

    private static final long POLL_MS = 100;

    /**
     * One running engine. {@code current} marks the one this directory's commands would reach.
     *
     * <p>{@code status} is null for an engine that is alive but not answering. That case is the whole
     * reason this is nullable rather than an {@code Optional<Status>} of convenience: a wedged engine
     * one that still holds its socket but cannot reply — used to be invisible, because discovery went
     * through a status probe. {@code status} would come back empty, the engine would be skipped, and
     * {@code stop --pid} would answer "no running engine with pid N" about a process that was very much
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
        return list(cc.jumpkick.util.JkDirs.state(), EnginePaths.current().key(), true);
    }

    /**
     * Engines that belong to this {@code JK_HOME} / state dir. {@link #stopAll} uses this so a
     * nested test suite cannot kill the host engine running {@code jk build}.
     */
    public static List<Member> listThisHome() {
        return list(cc.jumpkick.util.JkDirs.state(), EnginePaths.current().key(), false);
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
     * main class or {@code jk-engine.jar} on the command line. Sidecar AOT trainers and other
     * users' processes are excluded. {@code allHomes} includes other {@code JK_HOME}s (status);
     * {@code false} keeps stop scoped to this home.
     */
    private static List<Member> untracked(Set<Long> known, boolean allHomes) {
        long self = ProcessHandle.current().pid();
        String me = ProcessHandle.current().info().user().orElse("");
        Path home = cc.jumpkick.util.JkDirs.home();
        Path state = cc.jumpkick.util.JkDirs.state();
        List<Member> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(h -> {
                long pid = h.pid();
                if (pid == self || known.contains(pid) || !h.isAlive()) return;
                if (!sameUser(me, h)) return;
                String cmd = commandLineOf(h);
                if (!isResidentEngine(cmd)) return;
                if (!allHomes && !belongsToThisHome(cmd, home, state)) return;
                out.add(memberForProcess(pid, cmd));
            });
        } catch (RuntimeException e) {
            return List.of(); // process enumeration is best-effort; never fail a status over it
        }
        return out;
    }

    /**
     * True when {@code commandLine} names this product home or state dir (jar path, AOT path, or
     * inferred {@code JK_HOME}). A test JVM under {@code target/test-jk-home} must not match the
     * developer's {@code ~/.local/share/jk} engine.
     */
    static boolean belongsToThisHome(String commandLine, Path home, Path state) {
        if (commandLine == null || commandLine.isBlank() || home == null) return false;
        String cmd = commandLine.replace('\\', '/');
        String homeStr = home.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!homeStr.isEmpty() && cmd.contains(homeStr)) return true;
        if (state != null) {
            String stateStr = state.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!stateStr.isEmpty() && cmd.contains(stateStr)) return true;
        }
        Path inferred = homeFromCommandLine(commandLine);
        return inferred != null
                && inferred.toAbsolutePath()
                        .normalize()
                        .equals(home.toAbsolutePath().normalize());
    }

    /**
     * Best-effort product home from a spawn line ({@code …/<home>/lib/jk-engine.jar} or the leftover
     * {@code …/<home>/versions/<v>/lib/jk-engine.jar}). Empty when the command line is not that shape.
     */
    static Path homeFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return null;
        String norm = commandLine.replace('\\', '/');
        int jar = norm.indexOf("/lib/jk-engine.jar");
        if (jar < 0) return null;
        int lib = norm.lastIndexOf("/lib/jk-engine.jar", jar);
        if (lib <= 0) return null;
        int start = lib;
        while (start > 0) {
            char c = norm.charAt(start - 1);
            if (c == ' ' || c == '\t') break;
            start--;
        }
        String parent = norm.substring(start, lib);
        if (parent.isBlank()) return null;
        // leftover: <home>/versions/<v>/lib/jk-engine.jar
        int versions = parent.lastIndexOf("/versions/");
        if (versions > 0 && parent.indexOf('/', versions + "/versions/".length()) < 0) {
            parent = parent.substring(0, versions);
        }
        return parent.isBlank() ? null : Path.of(parent);
    }

    private static Member memberForProcess(long pid, String cmd) {
        Path home = homeFromCommandLine(cmd);
        if (home != null) {
            Path state = home.resolve("state");
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

    /** True when {@code commandLine} is a resident engine JVM. Trainers pass {@code --aot-training}. */
    static boolean isResidentEngine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return false;
        if (commandLine.contains("--aot-training")) return false;
        return commandLine.contains("cc.jumpkick.engine.EngineMain") || commandLine.contains("jk-engine.jar");
    }

    static String commandLineOf(ProcessHandle handle) {
        // /proc is the full argv; ProcessHandle.commandLine() is sometimes only the executable.
        String proc = procCmdline(handle.pid());
        if (!proc.isBlank()) return proc;
        var info = handle.info();
        String cmd = info.command().orElse("");
        String args = info.arguments().map(a -> String.join(" ", a)).orElse("");
        String joined = (cmd + " " + args).trim();
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

    private static boolean isEnginePid(long pid) {
        return ProcessHandle.of(pid)
                .map(h -> isResidentEngine(commandLineOf(h)))
                .orElse(false);
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
