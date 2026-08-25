// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [engine]} policy ({@code max-heap-mb}, {@code jobs}); not
 * project-overridable. Read once at engine start — not hot-reloaded.
 *
 * <p>{@code jobs} is Mill-shaped concurrent-work budget: {@code null} = default (cores),
 * {@code 0} = all cores, {@code 1} = serial, {@code N} = cap. Resolved via {@link Jobs}.
 *
 * <p>Default engine heap is {@link #DEFAULT_MAX_HEAP_MB} (256 MiB) on developer machines; when
 * {@code CI=1} or {@code CI=true}, the unset default is {@link #CI_DEFAULT_MAX_HEAP_MB} (512 MiB).
 * Explicit file/env values always win — the precedence is {@link MachineConfig}'s.
 */
public record JkEngineConfig(int maxHeapMb, @Nullable Integer jobs) {

    /** Default engine-process heap ceiling ({@code -Xmx}) when not on CI. */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /** Default engine heap when {@code CI=1} or {@code CI=true} and heap is unset. */
    public static final int CI_DEFAULT_MAX_HEAP_MB = 512;

    /** Initial engine heap ({@code -Xms}), clamped to {@link #maxHeapMb}. */
    public static final int MIN_HEAP_MB = 32;

    /** Logical non-CI defaults (256 MiB heap). Prefer {@link #resolve()} for effective policy. */
    public static final JkEngineConfig DEFAULTS = new JkEngineConfig(DEFAULT_MAX_HEAP_MB, null);

    /** {@code max-heap-mb} / {@code JK_ENGINE_MAX_HEAP_MB}: negatives are not a heap, 0 = uncapped. */
    private static final MachineConfig<Integer> MAX_HEAP_MB =
            MachineConfig.of(DEFAULT_MAX_HEAP_MB, JkEngineConfig::validHeap);

    /** {@code jobs} / {@code JK_JOBS}: the built-in is "unset", which {@link Jobs} reads as cores. */
    private static final MachineConfig<Integer> JOBS = MachineConfig.of(null);

    /** Back-compat: heap-only config (jobs default). */
    public JkEngineConfig(int maxHeapMb) {
        this(maxHeapMb, null);
    }

    /** Effective machine config: user-global file + {@code JK_ENGINE_MAX_HEAP_MB} / {@code JK_JOBS}. */
    public static JkEngineConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkEngineConfig resolve(Path userConfig, Function<String, String> env) {
        TomlScan scan = scan(userConfig);
        return new JkEngineConfig(
                // CI moves the floor, not the precedence: an explicit file or env value still wins.
                MAX_HEAP_MB.layerOver(
                        defaultMaxHeapMb(env),
                        EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB").orElse(null),
                        scanInt(scan, "engine.max-heap-mb")),
                // JK_ENGINE_JOBS is the legacy spelling: consulted below JK_JOBS, above the file.
                JOBS.layer(
                        EnvValues.intValue(env, "JK_JOBS").orElse(null),
                        EnvValues.intValue(env, "JK_ENGINE_JOBS").orElse(null),
                        scanInt(scan, "engine.jobs")));
    }

    /** Machine defaults only (CI-aware heap, no file/env heap override). */
    public static JkEngineConfig resolvedDefaults(Function<String, String> env) {
        return new JkEngineConfig(defaultMaxHeapMb(env), null);
    }

    /** Unset heap default: 512 MiB on CI, else 256 MiB. */
    public static int defaultMaxHeapMb(Function<String, String> env) {
        return isCi(env) ? CI_DEFAULT_MAX_HEAP_MB : DEFAULT_MAX_HEAP_MB;
    }

    static boolean isCi(Function<String, String> env) {
        return EnvValues.bool(env, "CI").orElse(false);
    }

    /** {@code [engine]} table; missing/malformed/out-of-range → non-CI defaults for that field. */
    public static JkEngineConfig fromToml(Path file) {
        TomlScan scan = scan(file);
        return new JkEngineConfig(
                MAX_HEAP_MB.layer(scanInt(scan, "engine.max-heap-mb")), JOBS.layer(scanInt(scan, "engine.jobs")));
    }

    private static TomlScan scan(Path file) {
        return TomlScan.scan(file, "engine.max-heap-mb", "engine.jobs");
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
}
