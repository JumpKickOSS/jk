// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.PluginTunings;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

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

    /**
     * First JDK feature that accepts {@code --sun-misc-unsafe-memory-access} (JEP 498). Older hosts
     * abort with "Unrecognized option" if the flag is present.
     */
    public static final int JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE = 23;

    /** Acknowledges memory-access {@code sun.misc.Unsafe} use on JDK ≥ 23 batch hosts. */
    public static final String JEP498_UNSAFE_MEMORY_ACCESS_ALLOW = "--sun-misc-unsafe-memory-access=allow";

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
        PluginTuning base = (t == null || t == PluginTuning.NONE) ? PluginTunings.fromEnv() : t;
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
        return workerFlags(concurrency, DEFAULT_GC);
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
        List<String> out = new ArrayList<>(workerFlags(concurrency, BATCH_DEFAULT_GC));
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

    /** Feature major for a {@code java}/{@code javac} executable under {@code <home>/bin/}. */
    public static int hostFeatureFromExe(Path javaOrJavac) {
        if (javaOrJavac != null) {
            Path parent = javaOrJavac.getParent();
            if (parent != null && parent.getParent() != null) {
                return hostFeature(parent.getParent());
            }
        }
        return Runtime.version().feature();
    }

    private static void appendJep498AllowIfSupported(List<String> out, int hostFeature) {
        if (hostFeature < JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE) return;
        if (!hasArgPrefix(out, "--sun-misc-unsafe-memory-access")) {
            out.add(JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        }
    }

    private static Integer featureFromRelease(Path javaHome) {
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
     * Heap and CPU for a fork that is the <em>only</em> worker its command runs — append after
     * {@link #batchFlags}, whose values these deliberately override (last flag wins on HotSpot).
     * The process-wide plan is sized for {@code jobs} concurrent JVMs, so a command that forks one
     * worker otherwise gets a twentieth of a twenty-core host. Empty when the user pinned memory.
     */
    public static List<String> soleWorkerFlags() {
        PluginTuning s = tuning();
        if (!autoHeapEnabled(s)) return List.of();
        HeapPlan.Plan plan = HeapPlan.compute(MemoryProbe.probe().availableBytes(), 1);
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
     * --ram-percent} / {@code [jvm] max-ram-percent} nor an explicit heap flag ({@code
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
     * plugin commands. Host feature is taken from {@code javaExe}'s JDK home when possible.
     */
    public static List<String> javaCommand(String javaExe, int concurrency, List<String> rest) {
        int feature = javaExe != null
                ? hostFeatureFromExe(Path.of(javaExe))
                : Runtime.version().feature();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
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
