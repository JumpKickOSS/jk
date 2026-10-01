// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.PluginTunings;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PreferIpv4;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * JVM flags for forked worker processes (compilers, test runners, …). Concurrent workers share a
 * RAM budget ({@link #DEFAULT_MAX_RAM_PERCENT} divided by concurrency). Tuning comes from the
 * request {@link cc.jumpkick.config.Session} via {@link #tuning()} (CLI &gt; env &gt; {@code [jvm]}
 * &gt; default).
 */
public final class JvmOptions {

    private JvmOptions() {}

    /** Conservative default: a worker plus the resident CLI fit under it. */
    public static final double DEFAULT_MAX_RAM_PERCENT = 50.0;

    /** JVM default collector (usually G1). Override with {@code [jvm] gc}. */
    public static final String DEFAULT_GC = "default";

    /**
     * Batch GC for jk-owned forks (compilers/plugins). Test JVMs always run the JVM's own default;
     * only flags the user wrote ({@code [test] jvm-args}, {@code [jvm] args}) choose theirs.
     */
    public static final String BATCH_DEFAULT_GC = "parallel";

    /**
     * First JDK feature that accepts {@code --sun-misc-unsafe-memory-access} (JEP 498). Older hosts
     * abort with "Unrecognized option" if the flag is present.
     */
    public static final int JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE = 23;

    /** Acknowledges memory-access {@code sun.misc.Unsafe} use on JDK ≥ 23 batch hosts. */
    public static final String JEP498_UNSAFE_MEMORY_ACCESS_ALLOW = "--sun-misc-unsafe-memory-access=allow";

    /**
     * Metaspace cap for jk-owned batch workers, outside the heap budget (avoids concurrent-worker
     * native overcommit). Test suites never get it — see {@link #suiteFlags}: a framework that
     * keeps an augmented application per test profile resident fills any cap jk could pick.
     */
    public static final long DEFAULT_MAX_METASPACE_MB = 256;

    /**
     * Thread stack for jk-owned batch workers (compilers, plugin tools): they rarely need deep
     * stacks, so a smaller reserve buys headroom. Test suites never get it — see {@link
     * #suiteFlags}.
     */
    public static final long DEFAULT_STACK_KB = 512;

    /** A sole worker leases at most one of this many parts of the worker budget. */
    static final int SOLE_WORKER_BUDGET_SHARE = 4;

    /** The sole worker's lease is not cut below this while the budget holds it. */
    static final long SOLE_WORKER_LEASE_FLOOR = 1L << 30;

    /** Which kind of fork the flags are for: one of jk's own batch tools, or a test JVM. */
    private enum Role {
        /**
         * {@code -Xss} at {@link #DEFAULT_STACK_KB}, the metaspace cap, {@code
         * ExitOnOutOfMemoryError}, and a CPU share when the fork's collector is named, each unless
         * the tuning pins it.
         */
        BATCH,
        /**
         * The platform defaults, as Surefire's and Gradle's test forks run: no {@code -Xss}, no
         * metaspace cap, no {@code ExitOnOutOfMemoryError}, no {@code ActiveProcessorCount} and no
         * collector flag. {@code [jvm] gc} does not reach it.
         */
        SUITE
    }

    /**
     * Build the JVM flag list for {@code settings}, dividing the heap cap across {@code concurrency}
     * simultaneously-launched JVMs (pass {@code 1} for a lone worker; the test-runner passes its
     * worker count).
     */
    public static List<String> flags(PluginTuning settings, int concurrency) {
        return flags(settings, concurrency, DEFAULT_GC, Role.BATCH, List.of());
    }

    /**
     * {@code userArgs} are the flags that follow these on the fork ({@code [test] jvm-args}). A heap
     * pinned there or in the tuning's own args leaves out jk's {@code MaxRAMPercentage}.
     */
    private static List<String> flags(
            PluginTuning settings, int concurrency, String defaultGc, Role role, List<String> userArgs) {
        PluginTuning s = settings == null ? PluginTuning.NONE : settings;
        double perJvm = ramPercent(s.maxRamPercent()) / Math.max(1, concurrency);
        String gc = collector(s, defaultGc, role);

        List<String> out = new ArrayList<>();
        if (!anyPinsHeap(s.extraArgs()) && !anyPinsHeap(userArgs)) {
            out.add("-XX:MaxRAMPercentage=" + fmt(perJvm));
        }
        addCollector(out, s, gc, false);
        addHardening(out, s, concurrency, role, gc);
        out.addAll(s.extraArgs());
        return out;
    }

    /**
     * {@code requested} within the (0, 100] HotSpot accepts: above 100 is all of RAM, and none, zero
     * or less is {@link #DEFAULT_MAX_RAM_PERCENT}. A JVM refuses to start on a value outside it.
     */
    static double ramPercent(@Nullable Double requested) {
        if (requested == null || !(requested > 0)) return DEFAULT_MAX_RAM_PERCENT;
        return Math.min(100.0, requested);
    }

    /** The collector a fork's flags name: the tuning's, else {@code defaultGc}. A test JVM's is the JVM default. */
    private static String collector(PluginTuning s, String defaultGc, Role role) {
        if (role == Role.SUITE) return DEFAULT_GC;
        return (s.gc() != null ? s.gc() : defaultGc).toLowerCase(Locale.ROOT);
    }

    /**
     * The flag for {@code gc} (none for {@code default}, {@code none} or an unrecognized name), then
     * string deduplication where it has an effect (G1, ZGC). {@code uncommit} adds ZGC's uncommit.
     */
    private static void addCollector(List<String> out, PluginTuning s, String gc, boolean uncommit) {
        switch (gc) {
            case "zgc" -> {
                out.add("-XX:+UseZGC");
                if (uncommit) {
                    out.add("-XX:+ZUncommit");
                    out.add("-XX:ZUncommitDelay=" + ZGC_UNCOMMIT_DELAY_SECONDS);
                }
            }
            case "g1" -> out.add("-XX:+UseG1GC");
            case "parallel" -> out.add("-XX:+UseParallelGC");
            case "serial" -> out.add("-XX:+UseSerialGC");
            default -> {
                /* the JVM's own default */
            }
        }
        boolean dedup = s.stringDedup() == null || s.stringDedup();
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) out.add("-XX:+UseStringDeduplication");
    }

    /**
     * The request-scoped worker tuning, read from the current {@link cc.jumpkick.config.Session}
     * and nowhere else. The client folds {@code --jvm-arg}/{@code --ram-percent} and their
     * {@code JK_JVM_*} spellings into the request, so a session that carries none means the caller
     * asked for none — not that the daemon's own environment should answer. The engine outlives the
     * shell that started it, and reading that shell's {@code JK_JVM_ARGS} here handed its flags to
     * every later terminal until {@code jk engine stop}.
     */
    private static PluginTuning tuning() {
        var session = SessionContext.current();
        PluginTuning t = session.jvm();
        PluginTuning base = t == null ? PluginTuning.NONE : t;
        // The jk.toml [jvm] table overlays here, at fork time, engine-side (thin-client contract):
        // the session carries only the client's flag/env layers, so a client of any age gets
        // current-engine [jvm] interpretation.
        return PluginTunings.overlayProject(base, session.workingDir());
    }

    /**
     * Worker-fork JVM flags for {@code concurrency} simultaneously-launched JVMs, built from the
     * request's {@linkplain #tuning() tuning} — so a {@code --ram-percent} flag or a {@code [jvm]}
     * table reaches the worker.
     *
     * <p>When a {@linkplain #processHeapPlan() heap plan} is in effect (the default — no explicit
     * heap tuning), absolute {@code -Xms}/{@code -Xmx}/ {@code -XX:SoftMaxHeapSize} from the plan
     * replace the relative {@code MaxRAMPercentage}; the plan already accounts for how many JVMs run
     * at once, so {@code concurrency} is ignored in that case.
     */
    public static List<String> workerFlags(int concurrency) {
        return workerFlags(concurrency, DEFAULT_GC, Role.BATCH, List.of());
    }

    /**
     * Flags for the JVMs that run a module's test suite, placed before {@code testJvmArgs} (the
     * plugins' arguments, {@code [test] jvm-args} and system properties, the profile's {@code
     * jvm-args}). jk plans the heap and leaves everything else the JVM chooses for itself: no
     * {@code -Xss}, no metaspace cap, no {@code -XX:+ExitOnOutOfMemoryError}, no {@code
     * -XX:ActiveProcessorCount} and no collector, so the suite runs the JVM's default GC (G1 on a
     * host with two or more CPUs) and sees every core, as under Surefire and Gradle. A test may
     * provoke an {@link OutOfMemoryError} and catch it. When {@code testJvmArgs} pin a heap ({@link
     * #pinsHeap}), jk adds no heap flag of its own. The user's {@code [jvm] args} still apply.
     */
    public static List<String> suiteFlags(int concurrency, List<String> testJvmArgs) {
        return workerFlags(concurrency, DEFAULT_GC, Role.SUITE, testJvmArgs == null ? List.of() : testJvmArgs);
    }

    /**
     * {@link #workerFlags} with the {@linkplain #BATCH_DEFAULT_GC batch collector} as the GC
     * default — for jk-owned batch forks (compilers, plugin tools), never test workers. An
     * explicit {@code [jvm] gc} still wins.
     *
     * <p>When the host JVM is feature ≥ {@link #JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE}, also
     * acknowledges JEP 498 memory-access {@code sun.misc.Unsafe} use so annotation processors and
     * compiler hosts (Lombok, KSP's IntelliJ containers, …) do not flood stderr with HotSpot's
     * terminal-deprecation banner. The running engine feature is assumed (engine-hosted workers).
     * For a different host (e.g. project-pinned {@code javac}), use {@link #batchFlags(int, int)}.
     */
    public static List<String> batchFlags(int concurrency) {
        return batchFlags(concurrency, Runtime.version().feature());
    }

    /**
     * As {@link #batchFlags(int)}, but sizes the JEP 498 allow flag for {@code hostFeature} — the
     * feature major of the JVM that will actually start (from {@code release} / {@link
     * Runtime#version()}). Hosts below {@link #JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE} never get
     * the flag.
     */
    public static List<String> batchFlags(int concurrency, int hostFeature) {
        List<String> out = new ArrayList<>(workerFlags(concurrency, BATCH_DEFAULT_GC, Role.BATCH, List.of()));
        appendJep498AllowIfSupported(out, hostFeature);
        return out;
    }

    /**
     * Feature major of {@code javaHome} from its {@code release} file ({@code JAVA_VERSION}), or
     * {@link Runtime#version()} when the file is missing or unreadable.
     */
    public static int hostFeature(Path javaHome) {
        Integer fromRelease = featureFromRelease(javaHome);
        return fromRelease != null ? fromRelease : Runtime.version().feature();
    }

    private static void appendJep498AllowIfSupported(List<String> out, int hostFeature) {
        if (hostFeature < JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE) return;
        if (!hasArgPrefix(out, "--sun-misc-unsafe-memory-access")) {
            out.add(JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        }
    }

    private static @Nullable Integer featureFromRelease(@Nullable Path javaHome) {
        if (javaHome == null) return null;
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String raw = props.getProperty("JAVA_VERSION", "").trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) return null;
        try {
            return Integer.parseInt(raw.split("[.+-]")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> workerFlags(int concurrency, String defaultGc, Role role, List<String> userArgs) {
        PluginTuning s = tuning();
        HeapPlan.Plan plan = processHeapPlan();
        if (plan != null && autoHeapEnabled(s)) return absoluteFlags(plan, s, defaultGc, role, userArgs);
        return flags(s, concurrency, defaultGc, role, userArgs);
    }

    /** Process-wide heap budget from {@link #planAndApply}, or null when explicit tuning wins. */
    private static volatile HeapPlan.@Nullable Plan heapPlan;

    /**
     * Probe memory, compute the heap budget for {@code requestedJvms} desired forks, and stash it
     * for {@link #workerFlags}. Each worker's {@code -Xmx} is then lowered, when it has to be, so
     * that many leases fit in {@link WorkerLeases#engine()}. A no-op (returns {@code null}) when the
     * request supplied explicit heap tuning — those settings then drive sizing as before.
     */
    public static HeapPlan.@Nullable Plan planAndApply(int requestedJvms) {
        return planAndApply(requestedJvms, WorkerLeases.engine());
    }

    /** As {@link #planAndApply(int)}, fitting the plan into {@code leases}. */
    public static HeapPlan.@Nullable Plan planAndApply(int requestedJvms, WorkerLeases.Ledger leases) {
        if (!autoHeapEnabled(tuning())) {
            heapPlan = null;
            return null;
        }
        HeapPlan.Plan plan =
                fitWorkerBudget(HeapPlan.compute(MemoryProbe.probe().availableBytes(), requestedJvms), leases);
        heapPlan = plan;
        return plan;
    }

    /**
     * {@code plan} with parallelism lowered until that many copies of its heap fit in {@link
     * WorkerLeases#engine()}. The heap itself shrinks only when one copy is already bigger than the
     * budget, so a tight budget queues forks instead of handing every worker a heap too small to run.
     */
    public static HeapPlan.Plan fitWorkerBudget(HeapPlan.Plan plan) {
        return fitWorkerBudget(plan, WorkerLeases.engine());
    }

    /** As {@link #fitWorkerBudget(HeapPlan.Plan)}, measured against {@code leases}. */
    public static HeapPlan.Plan fitWorkerBudget(HeapPlan.Plan plan, WorkerLeases.Ledger leases) {
        long budget = leases.capacityBytes();
        if (budget <= 0) return plan;
        int n = Math.max(1, plan.parallelism());
        long xmx = Math.max(0, plan.xmxBytes());
        if (xmx <= 0 || WorkerLeases.jvmLease(xmx) > budget) {
            xmx = WorkerLeases.clampXmx(xmx, budget);
            n = 1;
        }
        while (n > 1 && (long) n * WorkerLeases.jvmLease(xmx) > budget) n--;
        if (n == plan.parallelism() && xmx == plan.xmxBytes()) return plan;
        long soft = Math.min(plan.softMaxBytes(), xmx);
        long xms = Math.min(plan.xmsBytes(), xmx);
        return new HeapPlan.Plan(n, xms, soft, xmx, plan.warning());
    }

    /**
     * The heap of a sole worker: one JVM's share of {@code availableBytes}, leasing at most {@link
     * #SOLE_WORKER_BUDGET_SHARE} of the worker budget (never less than {@link
     * #SOLE_WORKER_LEASE_FLOOR} while the budget holds that). The engine is shared, so a lease the
     * size of the whole budget would wait for every other fork to end and hold back every fork
     * queued behind it.
     */
    static HeapPlan.Plan soleWorkerPlan(long availableBytes, WorkerLeases.Ledger leases) {
        HeapPlan.Plan plan = fitWorkerBudget(HeapPlan.compute(availableBytes, 1), leases);
        long budget = leases.capacityBytes();
        if (budget <= 0) return plan;
        long share = Math.max(budget / SOLE_WORKER_BUDGET_SHARE, Math.min(budget, SOLE_WORKER_LEASE_FLOOR));
        if (WorkerLeases.jvmLease(plan.xmxBytes()) <= share) return plan;
        long xmx = WorkerLeases.clampXmx(plan.xmxBytes(), share);
        return new HeapPlan.Plan(
                1, Math.min(plan.xmsBytes(), xmx), Math.min(plan.softMaxBytes(), xmx), xmx, plan.warning());
    }

    /** The applied heap budget, or {@code null} if none (explicit tuning / not yet planned). */
    public static HeapPlan.@Nullable Plan processHeapPlan() {
        return heapPlan;
    }

    /**
     * Heap and CPU for a fork that is the <em>only</em> worker its command runs — append after
     * {@link #batchFlags}, whose values these deliberately override (last flag wins on HotSpot).
     * The process-wide plan is sized for {@code jobs} concurrent JVMs, so a command that forks one
     * worker otherwise gets a twentieth of a twenty-core host. It gets every core, and the heap of
     * {@link #soleWorkerPlan}. Empty when the user pinned memory.
     */
    public static List<String> soleWorkerFlags() {
        PluginTuning s = tuning();
        if (!autoHeapEnabled(s)) return List.of();
        HeapPlan.Plan plan = soleWorkerPlan(MemoryProbe.probe().availableBytes(), WorkerLeases.engine());
        List<String> out = new ArrayList<>();
        out.add("-Xms" + HeapPlan.mib(plan.xmsBytes()) + "m");
        out.add("-Xmx" + HeapPlan.mib(plan.xmxBytes()) + "m");
        String gc = (s.gc() != null ? s.gc() : BATCH_DEFAULT_GC).toLowerCase(Locale.ROOT);
        if (!gc.equals("none")) {
            out.add("-XX:SoftMaxHeapSize=" + HeapPlan.mib(plan.softMaxBytes()) + "m");
        }
        out.add("-XX:ActiveProcessorCount=" + Math.max(1, Runtime.getRuntime().availableProcessors()));
        return out;
    }

    /**
     * Test-only: undo {@link #planAndApply}. Production code never calls this (a real process's plan
     * is meant to live for the process's whole lifetime); it exists because a test that spins up a
     * real {@code EngineServer} (which calls {@code planAndApply} as a side effect of starting)
     * would otherwise leak that process-wide static into unrelated tests sharing the same test JVM.
     */
    public static void resetSharedPlanForTests() {
        heapPlan = null;
    }

    /**
     * True when jk should auto-size worker heaps: the request pinned neither a {@code
     * --ram-percent} / {@code [jvm] max-ram-percent} nor an explicit heap flag ({@link #pinsHeap})
     * via {@code --jvm-arg} / {@code [jvm] args}.
     */
    public static boolean autoHeapEnabled() {
        return autoHeapEnabled(tuning());
    }

    private static boolean autoHeapEnabled(PluginTuning s) {
        if (s.maxRamPercent() != null) return false;
        for (String a : s.extraArgs()) {
            if (pinsHeap(a)) return false;
        }
        return true;
    }

    /**
     * Who chose the {@code -Xmx} on a fork, read off the command before an argfile shorten can hide
     * the flags. {@link JobWorkers} rewrites a planned heap that does not fit the budget and leaves
     * a user pin unchanged.
     *
     * @param userPinned the user wrote this heap ({@code --ram-percent}, {@code [jvm] args},
     *     {@code [test] jvm-args}, a module {@code -J} flag, or another caller {@code -Xmx} /
     *     {@code -XX:MaxRAM*})
     * @param xmxBytes the {@code -Xmx} in force, or {@code -1} when the command names none
     * @param label the pin, for the engine log; empty when {@code userPinned} is false
     */
    public record HeapChoice(boolean userPinned, long xmxBytes, String label) {

        /** {@code command} as jk is about to run it, argfile shorten not yet applied. */
        public static HeapChoice inspect(List<String> command) {
            long xmx = WorkerLeases.parseXmx(command);
            if (!userPinnedHeap(command)) return new HeapChoice(false, xmx, "");
            return new HeapChoice(true, xmx, userPinLabel(command));
        }
    }

    /**
     * {@code -Xmx} values jk chose for a worker after the process-wide plan, so the budget may lower
     * them. A flag the user wrote is not recorded here.
     */
    private static final Set<String> PLANNED_HEAPS = ConcurrentHashMap.newKeySet();

    /**
     * Record a heap flag jk chose so the budget may lower it. A flag the user wrote is not recorded
     * here; {@link #userPinnedHeap} treats an unrecorded heap flag as theirs.
     */
    public static void notePlannedHeap(String flag) {
        if (flag == null || flag.isEmpty()) return;
        String bare = bareJvmArg(flag);
        if (pinsHeap(bare)) PLANNED_HEAPS.add(bare);
    }

    /** {@link #notePlannedHeap} for every heap flag on a command jk just rewrote. */
    public static void notePlannedCommand(List<String> command) {
        if (command == null) return;
        for (String arg : command) notePlannedHeap(arg);
    }

    public static void forgetPlannedHeapForTests(String flag) {
        if (flag != null) PLANNED_HEAPS.remove(bareJvmArg(flag));
    }

    /**
     * True when the heap on {@code command} belongs to the user. The session pin ({@code
     * --ram-percent}, {@code [jvm] args} with a heap flag) is one case. A heap flag jk did not emit
     * — {@code [test] jvm-args}, a module {@code -J} flag, or any other caller {@code -Xmx} /
     * {@code -XX:MaxRAM*} — is the other.
     */
    public static boolean userPinnedHeap(List<String> command) {
        if (!autoHeapEnabled()) return true;
        if (command == null || !containsHeapFlag(command)) return false;
        Set<String> planned = plannedHeapFlags();
        for (String arg : command) {
            String bare = bareJvmArg(arg);
            if (pinsHeap(bare) && !planned.contains(bare)) return true;
        }
        return false;
    }

    /**
     * True when {@code arg} sets a heap or a RAM ceiling, with or without a launcher {@code -J}
     * prefix: {@code -Xmx}, {@code -Xms}, {@code -XX:MaxHeapSize}, {@code -XX:SoftMaxHeapSize},
     * {@code -XX:MaxRAM*}.
     */
    public static boolean pinsHeap(String arg) {
        if (arg == null || arg.isEmpty()) return false;
        String bare = bareJvmArg(arg);
        return bare.startsWith("-Xmx")
                || bare.startsWith("-Xms")
                || bare.startsWith("-XX:MaxHeapSize")
                || bare.startsWith("-XX:MinHeapSize")
                || bare.startsWith("-XX:InitialHeapSize")
                || bare.startsWith("-XX:SoftMaxHeapSize")
                || bare.startsWith("-XX:MaxRAM");
    }

    /** The user's pin as the log should name it: the flag they wrote, or {@code --ram-percent}. */
    public static String userPinLabel(List<String> command) {
        Set<String> planned = autoHeapEnabled() && command != null ? plannedHeapFlags() : Set.of();
        String last = null;
        String lastUnplanned = null;
        if (command != null) {
            for (String arg : command) {
                String bare = bareJvmArg(arg);
                if (!pinsHeap(bare)) continue;
                last = bare;
                if (!planned.contains(bare)) lastUnplanned = bare;
            }
        }
        if (lastUnplanned != null) return lastUnplanned;
        if (last != null) return last;
        PluginTuning s = tuning();
        if (s.maxRamPercent() != null) return "--ram-percent " + fmt(s.maxRamPercent());
        return "heap";
    }

    private static boolean containsHeapFlag(List<String> command) {
        for (String arg : command) {
            if (pinsHeap(arg)) return true;
        }
        return false;
    }

    /** Heap flags the plan, the batch/suite/sole forks, and {@link #notePlannedHeap} emit right now. */
    private static Set<String> plannedHeapFlags() {
        Set<String> out = new HashSet<>(PLANNED_HEAPS);
        collectHeapFlags(out, workerFlags(1));
        collectHeapFlags(out, batchFlags(1));
        collectHeapFlags(out, suiteFlags(1, List.of()));
        collectHeapFlags(out, soleWorkerFlags());
        return out;
    }

    private static void collectHeapFlags(Set<String> out, List<String> flags) {
        for (String flag : flags) {
            String bare = bareJvmArg(flag);
            if (pinsHeap(bare)) out.add(bare);
        }
    }

    /** Strip one launcher {@code -J} prefix. {@code -javaagent} is not one. */
    private static String bareJvmArg(String arg) {
        if (arg.startsWith("-J") && arg.length() > 2 && arg.charAt(2) == '-') return arg.substring(2);
        return arg;
    }

    /**
     * Absolute-heap worker flags from {@code plan}: small {@code -Xms}, a {@code SoftMaxHeapSize}
     * good-neighbour target, an {@code -Xmx} burst cap, and the collector (default: the JVM's own,
     * i.e. G1 on server-class machines; an explicit {@code gc = "zgc"} adds {@code ZUncommit} so
     * idle heap is returned to the OS). {@code SoftMaxHeapSize} is emitted except under an explicit
     * {@code gc = "none"} — G1 and ZGC honour it, everything else recognizes and ignores it.
     */
    static List<String> absoluteFlags(HeapPlan.Plan plan, PluginTuning s) {
        return absoluteFlags(plan, s, DEFAULT_GC, Role.BATCH, List.of());
    }

    static List<String> absoluteFlags(HeapPlan.Plan plan, PluginTuning s, String defaultGc) {
        return absoluteFlags(plan, s, defaultGc, Role.BATCH, List.of());
    }

    /** {@link #suiteFlags} under {@code plan}, for {@code testJvmArgs}. */
    static List<String> suiteAbsoluteFlags(HeapPlan.Plan plan, PluginTuning s, List<String> testJvmArgs) {
        return absoluteFlags(plan, s, DEFAULT_GC, Role.SUITE, testJvmArgs);
    }

    /** {@code userArgs} that pin a heap leave out the plan's heap flags. */
    private static List<String> absoluteFlags(
            HeapPlan.Plan plan, PluginTuning s, String defaultGc, Role role, List<String> userArgs) {
        String gc = collector(s, defaultGc, role);
        boolean softMaxAware = !gc.equals("none");

        List<String> out = new ArrayList<>();
        if (!anyPinsHeap(userArgs)) {
            out.add("-Xms" + HeapPlan.mib(plan.xmsBytes()) + "m");
            out.add("-Xmx" + HeapPlan.mib(plan.xmxBytes()) + "m");
            if (softMaxAware) out.add("-XX:SoftMaxHeapSize=" + HeapPlan.mib(plan.softMaxBytes()) + "m");
        }
        addCollector(out, s, gc, true);
        addHardening(out, s, plan.parallelism(), role, gc);
        out.addAll(s.extraArgs());
        return out;
    }

    private static boolean anyPinsHeap(List<String> args) {
        for (String a : args) {
            if (pinsHeap(a)) return true;
        }
        return false;
    }

    /** How long ZGC waits on an idle heap before returning pages to the OS — tuned for aggressive give-back. */
    static final int ZGC_UNCOMMIT_DELAY_SECONDS = 10;

    /**
     * IPv4 preference, and for a batch worker the metaspace cap, the CPU share, the stack and {@code
     * ExitOnOutOfMemoryError}, each unless already set in {@code extraArgs}. The CPU share is added
     * only when the fork's collector is named ({@code gc}, or a {@code -XX:+Use…GC} in {@code
     * extraArgs}): HotSpot picks SerialGC for one CPU, and the share bounds thread counts without
     * choosing the collector.
     */
    private static void addHardening(List<String> out, PluginTuning s, int concurrency, Role role, String gc) {
        List<String> extra = s.extraArgs();
        if (role == Role.BATCH && !hasArgPrefix(extra, "-XX:MaxMetaspaceSize", "-XX:MetaspaceSize")) {
            out.add("-XX:MaxMetaspaceSize=" + DEFAULT_MAX_METASPACE_MB + "m");
        }
        if (role == Role.BATCH && namesCollector(gc, extra) && !hasArgPrefix(extra, "-XX:ActiveProcessorCount")) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(1, concurrency));
            out.add("-XX:ActiveProcessorCount=" + cores);
        }
        if (role == Role.BATCH && !hasArgPrefix(extra, "-Xss")) {
            out.add("-Xss" + DEFAULT_STACK_KB + "k");
        }
        if (!hasArgPrefix(extra, "-D" + PreferIpv4.PROPERTY)) {
            out.add(PreferIpv4.JVM_FLAG);
        }
        if (role == Role.BATCH
                && !hasArgPrefix(
                        extra,
                        "-XX:+ExitOnOutOfMemoryError",
                        "-XX:-ExitOnOutOfMemoryError",
                        "-XX:+CrashOnOutOfMemoryError")) {
            out.add("-XX:+ExitOnOutOfMemoryError");
        }
    }

    /** True when {@code gc} or a flag in {@code extra} selects a collector. */
    private static boolean namesCollector(String gc, List<String> extra) {
        if (gc.equals("zgc") || gc.equals("g1") || gc.equals("parallel") || gc.equals("serial")) return true;
        for (String a : extra) {
            if (a.startsWith("-XX:+Use") && a.endsWith("GC")) return true;
        }
        return false;
    }

    private static boolean hasArgPrefix(List<String> args, String... prefixes) {
        for (String a : args) {
            for (String p : prefixes) {
                if (a.startsWith(p)) return true;
            }
        }
        return false;
    }

    /**
     * {@link #batchFlags(int)}, each flag {@code -J}-prefixed for launcher tools that wrap their own
     * JVM (javac, native-image) rather than being exec'd as {@code java} directly. Uses the running
     * engine feature; prefer {@link #launcherFlags(int, int)} when the launcher host differs (project
     * pin).
     */
    public static List<String> launcherFlags(int concurrency) {
        return launcherFlags(concurrency, Runtime.version().feature());
    }

    /** As {@link #launcherFlags(int)} for a known host feature major. */
    public static List<String> launcherFlags(int concurrency, int hostFeature) {
        List<String> out = new ArrayList<>();
        for (String f : batchFlags(concurrency, hostFeature)) out.add("-J" + f);
        return out;
    }

    /**
     * Assemble a worker JVM command line: {@code javaExe}, then the tuning flags ({@link
     * #batchFlags}), then {@code rest} (e.g. {@code -cp <jar> Main <spec>}). For forks not driven by
     * {@link cc.jumpkick.engine.plugin.PluginLoader} — the compiler/git plugins and the CLI's standalone
     * plugin commands. The JDK is named by its HOME: the launcher comes from {@link
     * JdkFingerprint#java} and the feature major from the home's own {@code release} file, so
     * neither is recovered by walking up from an executable path.
     */
    public static List<String> javaCommand(@Nullable Path javaHome, int concurrency, List<String> rest) {
        int feature =
                javaHome != null ? hostFeature(javaHome) : Runtime.version().feature();
        List<String> cmd = new ArrayList<>();
        cmd.add(
                javaHome != null
                        ? JdkFingerprint.java(javaHome).toString()
                        : JdkFingerprint.java(JavaHomes.runningJavaHome()).toString());
        cmd.addAll(batchFlags(concurrency, feature));
        cmd.addAll(rest);
        return cmd;
    }

    // ---- helpers --------------------------------------------------------

    /** Whole numbers render without a trailing {@code .0} ({@code 50}, not {@code 50.0}). */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
