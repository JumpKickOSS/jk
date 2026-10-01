// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Linux containment for forked workers: a raised {@code oom_score_adj}, and where this process's
 * cgroup can take a child, a cgroup v2 {@code workers} group with {@code memory.max} and {@code
 * memory.swap.max} 0 when that file exists. Inside it each worker gets its own group, {@code
 * workers/w<pid>}, whose {@code memory.max} is {@link WorkerRss#workerCapBytes}: the kernel stops
 * one runaway worker at its cap instead of letting it take the whole budget. Where the memory
 * controller cannot be enabled below {@code workers}, workers share that group as before.
 *
 * <p>{@code oom_score_adj} of {@value #OOM_SCORE_ADJ} is added to the kernel's badness, so a worker
 * is preferred over any other process that is not using about 80% of RAM more than the worker. The
 * engine's own score is never written. The move into {@code workers} happens just after the child
 * starts, so the child can run outside that group for that moment. A step that fails leaves the
 * score in place and records why.
 */
public final class WorkerContainment {

    /**
     * Written to a worker's {@code /proc/pid/oom_score_adj}. High enough that a jk worker is the
     * OOM victim before the session or the engine; short of 1000, which would ignore how much
     * memory the worker actually holds.
     */
    public static final int OOM_SCORE_ADJ = 800;

    /** The words a diagnostic uses when the kernel killed a worker for memory. */
    public static final String KILLED_FOR_MEMORY = "killed for memory";

    /**
     * The words a diagnostic uses when the kernel killed a worker at its own cap. A retry at the
     * same or a larger heap does not help: the worker grew past its lease outside the heap.
     */
    public static final String KILLED_AT_CAP = KILLED_FOR_MEMORY + " at its worker cap";

    /** Why {@link #KILLED_AT_CAP} is not a heap problem, appended to it. */
    public static final String OUTSIDE_HEAP =
            "; the memory was outside the heap (metaspace, thread stacks, direct buffers), so a larger -Xmx"
                    + " does not help";

    /** Name prefix of a worker's own group under {@code workers}. */
    static final String CHILD_PREFIX = "w";

    /** Under {@code workers}, the group a worker joins when its own could not be made. */
    static final String SHARED_CHILD = "shared";

    /** Cap kills {@link #memoryKill} has not yet attributed; older ones are dropped. */
    private static final int MAX_PENDING_CAP_KILLS = 16;

    /**
     * Set by the client when this process was not started in a delegated scope. Appended to a
     * score-only reason. Not a user setting; {@code JK_ENGINE_SCOPE=0} is the switch.
     */
    static final String SCOPE_NOTE = "JK_SCOPE_REASON";

    /** Java's exit status for SIGKILL ({@code 128 + 9}). */
    static final int SIGKILL_EXIT = 137;

    private static final long GIB = 1024L * 1024L * 1024L;

    private static final String PROBE_DIR = "jk-containment-probe";

    private static volatile @Nullable State state;

    private WorkerContainment() {}

    /**
     * What {@code jk engine status} reports. The limit is {@code -1} unless {@link Mode#CGROUP}.
     * {@code perWorker} is true when each worker runs in its own capped group.
     */
    public record Report(Mode mode, String reason, long maxBytes, boolean perWorker) {

        /** {@code cgroup}, {@code score-only}, or {@code none}. */
        public String modeWord() {
            return switch (mode) {
                case NONE -> "none";
                case SCORE_ONLY -> "score-only";
                case CGROUP -> "cgroup";
            };
        }

        /** The status line: {@code none}, {@code score-only (<reason>)}, or {@code cgroup (max X GiB)}. */
        public String text() {
            return switch (mode) {
                case NONE -> "none";
                case SCORE_ONLY -> reason.isEmpty() ? "score-only" : "score-only (" + reason + ")";
                case CGROUP -> "cgroup (max " + gib(maxBytes) + (perWorker ? ", capped per worker)" : ")");
            };
        }

        /**
         * The {@code memory.max} a worker at the smallest lease gets from {@code budgetBytes}, as
         * {@link WorkerRss#workerCapBytes} writes it; {@code -1} unless each worker has its own group.
         */
        public long workerCapBytes(long budgetBytes) {
            return mode == Mode.CGROUP && perWorker ? WorkerRss.workerCapBytes(0, budgetBytes) : -1L;
        }

        private static String gib(long bytes) {
            return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
        }
    }

    /** Which containment is active. */
    public enum Mode {
        /** Not Linux. */
        NONE,
        /** {@code oom_score_adj} only. {@link Report#reason()} says why the cgroup was not used. */
        SCORE_ONLY,
        /** Workers run in a cgroup with {@code memory.max}. */
        CGROUP
    }

    /**
     * Test and ops override for {@link #budgetBytes()}, in whole mebibytes. Read from this process,
     * so a resident engine keeps the value it was started with; {@code jk engine stop} first.
     */
    public static final String BUDGET_ENV = "JK_WORKER_BUDGET_MB";

    /** Where {@link #leaseBudget()} got its number. */
    public enum BudgetSource {
        /** Host {@code MemTotal}, minus the reserve. */
        HOST("host"),
        /** The enclosing cgroup's {@code memory.max} was the tighter input. */
        CGROUP("cgroup"),
        /** {@link #BUDGET_ENV} replaced the host number for this process. */
        OVERRIDE("override");

        private final String wire;

        BudgetSource(String wire) {
            this.wire = wire;
        }

        /** {@code host}, {@code cgroup}, or {@code override}. */
        public String wire() {
            return wire;
        }
    }

    /** The lease budget and which input produced it. */
    public record LeaseBudget(long bytes, BudgetSource source) {}

    /** {@code memory.max} for the workers group, in bytes. */
    public record Limits(long maxBytes) {}

    /**
     * The decision {@link #plan} reads off a {@code /proc} and {@code /sys} tree. {@code own} and
     * {@code workers} are null unless {@link Mode#CGROUP}.
     */
    public record Plan(
            Mode mode,
            String reason,
            @Nullable Limits limits,
            boolean moveEngine,
            boolean enableMemory,
            @Nullable Path own,
            @Nullable Path workers) {}

    /**
     * Worker-group limit from the host budget. {@code memTotalBytes} is {@code MemTotal};
     * {@code enclosingMaxBytes} is the enclosing cgroup's {@code memory.max}, or {@code -1} when
     * that file is absent or reads {@code max}. The reserve is the larger of 2 GiB and 10% of the
     * budget; {@code memory.max} is what remains. Null when the budget does not cover the reserve —
     * the one place a later host budget replaces.
     */
    public static @Nullable Limits budget(long memTotalBytes, long enclosingMaxBytes) {
        long budget = memTotalBytes > 0 ? memTotalBytes : -1L;
        if (enclosingMaxBytes > 0 && enclosingMaxBytes < MemoryProbe.CGROUP_UNLIMITED) {
            budget = budget > 0 ? Math.min(budget, enclosingMaxBytes) : enclosingMaxBytes;
        }
        if (budget <= 0) return null;
        long reserve = Math.max(2 * GIB, budget / 10);
        long max = budget - reserve;
        if (max <= 0) return null;
        return new Limits(max);
    }

    /**
     * Read {@code root} as a filesystem root ({@code proc/} and {@code sys/fs/cgroup/}) and decide
     * the mode. Does not move a process. {@code linux} is false on any other host, which is
     * {@link Mode#NONE}.
     */
    public static Plan plan(Path root, boolean linux, long selfPid) {
        if (!linux) return new Plan(Mode.NONE, "", null, false, false, null, null);
        Path proc = root.resolve("proc");
        Path cgroupRoot = root.resolve("sys/fs/cgroup");
        if (!Files.exists(cgroupRoot.resolve("cgroup.controllers"))) {
            return scoreOnly("cgroup v2 is not mounted");
        }
        String cgPath = cgroupPath(proc.resolve("self/cgroup"));
        if (cgPath == null) return scoreOnly("this process has no cgroup");
        String rel = cgPath.startsWith("/") ? cgPath.substring(1) : cgPath;
        if (rel.contains("..") || rel.indexOf('\0') >= 0) return scoreOnly("this process has no cgroup");
        Path own = cgroupRoot.resolve(rel).normalize();
        if (!own.startsWith(cgroupRoot.normalize())) return scoreOnly("this process has no cgroup");
        if (!Files.isDirectory(own)) return scoreOnly("this process has no cgroup");
        String controllers = readQuiet(own.resolve("cgroup.controllers"));
        if (controllers == null) return scoreOnly("this process has no cgroup");
        if (!hasWord(controllers, "memory")) return scoreOnly("the memory controller is not delegated");
        if (!canCreateChild(own)) return scoreOnly("cannot create a child cgroup");
        long memTotal = -1L;
        String meminfo = readQuiet(proc.resolve("meminfo"));
        if (meminfo != null) memTotal = MemoryProbe.meminfoValueBytes(meminfo, "MemTotal");
        long enclosing = parseLimit(readQuiet(own.resolve("memory.max")));
        if (memTotal <= 0 && enclosing <= 0) return scoreOnly("host memory is unknown");
        Limits limits = budget(memTotal, enclosing);
        if (limits == null) return scoreOnly("host memory is below the reserve");
        Path workers = own.resolve("workers");
        String subtree = readQuiet(own.resolve("cgroup.subtree_control"));
        if (subtree != null && hasWord(subtree, "memory")) {
            return new Plan(Mode.CGROUP, "", limits, false, false, own, workers);
        }
        List<Long> members = pids(readQuiet(own.resolve("cgroup.procs")));
        if (members == null) return scoreOnly("this process has no cgroup");
        if (!members.contains(selfPid)) return scoreOnly("this process is not a member of its cgroup");
        if (members.size() != 1) return scoreOnly("other processes share this cgroup");
        return new Plan(Mode.CGROUP, "", limits, true, true, own, workers);
    }

    /**
     * Detect the mode for this process and, when the cgroup can be delegated, arrange {@code
     * engine} and {@code workers} under it. Idempotent. A failure is the score alone.
     */
    public static void install() {
        if (state != null) return;
        synchronized (WorkerContainment.class) {
            if (state != null) return;
            state = Os.isLinux()
                    ? activate(Path.of("/"), ProcessHandle.current().pid())
                    : State.none();
            Log.info("jk engine: worker containment " + state.report().text());
        }
    }

    /** The mode {@link #install} settled on. {@link Mode#NONE} before install, including on Linux. */
    public static Report report() {
        State s = state;
        return s == null ? new Report(Mode.NONE, "", -1L, false) : s.report();
    }

    /**
     * Bytes forked workers may use. {@link #BUDGET_ENV} when it is a positive number; otherwise the
     * workers group's {@code memory.max} when containment installed one, otherwise {@link #budget}
     * from the host. A host smaller than the reserve still yields a positive budget so one worker
     * can be clamped down to it.
     */
    public static long budgetBytes() {
        return leaseBudget().bytes();
    }

    /**
     * {@link #budgetBytes()} plus the input that produced it. {@link BudgetSource#OVERRIDE} wins
     * over a cgroup limit: the variable changes what the ledger hands out, not {@code memory.max}.
     */
    public static LeaseBudget leaseBudget() {
        long fromEnv = envBudgetBytes();
        if (fromEnv > 0) return new LeaseBudget(fromEnv, BudgetSource.OVERRIDE);
        State s = state;
        if (s != null && s.mode == Mode.CGROUP && s.max > 0) {
            return new LeaseBudget(s.max, s.budgetSource);
        }
        MemoryProbe.Memory mem = MemoryProbe.current();
        Limits limits = budget(mem.totalBytes(), -1L);
        if (limits != null) return new LeaseBudget(limits.maxBytes(), BudgetSource.HOST);
        return new LeaseBudget(Math.max(32L << 20, mem.totalBytes()), BudgetSource.HOST);
    }

    /**
     * {@link BudgetSource#CGROUP} when {@code enclosingMaxBytes} is a finite limit and it is no
     * larger than {@code memTotalBytes} (or host memory is unknown). Otherwise {@link
     * BudgetSource#HOST}.
     */
    static BudgetSource naturalSource(long memTotalBytes, long enclosingMaxBytes) {
        if (enclosingMaxBytes > 0 && (memTotalBytes <= 0 || enclosingMaxBytes <= memTotalBytes)) {
            return BudgetSource.CGROUP;
        }
        return BudgetSource.HOST;
    }

    /** {@link #BUDGET_ENV} in bytes, or {@code -1} when unset or not a positive integer. */
    static long envBudgetBytes() {
        String raw = System.getenv(BUDGET_ENV);
        if (raw == null || raw.isBlank()) return -1L;
        try {
            long mb = Long.parseLong(raw.trim());
            return mb > 0 ? mb * (1024L * 1024L) : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** The workers directory {@link #install} is using, or null before install and outside cgroup mode. */
    static @Nullable Path workersGroup() {
        State s = state;
        return s == null ? null : s.workers;
    }

    /**
     * Score-only reasons gain {@code note} (the client's scope fallback). Cgroup and none do not:
     * a scope that succeeded has nothing to explain, and a non-Linux host has no cgroup line to amend.
     */
    static String withScopeNote(Mode mode, String detected, String note) {
        if (mode != Mode.SCORE_ONLY) return detected == null ? "" : detected;
        String extra = clean(note);
        String base = detected == null ? "" : detected;
        if (extra.isEmpty()) return base;
        if (base.isEmpty()) return extra;
        return base + "; " + extra;
    }

    /**
     * Raise the child's oom score and, in cgroup mode, move it into {@code workers}: into its own
     * group capped at {@code capBytes} when per-worker groups are on and {@code capBytes} is
     * positive. {@code what} names it if the kernel kills it at that cap. Failures are a debug
     * line. The move is after {@code start}, so the child is briefly still in the engine's cgroup.
     */
    public static void contain(Process process, long capBytes, String what) {
        if (!Os.isLinux()) return;
        long pid = process.pid();
        raiseScore(pid);
        State s = state;
        Path workers = s == null ? null : s.workers;
        if (s == null || s.mode != Mode.CGROUP || workers == null) return;
        Path target = workers;
        if (s.perWorker) {
            Path own = ownGroup(s, workers, pid, capBytes, what);
            target = own != null ? own : workers.resolve(SHARED_CHILD);
        }
        try {
            writeText(target.resolve("cgroup.procs"), Long.toString(pid) + "\n");
        } catch (IOException e) {
            Log.debug("worker containment cgroup move " + pid + ": " + e.getMessage());
        }
    }

    /** Create {@code workers/w<pid>} at {@code capBytes} and track it; null when it cannot be made. */
    private static @Nullable Path ownGroup(State s, Path workers, long pid, long capBytes, String what) {
        if (capBytes <= 0) return null;
        Path dir = workers.resolve(CHILD_PREFIX + pid);
        try {
            Files.createDirectories(dir);
            writeText(dir.resolve("memory.max"), capBytes + "\n");
            capSwap(dir);
        } catch (IOException e) {
            Log.debug("worker containment cap " + pid + ": " + e.getMessage());
            try {
                Files.deleteIfExists(dir);
            } catch (IOException ignored) {
                // nothing joined it; a leftover directory is harmless
            }
            return null;
        }
        synchronized (s) {
            s.children.put(pid, new Child(dir, capBytes, what));
        }
        return dir;
    }

    /**
     * {@link #KILLED_FOR_MEMORY} when {@code exit} is SIGKILL and this workers group has seen an
     * {@code oom_kill} or is at {@code memory.max}; {@link #capPhrase} when a worker was killed at
     * its own cap; otherwise {@code otherwise}. One observation consumes one kill.
     */
    public static String failure(int exit, String otherwise) {
        MemoryKill kill = memoryKill(exit);
        if (kill.atCap()) return capPhrase(kill.capBytes());
        return kill.killed() ? KILLED_FOR_MEMORY : otherwise;
    }

    /** {@code killed for memory at its worker cap of 10.1 GiB; the memory was outside the heap …}. */
    public static String capPhrase(long capBytes) {
        String size = capBytes > 0 ? " of " + WorkerLeases.format(capBytes) : "";
        return KILLED_AT_CAP + size + OUTSIDE_HEAP;
    }

    /** True when {@code exit} is a cgroup memory kill of a worker. Score-only mode has no signal. */
    public static boolean killedForMemory(int exit) {
        return memoryKill(exit).killed();
    }

    /**
     * How a worker that exited was killed. {@code capBytes} is positive when the kernel stopped it
     * at its own cap, {@code -1} for a kill of the shared workers group or no kill.
     */
    public record MemoryKill(boolean killed, long capBytes) {
        static final MemoryKill NONE = new MemoryKill(false, -1L);

        /** Killed at its own per-worker cap. */
        public boolean atCap() {
            return killed && capBytes > 0;
        }
    }

    /**
     * Classify a SIGKILL. A worker group that counted an {@code oom_kill} is a kill at that
     * worker's cap; otherwise a new {@code oom_kill} on {@code workers}, or {@code workers} at its
     * {@code memory.max}, is a group kill. Score-only mode has no signal.
     */
    public static MemoryKill memoryKill(int exit) {
        if (exit != SIGKILL_EXIT) return MemoryKill.NONE;
        State s = state;
        Path workers = s == null ? null : s.workers;
        if (s == null || s.mode != Mode.CGROUP || workers == null) return MemoryKill.NONE;
        synchronized (s) {
            sweep(s);
            Long cap = s.capKills.pollFirst();
            if (cap != null) return new MemoryKill(true, cap);
            long now = readCounter(workers.resolve("memory.events"), "oom_kill");
            boolean increased = now >= 0 && now > s.oomKills;
            if (increased) s.oomKills = now;
            return increased || atMax(workers) ? new MemoryKill(true, -1L) : MemoryKill.NONE;
        }
    }

    /**
     * Settle the groups of workers that have exited. A group whose {@code memory.events} counts an
     * {@code oom_kill} is a cap kill: logged, and queued for {@link #memoryKill}. An empty group is
     * removed; one a lingering descendant still occupies is tried again on the next sweep.
     */
    public static void sweep() {
        State s = state;
        if (s == null || !s.perWorker) return;
        synchronized (s) {
            sweep(s);
        }
    }

    private static void sweep(State s) {
        if (s.children.isEmpty()) return;
        Iterator<Map.Entry<Long, Child>> it = s.children.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Child> entry = it.next();
            long pid = entry.getKey();
            Child child = entry.getValue();
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
            if (!child.settled) {
                child.settled = true;
                long kills = readCounter(child.dir.resolve("memory.events"), "oom_kill");
                if (kills > 0) {
                    // memory.events is hierarchical: the same kill shows on workers, already counted here.
                    s.oomKills += kills;
                    if (s.capKills.size() >= MAX_PENDING_CAP_KILLS) s.capKills.pollFirst();
                    s.capKills.addLast(child.capBytes);
                    Log.warn("jk engine: " + child.what + " pid " + pid + " " + capPhrase(child.capBytes));
                }
            }
            try {
                removeGroup(child.dir);
                it.remove();
            } catch (IOException e) {
                Log.debug("worker containment rmdir " + child.dir + ": " + e.getMessage());
            }
        }
    }

    /**
     * Remove {@code dir} and the groups under it, deepest first. A worker that is itself an engine
     * makes its own groups below its {@code w<pid>}. Only directories are removed: a cgroup's
     * files go with it.
     */
    private static void removeGroup(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> subgroups = new ArrayList<>();
        PathUtil.forEachChild(dir, (child, attrs) -> {
            if (attrs.isDirectory()) subgroups.add(child);
            return true;
        });
        for (Path sub : subgroups) removeGroup(sub);
        Files.deleteIfExists(dir);
    }

    /** Point the live reader at a fixture workers group. Tests reset with {@link #resetForTest}. */
    static void armForTest(Path workers, long maxBytes, long seenOom) {
        armForTest(workers, maxBytes, seenOom, false);
    }

    /** As {@link #armForTest(Path, long, long)}; {@code perWorker} gives each worker its own group. */
    static void armForTest(Path workers, long maxBytes, long seenOom, boolean perWorker) {
        synchronized (WorkerContainment.class) {
            State armed = State.cgroup(workers, new Limits(maxBytes), seenOom);
            armed.perWorker = perWorker;
            state = armed;
        }
    }

    /** Forget {@link #install} / {@link #armForTest}. Does not move the process back. */
    static void resetForTest() {
        synchronized (WorkerContainment.class) {
            state = null;
        }
    }

    private static State activate(Path root, long pid) {
        Plan plan = plan(root, true, pid);
        String note = scopeNote();
        if (plan.mode() != Mode.CGROUP) {
            if (plan.mode() == Mode.SCORE_ONLY)
                return State.scoreOnly(withScopeNote(Mode.SCORE_ONLY, plan.reason(), note));
            return State.of(plan);
        }
        Path own = Objects.requireNonNull(plan.own(), "own");
        Path workers = Objects.requireNonNull(plan.workers(), "workers");
        Limits limits = Objects.requireNonNull(plan.limits(), "limits");
        boolean moved = false;
        try {
            Files.createDirectories(own.resolve("engine"));
            Files.createDirectories(workers);
            if (plan.moveEngine()) {
                try {
                    writeText(own.resolve("engine/cgroup.procs"), pid + "\n");
                    moved = true;
                } catch (IOException e) {
                    Log.debug("worker containment move: " + e.getMessage());
                    return notedScore(note, "could not move the engine into its cgroup");
                }
            }
            if (plan.enableMemory()) {
                try {
                    writeText(own.resolve("cgroup.subtree_control"), "+memory\n");
                } catch (IOException e) {
                    Log.debug("worker containment subtree_control: " + e.getMessage());
                    if (moved) moveBack(own, pid);
                    return notedScore(note, "could not enable the memory controller");
                }
            }
            try {
                writeText(workers.resolve("memory.max"), limits.maxBytes() + "\n");
                capSwap(workers);
            } catch (IOException e) {
                Log.debug("worker containment limits: " + e.getMessage());
                if (moved) moveBack(own, pid);
                return notedScore(note, "could not set the worker memory limit");
            }
            long seen = readCounter(workers.resolve("memory.events"), "oom_kill");
            long memTotal = MemoryProbe.current().totalBytes();
            long enclosing = parseLimit(readQuiet(own.resolve("memory.max")));
            State ready = State.cgroup(workers, limits, seen < 0 ? 0 : seen, naturalSource(memTotal, enclosing));
            ready.perWorker = enablePerWorker(workers);
            return ready;
        } catch (IOException e) {
            Log.debug("worker containment setup: " + e.getMessage());
            if (moved) moveBack(own, pid);
            return notedScore(note, "cannot create a child cgroup");
        }
    }

    /**
     * Turn on the memory controller below {@code workers} so each worker can have its own cap, and
     * make the {@link #SHARED_CHILD} group a worker joins when its own cannot be made. False, and
     * workers share {@code workers}, when that is refused: a group that already holds processes
     * cannot hand the controller down.
     */
    static boolean enablePerWorker(Path workers) {
        try {
            String procs = readQuiet(workers.resolve("cgroup.procs"));
            if (procs != null && !procs.isBlank()) return false;
            writeText(workers.resolve("cgroup.subtree_control"), "+memory\n");
            Files.createDirectories(workers.resolve(SHARED_CHILD));
            return true;
        } catch (IOException e) {
            Log.debug("worker containment per-worker caps: " + e.getMessage());
            return false;
        }
    }

    /** Pin swap at zero so {@code memory.max} cannot be absorbed by swap. Absent when the host has none. */
    private static void capSwap(Path workers) {
        Path swap = workers.resolve("memory.swap.max");
        if (!Files.exists(swap)) return;
        try {
            writeText(swap, "0\n");
        } catch (IOException e) {
            Log.debug("worker containment swap: " + e.getMessage());
        }
    }

    private static State notedScore(String note, String reason) {
        return State.scoreOnly(withScopeNote(Mode.SCORE_ONLY, reason, note));
    }

    /** The client's scope note, one line, capped. Empty when the scope was used or nothing was said. */
    private static String scopeNote() {
        return clean(System.getenv(SCOPE_NOTE));
    }

    /** Collapse whitespace and cap at 160 characters so a status row stays one line. */
    private static String clean(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String collapsed =
                raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        StringBuilder sb = new StringBuilder(collapsed.length());
        boolean pendingSpace = false;
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == ' ') {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) sb.append(' ');
            pendingSpace = false;
            sb.append(c);
        }
        String s = sb.toString();
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }

    private static void moveBack(Path own, long pid) {
        try {
            writeText(own.resolve("cgroup.procs"), pid + "\n");
        } catch (IOException e) {
            Log.debug("worker containment rollback: " + e.getMessage());
        }
    }

    private static void raiseScore(long pid) {
        Path file = Path.of("/proc", Long.toString(pid), "oom_score_adj");
        try {
            String cur = Files.readString(file).trim();
            int now = Integer.parseInt(cur);
            if (now >= OOM_SCORE_ADJ) return;
            writeText(file, OOM_SCORE_ADJ + "\n");
        } catch (IOException | RuntimeException e) {
            Log.debug("oom_score_adj " + pid + ": " + e.getMessage());
        }
    }

    private static boolean atMax(Path workers) {
        long max = parseLimit(readQuiet(workers.resolve("memory.max")));
        if (max <= 0) return false;
        long current = parseLimit(readQuiet(workers.resolve("memory.current")));
        return current >= max;
    }

    /** {@code -1} when the file or the key is absent. */
    private static long readCounter(Path events, String key) {
        String text = readQuiet(events);
        if (text == null) return -1L;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2 || !key.equals(parts[0])) continue;
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        return -1L;
    }

    private static boolean canCreateChild(Path own) {
        if (!Files.isWritable(own)) return false;
        Path probe = own.resolve(PROBE_DIR);
        try {
            Files.createDirectory(probe);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                Log.debug("worker containment probe: " + e.getMessage());
            }
        }
    }

    /** The cgroup v2 line is hierarchy {@code 0}; v1 lines name a controller in the middle field. */
    private static boolean isUnifiedHierarchy(String hierarchy) {
        return hierarchy.length() == 1 && hierarchy.charAt(0) == '0';
    }

    private static @Nullable String cgroupPath(Path file) {
        String text = readQuiet(file);
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length == 3 && isUnifiedHierarchy(parts[0])) {
                String path = parts[2].trim();
                return path.isEmpty() ? "/" : path;
            }
        }
        return null;
    }

    /** {@code -1} for absent, blank, {@code max}, or a value the kernel uses as unlimited. */
    private static long parseLimit(@Nullable String text) {
        if (text == null) return -1L;
        String s = text.trim();
        if (s.isEmpty() || "max".equals(s)) return -1L;
        try {
            long v = Long.parseLong(s);
            if (v <= 0 || v >= MemoryProbe.CGROUP_UNLIMITED) return -1L;
            return v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Null when the file cannot be read. An empty file is an empty string. Reads through an
     * {@link InputStream}: cgroup and proc files often report a size of zero, which a size-based
     * read treats as empty.
     */
    private static @Nullable String readQuiet(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Null when the file cannot be read; empty when it lists nobody. */
    private static @Nullable List<Long> pids(@Nullable String text) {
        if (text == null) return null;
        List<Long> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static boolean hasWord(String text, String word) {
        for (String w : text.trim().split("\\s+")) {
            if (word.equals(w)) return true;
        }
        return false;
    }

    private static void writeText(Path file, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Plan scoreOnly(String reason) {
        return new Plan(Mode.SCORE_ONLY, reason, null, false, false, null, null);
    }

    private static final class State {
        final Mode mode;
        final String reason;
        final long max;
        final @Nullable Path workers;
        final BudgetSource budgetSource;
        long oomKills;
        /** Each worker runs in its own capped group under {@link #workers}. */
        boolean perWorker;

        /** Per-worker groups by pid, until a sweep removes them. */
        final Map<Long, Child> children = new LinkedHashMap<>();

        /** Caps of workers killed at their cap, oldest first, not yet attributed to an exit. */
        final ArrayDeque<Long> capKills = new ArrayDeque<>();

        private State(
                Mode mode, String reason, long max, @Nullable Path workers, long oomKills, BudgetSource budgetSource) {
            this.mode = mode;
            this.reason = reason;
            this.max = max;
            this.workers = workers;
            this.oomKills = oomKills;
            this.budgetSource = budgetSource;
        }

        static State none() {
            return new State(Mode.NONE, "", -1L, null, 0L, BudgetSource.HOST);
        }

        static State scoreOnly(String reason) {
            return new State(Mode.SCORE_ONLY, reason, -1L, null, 0L, BudgetSource.HOST);
        }

        static State of(Plan plan) {
            if (plan.mode() == Mode.NONE) return none();
            if (plan.mode() == Mode.SCORE_ONLY) return scoreOnly(plan.reason());
            Limits limits = Objects.requireNonNull(plan.limits(), "limits");
            return cgroup(plan.workers(), limits, 0L);
        }

        static State cgroup(@Nullable Path workers, Limits limits, long seen) {
            return cgroup(workers, limits, seen, BudgetSource.HOST);
        }

        static State cgroup(@Nullable Path workers, Limits limits, long seen, BudgetSource source) {
            return new State(Mode.CGROUP, "", limits.maxBytes(), workers, seen, source);
        }

        Report report() {
            return new Report(mode, reason, max, perWorker);
        }
    }

    /** A worker's own group. {@code settled}: its kill count was read after the worker exited. */
    private static final class Child {
        final Path dir;
        final long capBytes;
        final String what;
        boolean settled;

        Child(Path dir, long capBytes, String what) {
            this.dir = dir;
            this.capBytes = capBytes;
            this.what = what;
        }
    }
}
