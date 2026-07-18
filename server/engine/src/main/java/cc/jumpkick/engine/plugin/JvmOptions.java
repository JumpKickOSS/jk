// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * Batch GC for jk-owned forks (compilers/plugins). Test workers keep {@link #DEFAULT_GC} so
     * user code sees the same collector as other runners.
     */
    public static final String BATCH_DEFAULT_GC = "parallel";

    /** Metaspace cap outside the heap budget (avoids concurrent-worker native overcommit). */
    public static final long DEFAULT_MAX_METASPACE_MB = 256;

    /** Worker JVMs (compilers, test runners) rarely need deep stacks; smaller reserve, more headroom. */
    public static final long DEFAULT_STACK_KB = 512;

    /**
     * Build the JVM flag list for {@code settings}, dividing the heap cap across {@code concurrency}
     * simultaneously-launched JVMs (pass {@code 1} for a lone worker; the test-runner passes its
     * worker count).
     */
    public static List<String> flags(PluginTuning settings, int concurrency) {
        return flags(settings, concurrency, DEFAULT_GC);
    }

    private static List<String> flags(PluginTuning settings, int concurrency, String defaultGc) {
        PluginTuning s = settings == null ? PluginTuning.NONE : settings;
        double base = s.maxRamPercent() != null ? s.maxRamPercent() : DEFAULT_MAX_RAM_PERCENT;
        double perJvm = base / Math.max(1, concurrency);
        String gc = (s.gc() != null ? s.gc() : defaultGc).toLowerCase(Locale.ROOT);
        boolean dedup = s.stringDedup() == null || s.stringDedup();

        List<String> out = new ArrayList<>();
        out.add("-XX:MaxRAMPercentage=" + fmt(perJvm));
        switch (gc) {
            case "zgc" -> out.add("-XX:+UseZGC");
            case "g1" -> out.add("-XX:+UseG1GC");
            case "parallel" -> out.add("-XX:+UseParallelGC");
            case "serial" -> out.add("-XX:+UseSerialGC");
            case "none", "default", "" -> {
                /* leave the JVM's own default */
            }
            default -> {
                /* unrecognized name: leave the JVM's own default rather than guess */
            }
        }
        // String deduplication only has an effect on G1/ZGC; skip it otherwise.
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) {
            out.add("-XX:+UseStringDeduplication");
        }
        addHardening(out, s, concurrency);
        out.addAll(s.extraArgs());
        return out;
    }

    /**
     * The request-scoped worker tuning, read from the current {@link cc.jumpkick.config.Session}. When
     * the session carries none (e.g. a direct engine/test call that bypassed the CLI composition
     * root), fall back to the {@code JK_*} env layer — mirroring the old {@code processSettings()}
     * default.
     */
    private static PluginTuning tuning() {
        var session = SessionContext.current();
        PluginTuning t = session.jvm();
        PluginTuning base = (t == null || t == PluginTuning.NONE) ? cc.jumpkick.config.PluginTunings.fromEnv() : t;
        // The jk.toml [jvm] table overlays here, at fork time, engine-side (thin-client contract):
        // the session carries only the client's flag/env layers, so a client of any age gets
        // current-engine [jvm] interpretation.
        return cc.jumpkick.config.PluginTunings.overlayProject(base, session.workingDir());
    }

    /**
     * Worker-fork JVM flags for {@code concurrency} simultaneously-launched JVMs, built from the
     * request's {@linkplain #tuning() tuning} — so a {@code --max-ram-percent} flag or a {@code [jvm]}
     * table reaches the worker.
     *
     * <p>When a {@linkplain #processHeapPlan() heap plan} is in effect (the default — no explicit
     * heap tuning), absolute {@code -Xms}/{@code -Xmx}/ {@code -XX:SoftMaxHeapSize} from the plan
     * replace the relative {@code MaxRAMPercentage}; the plan already accounts for how many JVMs run
     * at once, so {@code concurrency} is ignored in that case.
     */
    public static List<String> workerFlags(int concurrency) {
        return workerFlags(concurrency, DEFAULT_GC);
    }

    /**
     * {@link #workerFlags} with the {@linkplain #BATCH_DEFAULT_GC batch collector} as the GC
     * default — for jk-owned batch forks (compilers, plugin tools), never test workers. An
     * explicit {@code [jvm] gc} still wins.
     */
    public static List<String> batchFlags(int concurrency) {
        return workerFlags(concurrency, BATCH_DEFAULT_GC);
    }

    private static List<String> workerFlags(int concurrency, String defaultGc) {
        PluginTuning s = tuning();
        HeapPlan.Plan plan = processHeapPlan();
        if (plan != null && autoHeapEnabled(s)) return absoluteFlags(plan, s, defaultGc);
        return flags(s, concurrency, defaultGc);
    }

    /** Process-wide heap budget from {@link #planAndApply}, or null when explicit tuning wins. */
    private static volatile HeapPlan.Plan heapPlan;

    /**
     * Probe memory, compute the heap budget for {@code requestedJvms} desired forks, and apply it:
     * stash it for {@link #workerFlags} and size {@link PluginSlots} so no more than the plan's
     * parallelism run at once. A no-op (returns {@code null}, opens the worker gate) when the request
     * supplied explicit heap tuning — those settings then drive sizing as before.
     */
    public static HeapPlan.Plan planAndApply(int requestedJvms) {
        if (!autoHeapEnabled(tuning())) {
            PluginSlots.configure(0); // unbounded: honour the user's relative/explicit sizing
            heapPlan = null;
            return null;
        }
        HeapPlan.Plan plan = HeapPlan.compute(MemoryProbe.probe().availableBytes(), requestedJvms);
        heapPlan = plan;
        PluginSlots.configure(plan.parallelism());
        return plan;
    }

    /** The applied heap budget, or {@code null} if none (explicit tuning / not yet planned). */
    public static HeapPlan.Plan processHeapPlan() {
        return heapPlan;
    }

    /**
     * Test-only: undo {@link #planAndApply} — clears the shared heap plan and reopens the {@link
     * PluginSlots} gate. Production code never calls this (a real process's plan is meant to live for
     * the process's whole lifetime); it exists because a test that spins up a real {@code
     * EngineServer} (which calls {@code planAndApply} as a side effect of starting) would otherwise
     * leak that process-wide static into unrelated tests sharing the same test JVM.
     */
    public static void resetSharedPlanForTests() {
        heapPlan = null;
        PluginSlots.configure(0);
    }

    /**
     * True when jk should auto-size worker heaps: the request pinned neither a {@code
     * --max-ram-percent} / {@code [jvm] max-ram-percent} nor an explicit heap flag ({@code
     * -Xmx}/{@code -Xms}/{@code -XX:MaxHeapSize}/ {@code -XX:MaxRAMPercentage}) via {@code --jvm-arg}
     * / {@code [jvm] args}.
     */
    public static boolean autoHeapEnabled() {
        return autoHeapEnabled(tuning());
    }

    private static boolean autoHeapEnabled(PluginTuning s) {
        if (s.maxRamPercent() != null) return false;
        for (String a : s.extraArgs()) {
            if (a.startsWith("-Xmx")
                    || a.startsWith("-Xms")
                    || a.startsWith("-XX:MaxHeapSize")
                    || a.startsWith("-XX:MinHeapSize")
                    || a.startsWith("-XX:MaxRAMPercentage")
                    || a.startsWith("-XX:SoftMaxHeapSize")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Absolute-heap worker flags from {@code plan}: small {@code -Xms}, a {@code SoftMaxHeapSize}
     * good-neighbour target, an {@code -Xmx} burst cap, and the collector (default: the JVM's own,
     * i.e. G1 on server-class machines; an explicit {@code gc = "zgc"} adds {@code ZUncommit} so
     * idle heap is returned to the OS). {@code SoftMaxHeapSize} is emitted except under an explicit
     * {@code gc = "none"} — G1 and ZGC honour it, everything else recognizes and ignores it.
     */
    static List<String> absoluteFlags(HeapPlan.Plan plan, PluginTuning s) {
        return absoluteFlags(plan, s, DEFAULT_GC);
    }

    static List<String> absoluteFlags(HeapPlan.Plan plan, PluginTuning s, String defaultGc) {
        String gc = (s.gc() != null ? s.gc() : defaultGc).toLowerCase(Locale.ROOT);
        boolean dedup = s.stringDedup() == null || s.stringDedup();
        boolean softMaxAware = !gc.equals("none");

        List<String> out = new ArrayList<>();
        out.add("-Xms" + HeapPlan.mib(plan.xmsBytes()) + "m");
        out.add("-Xmx" + HeapPlan.mib(plan.xmxBytes()) + "m");
        if (softMaxAware) out.add("-XX:SoftMaxHeapSize=" + HeapPlan.mib(plan.softMaxBytes()) + "m");
        switch (gc) {
            case "zgc" -> {
                out.add("-XX:+UseZGC");
                out.add("-XX:+ZUncommit");
                out.add("-XX:ZUncommitDelay=" + ZGC_UNCOMMIT_DELAY_SECONDS);
            }
            case "g1" -> out.add("-XX:+UseG1GC");
            case "parallel" -> out.add("-XX:+UseParallelGC");
            case "serial" -> out.add("-XX:+UseSerialGC");
            case "none", "default", "" -> {
                /* JVM default collector */
            }
            default -> {
                /* unrecognized name: leave the JVM's own default rather than guess */
            }
        }
        if (dedup && (gc.equals("zgc") || gc.equals("g1"))) out.add("-XX:+UseStringDeduplication");
        addHardening(out, s, plan.parallelism());
        out.addAll(s.extraArgs());
        return out;
    }

    /** How long ZGC waits on an idle heap before returning pages to the OS — tuned for aggressive give-back. */
    static final int ZGC_UNCOMMIT_DELAY_SECONDS = 10;

    /**
     * Default metaspace, CPU share, stack, and {@code ExitOnOutOfMemoryError} for workers, unless
     * already set in {@code extraArgs}.
     */
    private static void addHardening(List<String> out, PluginTuning s, int concurrency) {
        List<String> extra = s.extraArgs();
        if (!hasArgPrefix(extra, "-XX:MaxMetaspaceSize", "-XX:MetaspaceSize")) {
            out.add("-XX:MaxMetaspaceSize=" + DEFAULT_MAX_METASPACE_MB + "m");
        }
        if (!hasArgPrefix(extra, "-XX:ActiveProcessorCount")) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(1, concurrency));
            out.add("-XX:ActiveProcessorCount=" + cores);
        }
        if (!hasArgPrefix(extra, "-Xss")) {
            out.add("-Xss" + DEFAULT_STACK_KB + "k");
        }
        if (!hasArgPrefix(
                extra, "-XX:+ExitOnOutOfMemoryError", "-XX:-ExitOnOutOfMemoryError", "-XX:+CrashOnOutOfMemoryError")) {
            out.add("-XX:+ExitOnOutOfMemoryError");
        }
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
     * {@code workerFlags}, but each flag {@code -J}-prefixed for launcher tools that wrap their own
     * JVM (javac, native-image) rather than being exec'd as {@code java} directly.
     */
    public static List<String> launcherFlags(int concurrency) {
        List<String> out = new ArrayList<>();
        for (String f : batchFlags(concurrency)) out.add("-J" + f);
        return out;
    }

    /**
     * Assemble a worker JVM command line: {@code javaExe}, then the tuning flags ({@link
     * #workerFlags}), then {@code rest} (e.g. {@code -cp <jar> Main <spec>}). For forks not driven by
     * {@link cc.jumpkick.engine.plugin.PluginLoader} — the compiler/git plugins and the CLI's standalone
     * plugin commands.
     */
    public static List<String> javaCommand(String javaExe, int concurrency, List<String> rest) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.addAll(batchFlags(concurrency));
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
