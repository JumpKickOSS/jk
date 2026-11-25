// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.TestHomes;
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
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

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
 * A protocol reply or a pid file identifies a tracked engine. The process scan uses the Java
 * main class or a dedicated engine binary in the installation directory.
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
     *
     * <p>{@code otherHome} marks an engine that belongs to a different {@code JK_HOME} (a scratch
     * install, a test suite's home): {@link #stopAll} leaves it running, so status must say so.
     * {@code home} is that home when its spawn line names it, null when it does not; {@code
     * stateDir} is that engine's state directory when its spawn line names one, which is what
     * identifies an engine run under a custom {@code JK_STATE_DIR}.
     */
    public record Member(
            EnginePaths.@Nullable Paths paths,
            @Nullable Path socket,
            EngineProbe.@Nullable Status status,
            long pid,
            boolean current,
            boolean otherHome,
            @Nullable Path home,
            @Nullable Path stateDir) {

        /** An engine of this home. */
        public Member(
                EnginePaths.@Nullable Paths paths,
                @Nullable Path socket,
                EngineProbe.@Nullable Status status,
                long pid,
                boolean current) {
            this(paths, socket, status, pid, current, false, null, null);
        }

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
        /** Left running deliberately: no {@code --now}, no reply, and life that a kill would cut short. */
        LEFT_ALONE,
        /** Still alive after a kill — the only case a user may have to act on. */
        SURVIVED
    }

    public record StopResult(Member member, Outcome outcome) {}

    /**
     * What {@link #stopAll} did: one result per engine of this home, and the engines of other homes
     * it found and deliberately left running.
     */
    public record FleetStop(List<StopResult> results, List<Member> otherHomes) {
        public FleetStop {
            results = List.copyOf(results);
            otherHomes = List.copyOf(otherHomes);
        }
    }

    /**
     * Every engine this user is running. Disk pointers under this state dir come first; then any
     * other generation pid still alive; then every other resident {@code EngineMain} JVM we can
     * see. Liveness is the process, not the socket: a wedged or rebound engine answers nowhere and
     * is the one {@code status} must not hide. A draining engine answers on its own generation
     * socket and is listed with its status.
     *
     * <p>A jk running in a test sandbox ({@link TestHomes}) lists its own home's engines only: the
     * developer's engines and every other sandbox's are none of a suite's business, and reading their
     * endpoints made a suite's inputs move whenever any engine on the machine started or stopped.
     */
    public static List<Member> list() {
        OtherHomes others = TestHomes.contains(JkDirs.home()) ? OtherHomes.LEFT_OUT : OtherHomes.PROBED;
        return list(JkDirs.state(), EnginePaths.current().key(), others);
    }

    /**
     * Engines that belong to this {@code JK_HOME} / state dir: exactly the members of {@link #list()}
     * that are not {@link Member#otherHome()}, the set {@link #stopAll} stops.
     */
    public static List<Member> listThisHome() {
        return list(JkDirs.state(), EnginePaths.current().key(), OtherHomes.LEFT_OUT);
    }

    /**
     * What a listing does with an engine of another home: leave it out, name it by its process
     * alone, or also read its state directory and ask it for its status.
     */
    enum OtherHomes {
        LEFT_OUT,
        NAMED,
        PROBED
    }

    /**
     * Test seam: enumerate against an explicit state dir, every other home probed, scanning only the
     * processes {@code scanned} accepts — a test's own, never the machine's.
     */
    static List<Member> list(Path stateDir, String currentKey, Predicate<ProcessHandle> scanned) {
        return list(stateDir, currentKey, OtherHomes.PROBED, scanned);
    }

    static List<Member> list(Path stateDir, String currentKey, OtherHomes otherHomes) {
        return list(stateDir, currentKey, otherHomes, h -> true);
    }

    static List<Member> list(
            Path stateDir, String currentKey, OtherHomes otherHomes, Predicate<ProcessHandle> scanned) {
        List<Member> out = new ArrayList<>();
        Set<Long> known = new HashSet<>();
        for (EnginePaths.Paths paths : EnginePaths.identitiesIn(stateDir)) {
            Path socket = EnginePaths.activeSocket(paths);
            Optional<EngineProbe.Status> status = EngineProbe.status(socket);
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
        out.addAll(untracked(stateDir, known, otherHomes, scanned));
        return List.copyOf(out);
    }

    /**
     * Pid files for every generation under this state dir. A draining predecessor keeps its
     * {@code .genN.pid} and answers on its {@code .genN.sock} until it exits; the endpoint already
     * names the successor, so the identity loop above never sees that pid.
     */
    private static void addGenerationPids(Path stateDir, Set<Long> known, List<Member> out) {
        Path dir = stateDir.resolve("engine");
        if (!Files.isDirectory(dir)) return;
        try {
            List<Path> pidFiles = new ArrayList<>();
            PathUtil.forEachChild(dir, (f, attrs) -> !f.getFileName().toString().endsWith(".pid") || pidFiles.add(f));
            pidFiles.forEach(pidFile -> {
                long pid = EngineProcessControl.readPidFile(pidFile);
                if (pid <= 0 || known.contains(pid) || !alive(pid) || !isEnginePid(pid)) return;
                String stem = pidFile.getFileName().toString();
                stem = stem.substring(0, stem.length() - ".pid".length());
                Path socket = EnginePaths.reachableSocketDir(dir).resolve(stem + ".sock");
                String key = keyFromPidStem(stem);
                EnginePaths.Paths paths = EnginePaths.forKey(key, stateDir);
                Optional<EngineProbe.Status> status = EngineProbe.status(socket);
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
     * Discover same-user engines by their executable and launch arguments. Only inspect state
     * directories of the homes the caller intends to probe.
     */
    private static List<Member> untracked(
            Path state, Set<Long> known, OtherHomes otherHomes, Predicate<ProcessHandle> scanned) {
        long self = ProcessHandle.current().pid();
        String me = ProcessHandle.current().info().user().orElse("");
        Path home = JkDirs.home();
        List<Member> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(h -> {
                long pid = h.pid();
                if (pid == self || known.contains(pid) || !scanned.test(h) || !h.isAlive()) return;
                if (!isEngineExecutable(h) || !sameUser(me, h)) return;
                String cmd = commandLineOf(h);
                if (!isResidentEngine(cmd) || !h.isAlive()) return;
                boolean ours = belongsToThisHome(cmd, home, state);
                if (otherHomes == OtherHomes.LEFT_OUT && !ours) return;
                out.add(memberForProcess(pid, cmd, !ours, ours || otherHomes == OtherHomes.PROBED));
            });
        } catch (RuntimeException e) {
            return List.of(); // process enumeration is best-effort; never fail a status over it
        }
        return out;
    }

    /** An installed jar identifies the home; a classpath engine identifies it by its state. */
    static boolean belongsToThisHome(String commandLine, Path homeDir, @Nullable Path state) {
        Path home = WorkingDirectory.resolve(homeDir);
        Path inferred = homeFromCommandLine(commandLine);
        if (inferred != null) return WorkingDirectory.resolve(inferred).equals(home);
        Path engineState = stateFromCommandLine(commandLine);
        return engineState != null && state != null && engineState.equals(WorkingDirectory.resolve(state));
    }

    /** Home of the installed jar or dedicated engine executable named by the launcher. */
    static @Nullable Path homeFromCommandLine(String commandLine) {
        List<String> args = launchArguments(commandLine);
        if (args.isEmpty()) return null;
        String executable = args.getFirst();
        if (!WindowsCommandLines.isJvmExecutable(executable)) return installedHome(executable);
        int main = mainArgument(args);
        if (main < 0 || !ENGINE_MAIN.equals(args.get(main))) return null;
        Path home = null;
        for (int i = 1; i + 1 < main; i++) {
            if (classPathOption(args.get(i))) {
                String classpath = args.get(++i);
                home = classpath.endsWith(".jar") ? installedHome(classpath) : null;
            }
        }
        return home;
    }

    private static @Nullable Path installedHome(String artifact) {
        String normalized = artifact.replace('\\', '/');
        int marker = normalized.lastIndexOf("/lib/jk-engine/");
        if (marker <= 0 || normalized.indexOf('/', marker + "/lib/jk-engine/".length()) >= 0) return null;
        try {
            return Path.of(normalized.substring(0, marker));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final String ENGINE_MAIN = "cc.jumpkick.engine.EngineMain";

    private static boolean classPathOption(String arg) {
        return arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path");
    }

    /** The first application argument of a JVM launched with the engine's JVM options. */
    private static int mainArgument(List<String> args) {
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (classPathOption(arg)) {
                i++;
            } else if (arg.startsWith("-X")
                    || arg.startsWith("-D")
                    || arg.startsWith("--enable-native-access=")
                    || arg.startsWith("--add-opens=")) {
                continue;
            } else {
                return arg.startsWith("-") ? -1 : i;
            }
        }
        return -1;
    }

    /** Split a Windows launch line, including backslashes before quotes in JVM property values. */
    private static List<String> launchArguments(@Nullable String line) {
        if (line == null) return List.of();
        List<String> args = new ArrayList<>();
        StringBuilder arg = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int slashes = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                slashes++;
                started = true;
                continue;
            }
            if (c == '"') {
                arg.append("\\".repeat(slashes / 2));
                if (slashes % 2 == 0) quoted = !quoted;
                else arg.append(c);
                started = true;
            } else {
                arg.append("\\".repeat(slashes));
                if (!quoted && (c == ' ' || c == '\t')) {
                    if (started) args.add(arg.toString());
                    arg.setLength(0);
                    started = false;
                } else {
                    arg.append(c);
                    started = true;
                }
            }
            slashes = 0;
        }
        if (started) args.add(arg.append("\\".repeat(slashes)).toString());
        return quoted ? List.of() : args;
    }

    private static Member memberForProcess(long pid, String cmd, boolean otherHome, boolean probe) {
        Path home = otherHome ? engineHome(cmd) : null;
        Path stateDir = otherHome ? stateFromCommandLine(cmd) : null;
        for (Path state : probe ? candidateStateDirs(cmd) : List.<Path>of()) {
            for (EnginePaths.Paths paths : EnginePaths.identitiesIn(state)) {
                Path socket = EnginePaths.activeSocket(paths);
                Optional<EngineProbe.Status> status = EngineProbe.status(socket);
                if (status.isPresent() && status.get().pid() == pid) {
                    return new Member(paths, socket, status.get(), pid, false, otherHome, home, stateDir);
                }
            }
        }
        return new Member(null, null, null, pid, false, otherHome, home, stateDir);
    }

    /**
     * The home an engine runs for, read off its spawn line: the jar's {@code <home>/lib/jk-engine/},
     * else the parent of a {@code <home>/state} heap-dump directory. Null when neither names one (an
     * engine run from a classpath under a custom {@code JK_STATE_DIR}).
     */
    static @Nullable Path engineHome(String commandLine) {
        Path fromJar = homeFromCommandLine(commandLine);
        if (fromJar != null) return WorkingDirectory.resolve(fromJar);
        Path state = stateFromCommandLine(commandLine);
        if (state == null || state.getFileName() == null) return null;
        if (!state.getFileName().toString().equals("state")) return null;
        return state.getParent();
    }

    /**
     * State dirs worth scanning for an untracked engine's socket: the engine's own, read off the
     * spawn line's heap-dump path ({@link #stateFromCommandLine}), which finds an engine started
     * under any {@code JK_STATE_DIR}; then {@code <home>/state} from the jar path; then this
     * client's own. Scanning a directory that does not exist costs nothing
     * ({@link EnginePaths#identitiesIn} returns empty).
     */
    private static List<Path> candidateStateDirs(String commandLine) {
        List<Path> out = new ArrayList<>(3);
        Path own = stateFromCommandLine(commandLine);
        if (own != null) out.add(own);
        Path home = homeFromCommandLine(commandLine);
        if (home != null && !out.contains(home.resolve("state"))) out.add(home.resolve("state"));
        Path client = JkDirs.state();
        if (client != null && !out.contains(client)) out.add(client);
        return out;
    }

    /** State directory named by the engine's JVM heap-dump option. */
    static @Nullable Path stateFromCommandLine(String commandLine) {
        List<String> args = launchArguments(commandLine);
        if (args.isEmpty()) return null;
        int end = WindowsCommandLines.isJvmExecutable(args.getFirst()) ? mainArgument(args) : args.size();
        String flag = "-XX:HeapDumpPath=";
        for (int i = end - 1; i > 0; i--) {
            String arg = args.get(i);
            if (!arg.startsWith(flag) || classPathOption(args.get(i - 1))) continue;
            try {
                Path dump = Path.of(arg.substring(flag.length()));
                return dump.isAbsolute() && dump.endsWith("engine")
                        ? dump.normalize().getParent()
                        : null;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /** True when the launcher actually runs the resident engine. */
    static boolean isResidentEngine(@Nullable String commandLine) {
        List<String> args = launchArguments(commandLine);
        if (args.isEmpty()) return false;
        if (!WindowsCommandLines.isJvmExecutable(args.getFirst())) return installedHome(args.getFirst()) != null;
        int main = mainArgument(args);
        return main >= 0 && ENGINE_MAIN.equals(args.get(main));
    }

    static boolean isResidentEngineProcess(ProcessHandle handle, String commandLine) {
        return isEngineExecutable(handle) && isResidentEngine(commandLine);
    }

    private static boolean isEngineExecutable(ProcessHandle handle) {
        String exe = handle.info().command().orElse("");
        return WindowsCommandLines.isJvmExecutable(exe) || installedHome(exe) != null;
    }

    static String commandLineOf(ProcessHandle handle) {
        if (Os.isWindows()) return WindowsCommandLines.of(handle.pid());
        var info = handle.info();
        if (Os.isLinux() && info.arguments().isEmpty()) {
            // ProcessHandle caps its Linux argv read at one page.
            try {
                Path cmdline = Path.of("/proc", Long.toString(handle.pid()), "cmdline");
                String raw = new String(Files.readAllBytes(cmdline), StandardCharsets.UTF_8);
                return JobBreakaway.commandLine(List.of(raw.split("\0")));
            } catch (IOException ignored) {
                return "";
            }
        }
        return info.arguments()
                .map(args -> {
                    List<String> command = new ArrayList<>(args.length + 1);
                    command.add(info.command().orElse(""));
                    command.addAll(List.of(args));
                    return JobBreakaway.commandLine(command);
                })
                .orElse("");
    }

    private static boolean sameUser(String me, ProcessHandle them) {
        if (me.isEmpty()) return true;
        String other = them.info().user().orElse("");
        return other.isEmpty() || me.equals(other);
    }

    /** Corroborate a pid from an engine pid file with the live process's launch information. */
    static boolean isEnginePid(long pid) {
        return ProcessHandle.of(pid)
                .map(h -> looksLikeEngine(h, commandLineOf(h)))
                .orElse(false);
    }

    static boolean looksLikeEngine(ProcessHandle handle, String cmd) {
        if (!cmd.isBlank()) return isResidentEngineProcess(handle, cmd);
        return isEngineExecutable(handle);
    }

    /** The pid this identity recorded, from the active generation's file or the base one. */
    private static long recordedPid(EnginePaths.Paths paths, Path socket) {
        long pid = EngineProcessControl.readPidForSocket(socket);
        if (pid > 0) return pid;
        return EngineProcessControl.readPidFile(paths.pid());
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
        return stop(member, now, EngineProbe.Patience.DEFAULT, SilentPeer.Grace.DEFAULT);
    }

    /**
     * As {@link #stop(Member, boolean)} with the silent engine's patience and grace explicit. A
     * graceful stop never kills on silence alone: an engine that accepts connections and answers
     * nothing through the patience, or one with no listener whose process shows life, is
     * {@link Outcome#LEFT_ALONE}; only a lifeless holder with no listener is killed.
     */
    static StopResult stop(Member member, boolean now, EngineProbe.Patience patience, SilentPeer.Grace grace) {
        long pid = member.pid();
        if (now) {
            if (member.responsive() && EngineProcessControl.forceStop(member.socket())) {
                return new StopResult(member, settle(pid));
            }
            // Not answering, or no bye: there is no clean path to ask for, so go straight to the kill.
            EngineProcessControl.hardKill(pid);
            return new StopResult(member, waitGone(pid, EXIT_GRACE_MS) ? Outcome.KILLED : Outcome.SURVIVED);
        }
        return new StopResult(
                member,
                switch (EngineProcessControl.drainPatiently(member.socket(), patience)) {
                    case EngineProcessControl.GracefulStop.Draining d -> Outcome.DRAINING;
                    case EngineProcessControl.GracefulStop.Exiting e -> settle(pid);
                    case EngineProcessControl.GracefulStop.Silent s -> Outcome.LEFT_ALONE;
                    case EngineProcessControl.GracefulStop.Unreachable u ->
                        member.responsive() ? settle(pid) : stopHolder(pid, patience, grace);
                });
    }

    /**
     * No listener, and the member was never answering: the process holds engine state it will not
     * serve. One that shows life is left alone — mid-build with its wire starved, or a recycled pid
     * — and only a lifeless one is killed.
     */
    private static Outcome stopHolder(long pid, EngineProbe.Patience patience, SilentPeer.Grace grace) {
        Optional<SilentPeer.Pulse> pulse = SilentPeer.Pulse.of(pid, Clock.SYSTEM, patience.backoff(), grace);
        if (pulse.isEmpty()) return Outcome.STOPPED;
        if (pulse.get().alive()) return Outcome.LEFT_ALONE;
        EngineProcessControl.hardKill(pid);
        return waitGone(pid, EXIT_GRACE_MS) ? Outcome.KILLED : Outcome.SURVIVED;
    }

    /**
     * Stop every engine that belongs to this {@code JK_HOME} / state dir. Other homes stay up, so a
     * nested test suite cannot kill the host engine running {@code jk build}; they come back in
     * {@link FleetStop#otherHomes()} for the caller to name; {@code --pid} stops them explicitly, and
     * {@link #stopEveryHome} stops them all.
     * Their state is never read: what another home's engine answers is not this stop's business.
     */
    public static FleetStop stopAll(boolean now) {
        return stopAll(JkDirs.state(), EnginePaths.current().key(), now);
    }

    /** As {@link #stopAll(boolean)}, for the engines under an explicit state dir. */
    static FleetStop stopAll(Path stateDir, String currentKey, boolean now) {
        return stopAll(list(stateDir, currentKey, OtherHomes.NAMED), now);
    }

    /** As {@link #stopAll(boolean)}, over an explicit fleet. */
    static FleetStop stopAll(List<Member> fleet, boolean now) {
        return stopAll(fleet, now, false);
    }

    /**
     * Stop every engine this user runs on the host, whatever its home: {@code jk engine stop -A -F}.
     * From a test sandbox ({@link TestHomes}) the fleet is still the sandbox's own, as {@link #list()}
     * leaves the rest out, so a suite can never stop the developer's engines this way either.
     */
    public static FleetStop stopEveryHome(boolean now) {
        return stopAll(list(), now, true);
    }

    private static FleetStop stopAll(List<Member> fleet, boolean now, boolean everyHome) {
        List<StopResult> results = new ArrayList<>();
        for (Member m : targets(fleet, everyHome)) results.add(stop(m, now));
        return new FleetStop(results, everyHome ? List.of() : otherHomes(fleet));
    }

    /** The members a fleet stop stops: this home's, or with {@code everyHome} all of them. */
    static List<Member> targets(List<Member> fleet, boolean everyHome) {
        List<Member> out = new ArrayList<>();
        for (Member m : fleet) if (everyHome || !m.otherHome()) out.add(m);
        return List.copyOf(out);
    }

    private static List<Member> otherHomes(List<Member> fleet) {
        List<Member> out = new ArrayList<>();
        for (Member m : fleet) if (m.otherHome()) out.add(m);
        return List.copyOf(out);
    }

    /**
     * Retire engines positively identified in the superseded platform-default product location. Not
     * from a test sandbox ({@link TestHomes}): that location is the developer's, never the suite's.
     */
    public static List<StopResult> retireOldDefaultLayoutEngines() {
        if (TestHomes.contains(JkDirs.home())) return List.of();
        RetiredEngineLayouts.Layout layout = RetiredEngineLayouts.currentPlatformDefault();
        List<Member> candidates = list(layout.stateDir(), "", OtherHomes.PROBED);
        return retireOldDefaultLayoutEngines(candidates, JkDirs.home(), layout);
    }

    static List<StopResult> retireOldDefaultLayoutEngines(
            List<Member> candidates, Path currentHome, RetiredEngineLayouts.Layout retired) {
        return retireOldDefaultLayoutEngines(candidates, currentHome, retired, EXIT_GRACE_MS);
    }

    /** As above, with the exit grace each retired engine gets before and after its hard kill. */
    static List<StopResult> retireOldDefaultLayoutEngines(
            List<Member> candidates, Path currentHome, RetiredEngineLayouts.Layout retired, long graceMs) {
        List<StopResult> results = new ArrayList<>();
        for (Member member : candidates) {
            String commandLine = ProcessHandle.of(member.pid())
                    .filter(EngineFleet::isEngineExecutable)
                    .map(EngineFleet::commandLineOf)
                    .orElse("");
            if (!isRetiredDefaultEngine(commandLine, currentHome, retired)) continue;
            results.add(retire(member, graceMs));
        }
        return List.copyOf(results);
    }

    static boolean isRetiredDefaultEngine(String commandLine, Path currentHome, RetiredEngineLayouts.Layout retired) {
        if (!isResidentEngine(commandLine)) return false;
        Path engineHome = homeFromCommandLine(commandLine);
        if (engineHome == null) return false;
        Path normalized = WorkingDirectory.resolve(engineHome);
        return normalized.equals(WorkingDirectory.resolve(retired.engineHome()))
                && !normalized.equals(WorkingDirectory.resolve(currentHome));
    }

    /**
     * A superseded-layout engine always goes: a drain, or a silent engine the graceful stop left
     * alone, is given the exit grace and then killed.
     */
    private static StopResult retire(Member member, long graceMs) {
        StopResult result = stop(member, false);
        if (result.outcome() != Outcome.DRAINING && result.outcome() != Outcome.LEFT_ALONE) return result;
        if (waitGone(member.pid(), graceMs)) return new StopResult(member, Outcome.STOPPED);
        EngineProcessControl.hardKill(member.pid());
        return new StopResult(member, waitGone(member.pid(), graceMs) ? Outcome.KILLED : Outcome.SURVIVED);
    }

    /** Stop the engine with this pid, or empty when no running engine has it. */
    public static Optional<StopResult> stopByPid(long pid, boolean now) {
        List<StopResult> results = stopByPids(List.of(pid), now);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Stop each running engine, of any home, whose pid is in {@code pids}, in the fleet's order. A
     * pid no running engine has is absent from the result.
     */
    public static List<StopResult> stopByPids(List<Long> pids, boolean now) {
        Set<Long> wanted = new HashSet<>(pids);
        List<StopResult> results = new ArrayList<>();
        for (Member m : list()) {
            if (wanted.remove(m.pid())) results.add(stop(m, now));
        }
        return List.copyOf(results);
    }

    /**
     * Wait for an engine that should be exiting to disappear, escalating once if it does not.
     *
     * <p>Liveness is checked by pid rather than by socket: a wedged engine can keep a bound socket while
     * being unable to answer, and "the socket stopped responding" is exactly the state where a user would
     * otherwise be told to go find the process themselves.
     */
    private static Outcome settle(long pid) {
        if (waitGone(pid, EXIT_GRACE_MS)) return Outcome.STOPPED;
        EngineProcessControl.hardKill(pid);
        return waitGone(pid, EXIT_GRACE_MS) ? Outcome.KILLED : Outcome.SURVIVED;
    }

    /** Wait up to the standard grace for {@code pid} to exit. True when it is gone. */
    public static boolean waitForExit(long pid) {
        return waitGone(pid, EXIT_GRACE_MS);
    }

    /** Wait up to {@code withinMs} for {@code pid} to exit. True when it is gone. */
    static boolean waitGone(long pid, long withinMs) {
        if (pid <= 0) return true; // nothing addressable; treat as gone rather than claim a kill
        long deadline = Clock.SYSTEM.nanos() + Duration.ofMillis(withinMs).toNanos();
        while (Clock.SYSTEM.nanos() < deadline) {
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
