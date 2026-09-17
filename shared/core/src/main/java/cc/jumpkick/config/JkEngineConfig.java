// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [engine]} policy. Keys live in {@link EngineControls}. Not
 * project-overridable. Read once at engine start — not hot-reloaded.
 *
 * <p>{@code jobs} is Mill-shaped concurrent-work budget: {@code null} = default (cores),
 * {@code 0} = all cores, {@code 1} = serial, {@code N} = cap. Resolved via {@link Jobs}.
 *
 * <p>Default engine heap is {@link #DEFAULT_MAX_HEAP_MB} (256 MiB) on developer machines; when
 * {@code CI=1} or {@code CI=true}, the unset default is {@link #CI_DEFAULT_MAX_HEAP_MB} (512 MiB).
 * Explicit file/env values always win — the precedence is {@link MachineConfig}'s.
 *
 * <p>{@code continue} is the same shape: unset means fail-fast interactively and keep-going on CI.
 * The two situations want opposite answers. At a prompt the first failure is the one you are about
 * to fix and finishing the graph to prove it wastes minutes; on CI a run costs a queue slot, and
 * reporting one failure per run turns a five-fault branch into five round trips. It never changes
 * the verdict — a run that kept going and had failures still fails.
 */
public record JkEngineConfig(
        int maxHeapMb,
        @Nullable Integer jobs,
        boolean keepGoing,
        int vfsMaxMb,
        boolean autoWarmup,
        JobLimits jobLimits,
        int logMaxMb,
        String logLevel) {

    /** Default engine-process heap ceiling ({@code -Xmx}) when not on CI. */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /** Default engine heap when {@code CI=1} or {@code CI=true} and heap is unset. */
    public static final int CI_DEFAULT_MAX_HEAP_MB = 512;

    /** Initial engine heap ({@code -Xms}), clamped to {@link #maxHeapMb}. */
    public static final int MIN_HEAP_MB = 32;

    /** Per-job input-tree retain, in MiB. {@code 0} disables the VFS (always live-walk). CI does not change this. */
    public static final int DEFAULT_VFS_MAX_MB = 32;

    /** Engine log size cap, in MiB; the log rolls to {@code .1} at the cap. {@code 0} = no cap. */
    public static final int DEFAULT_LOG_MAX_MB = 16;

    /** Engine log threshold; {@code debug} adds the perf probes and swallowed-exception detail. */
    public static final String DEFAULT_LOG_LEVEL = "info";

    /**
     * Logical non-CI defaults (256 MiB heap, fail-fast, 32 MiB VFS, warmup on, default job limits,
     * 16 MiB log cap, info log level). Prefer {@link #resolve()} for effective policy.
     */
    public static final JkEngineConfig DEFAULTS = new JkEngineConfig(
            DEFAULT_MAX_HEAP_MB,
            null,
            false,
            DEFAULT_VFS_MAX_MB,
            true,
            JobLimits.DEFAULTS,
            DEFAULT_LOG_MAX_MB,
            DEFAULT_LOG_LEVEL);

    /** {@code max-heap-mb} / {@code JK_ENGINE_MAX_HEAP_MB}: negatives are not a heap, 0 = uncapped. */
    private static final MachineConfig<Integer> MAX_HEAP_MB =
            MachineConfig.of(DEFAULT_MAX_HEAP_MB, JkEngineConfig::validHeap);

    /** {@code jobs} / {@code JK_JOBS}: the built-in is "unset", which {@link Jobs} reads as cores. */
    private static final MachineConfig<@Nullable Integer> JOBS = MachineConfig.of(null);

    /** {@code continue} / {@code JK_CONTINUE}: the built-in is fail-fast; CI moves the floor. */
    private static final MachineConfig<Boolean> KEEP_GOING = MachineConfig.of(false);

    /** {@code vfs-max-mb} / {@code JK_ENGINE_VFS_MAX_MB}: {@code 0} = off, negatives fall through. */
    private static final MachineConfig<Integer> VFS_MAX_MB = MachineConfig.of(DEFAULT_VFS_MAX_MB, v -> v >= 0);

    /** {@code auto-warmup} / {@code JK_AUTO_WARMUP}: the built-in is on. */
    private static final MachineConfig<Boolean> AUTO_WARMUP = MachineConfig.of(true);

    /** {@code log-max-mb} / {@code JK_ENGINE_LOG_MAX_MB}: {@code 0} = no cap, negatives fall through. */
    private static final MachineConfig<Integer> LOG_MAX_MB = MachineConfig.of(DEFAULT_LOG_MAX_MB, v -> v >= 0);

    /** {@code log-level} / {@code JK_LOG_LEVEL}: a name {@link Log#level} knows, else the default. */
    private static final MachineConfig<String> LOG_LEVEL =
            MachineConfig.of(DEFAULT_LOG_LEVEL, v -> Log.level(v).isPresent());

    /** Heap-only config; everything else at its default. */
    public JkEngineConfig(int maxHeapMb) {
        this(
                maxHeapMb,
                null,
                false,
                DEFAULT_VFS_MAX_MB,
                true,
                JobLimits.DEFAULTS,
                DEFAULT_LOG_MAX_MB,
                DEFAULT_LOG_LEVEL);
    }

    /** Heap + jobs + continue; everything else at its default. */
    public JkEngineConfig(int maxHeapMb, @Nullable Integer jobs, boolean keepGoing) {
        this(
                maxHeapMb,
                jobs,
                keepGoing,
                DEFAULT_VFS_MAX_MB,
                true,
                JobLimits.DEFAULTS,
                DEFAULT_LOG_MAX_MB,
                DEFAULT_LOG_LEVEL);
    }

    /** Heap + jobs + continue + VFS; everything else at its default. */
    public JkEngineConfig(int maxHeapMb, @Nullable Integer jobs, boolean keepGoing, int vfsMaxMb) {
        this(maxHeapMb, jobs, keepGoing, vfsMaxMb, true, JobLimits.DEFAULTS, DEFAULT_LOG_MAX_MB, DEFAULT_LOG_LEVEL);
    }

    /** This config with different job limits — how a test gives a live engine a millisecond deadline. */
    public JkEngineConfig withJobLimits(JobLimits limits) {
        return new JkEngineConfig(maxHeapMb, jobs, keepGoing, vfsMaxMb, autoWarmup, limits, logMaxMb, logLevel);
    }

    /** Effective machine config: user-global file + {@code JK_ENGINE_MAX_HEAP_MB} / {@code JK_JOBS}. */
    public static JkEngineConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkEngineConfig resolve(Path userConfig, Function<String, @Nullable String> env) {
        TomlScan scan = scan(userConfig);
        return new JkEngineConfig(
                // CI moves the floor, not the precedence: an explicit file or env value still wins.
                MAX_HEAP_MB.layerOver(
                        defaultMaxHeapMb(env),
                        EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB").orElse(null),
                        scanInt(scan, "engine.max-heap-mb")),
                JOBS.layer(EnvValues.intValue(env, "JK_JOBS").orElse(null), scanInt(scan, "engine.jobs")),
                // As the heap: CI moves the floor, not the precedence.
                KEEP_GOING.layerOver(
                        defaultKeepGoing(env),
                        EnvValues.bool(env, "JK_CONTINUE").orElse(null),
                        scanBool(scan, "engine.continue")),
                // CI does not bump VFS: the heap bump is concurrency headroom.
                VFS_MAX_MB.layer(
                        EnvValues.intValue(env, "JK_ENGINE_VFS_MAX_MB").orElse(null),
                        scanInt(scan, "engine.vfs-max-mb")),
                AUTO_WARMUP.layer(
                        EnvValues.bool(env, "JK_AUTO_WARMUP").orElse(null), scanBool(scan, "engine.auto-warmup")),
                JobLimits.resolve(
                        env, scanLong(scan, "engine.detached-deadline-ms"), scanLong(scan, "engine.queue-wait-ms")),
                LOG_MAX_MB.layer(
                        EnvValues.intValue(env, "JK_ENGINE_LOG_MAX_MB").orElse(null),
                        scanInt(scan, "engine.log-max-mb")),
                LOG_LEVEL.layer(EnvValues.string(env, "JK_LOG_LEVEL").orElse(null), scan.get("engine.log-level")));
    }

    /** Machine defaults only (CI-aware heap and continue, no file/env override). */
    public static JkEngineConfig resolvedDefaults(Function<String, @Nullable String> env) {
        return new JkEngineConfig(
                defaultMaxHeapMb(env),
                null,
                defaultKeepGoing(env),
                DEFAULT_VFS_MAX_MB,
                true,
                JobLimits.DEFAULTS,
                DEFAULT_LOG_MAX_MB,
                DEFAULT_LOG_LEVEL);
    }

    /** Unset heap default: 512 MiB on CI, else 256 MiB. */
    public static int defaultMaxHeapMb(Function<String, @Nullable String> env) {
        return EnvValues.isCi(env) ? CI_DEFAULT_MAX_HEAP_MB : DEFAULT_MAX_HEAP_MB;
    }

    /** Unset {@code continue} default: keep going on CI, fail fast at a prompt. */
    public static boolean defaultKeepGoing(Function<String, @Nullable String> env) {
        return EnvValues.isCi(env);
    }

    /**
     * {@code [engine]} table; missing/malformed/out-of-range → non-CI defaults for that field. Of
     * the job limits only the detached deadline and the queue wait are file keys; the rest are
     * env-only knobs and stay at their defaults here.
     */
    public static JkEngineConfig fromToml(Path file) {
        TomlScan scan = scan(file);
        return new JkEngineConfig(
                MAX_HEAP_MB.layer(scanInt(scan, "engine.max-heap-mb")),
                JOBS.layer(scanInt(scan, "engine.jobs")),
                KEEP_GOING.layer(scanBool(scan, "engine.continue")),
                VFS_MAX_MB.layer(scanInt(scan, "engine.vfs-max-mb")),
                AUTO_WARMUP.layer(scanBool(scan, "engine.auto-warmup")),
                JobLimits.fromFile(
                        scanLong(scan, "engine.detached-deadline-ms"), scanLong(scan, "engine.queue-wait-ms")),
                LOG_MAX_MB.layer(scanInt(scan, "engine.log-max-mb")),
                LOG_LEVEL.layer(scan.get("engine.log-level")));
    }

    private static TomlScan scan(Path file) {
        return TomlScan.scan(file, EngineControls.tomlScanKeys());
    }

    private static @Nullable Boolean scanBool(TomlScan scan, String key) {
        String v = scan.get(key);
        return v == null ? null : EnvValues.parseBool(v).orElse(null);
    }

    private static @Nullable Integer scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null; // malformed value — advisory layer, fall back
        }
    }

    private static @Nullable Long scanLong(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null; // malformed value — advisory layer, fall back
        }
    }

    private static boolean validHeap(int maxHeapMb) {
        return maxHeapMb >= 0; // 0 = uncapped (inherit the runtime's own default)
    }

    /** {@code true}: cap the engine process's heap at {@link #maxHeapMb} MiB when spawning it. */
    public boolean heapCapped() {
        return maxHeapMb > 0;
    }

    /** The {@code -Xms} the spawner should request: {@link #MIN_HEAP_MB}, never above the cap. */
    public int minHeapMb() {
        return heapCapped() ? Math.min(MIN_HEAP_MB, maxHeapMb) : MIN_HEAP_MB;
    }

    /** {@link #logLevel} as the logger's level; the record only ever holds a name {@link Log#level} knows. */
    public System.Logger.Level logThreshold() {
        return Log.level(logLevel).orElseThrow();
    }

    /** {@link #logMaxMb} in bytes; {@code 0} when the log is uncapped. */
    public long logMaxBytes() {
        return (long) logMaxMb << 20;
    }
}
