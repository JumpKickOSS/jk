// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.EnginePaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
     * Every engine that is actually running, newest identity first; the current one is flagged.
     *
     * <p>Liveness is decided by the recorded pid, not by whether the socket answers. Probing the socket
     * first was the obvious approach and it hid exactly the engines a user most needs to see: a wedged one
     * fails the probe while continuing to hold memory and its port. A pointer whose process is gone is
     * stale and skipped; a process that is alive is listed whether or not it can talk.
     */
    public static List<Member> list() {
        String currentKey = EnginePaths.current().key();
        List<Member> out = new ArrayList<>();
        for (EnginePaths.Paths paths : EnginePaths.identitiesIn(cc.jumpkick.util.JkDirs.state())) {
            Path socket = EnginePaths.activeSocket(paths);
            Optional<EngineClient.Status> status = EngineClient.status(socket);
            boolean isCurrent = paths.key().equals(currentKey);
            if (status.isPresent()) {
                out.add(new Member(paths, socket, status.get(), status.get().pid(), isCurrent));
                continue;
            }
            long pid = recordedPid(paths, socket);
            if (pid > 0 && alive(pid)) {
                out.add(new Member(paths, socket, null, pid, isCurrent));
            }
        }
        java.util.Set<Long> known = new java.util.HashSet<>();
        for (Member m : out) known.add(m.pid());
        out.addAll(untracked(known));
        return List.copyOf(out);
    }

    /**
     * Engines with no on-disk identity at all, found by matching this installation's engine jar on the
     * process command line.
     *
     * <p>An engine whose endpoint pointer AND pid file are both gone is invisible to every disk-based
     * lookup, yet still holds memory and a port. Five such processes survived a {@code stop --all} that
     * reported success — which is precisely the outcome this command exists to prevent, since the
     * alternative for the user is {@code kill}, or Task Manager on Windows.
     *
     * <p>Matching is deliberately narrow: the command line must reference {@code jk-engine.jar} <em>under
     * this {@code JK_HOME} / platform product layout</em>, so another user's engine, another installation, or an unrelated JVM is never
     * a candidate. AOT training sidecars are excluded — they are bounded and self-halting, and killing one
     * mid-recording would discard work for no benefit.
     */
    private static List<Member> untracked(java.util.Set<Long> known) {
        String home =
                cc.jumpkick.util.JkDirs.home().toAbsolutePath().normalize().toString();
        long self = ProcessHandle.current().pid();
        List<Member> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(h -> {
                long pid = h.pid();
                if (pid == self || known.contains(pid)) return;
                String cmd = h.info().commandLine().orElse("");
                if (!cmd.contains("jk-engine.jar") || !cmd.contains(home)) return;
                if (cmd.contains("--aot-training")) return;
                out.add(new Member(null, null, null, pid, false));
            });
        } catch (RuntimeException e) {
            return List.of(); // process enumeration is best-effort; never fail a stop over it
        }
        return out;
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

    /** Stop every running engine. */
    public static List<StopResult> stopAll(boolean now) {
        List<StopResult> results = new ArrayList<>();
        for (Member m : list()) {
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
        long deadline =
                System.nanoTime() + java.time.Duration.ofMillis(withinMs).toNanos();
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
