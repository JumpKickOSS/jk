// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Machine-scoped {@code [engine]} policy ({@code max-heap-mb}, {@code jobs}); not
 * project-overridable. Read once at engine start — not hot-reloaded.
 *
 * <p>{@code jobs} is Mill-shaped concurrent-work budget: {@code null} = default (cores),
 * {@code 0} = all cores, {@code 1} = serial, {@code N} = cap. Resolved via {@link Jobs}.
 *
 * <p>Default engine heap is {@link #DEFAULT_MAX_HEAP_MB} (256 MiB) on developer machines; when
 * {@code CI=1} or {@code CI=true}, the unset default is {@link #CI_DEFAULT_MAX_HEAP_MB} (512 MiB).
 * Explicit file/env values always win.
 */
public record JkEngineConfig(int maxHeapMb, Integer jobs) {

    /** Default engine-process heap ceiling ({@code -Xmx}) when not on CI. */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /** Default engine heap when {@code CI=1} or {@code CI=true} and heap is unset. */
    public static final int CI_DEFAULT_MAX_HEAP_MB = 512;

    /** Initial engine heap ({@code -Xms}), clamped to {@link #maxHeapMb}. */
    public static final int MIN_HEAP_MB = 32;

    /** Logical non-CI defaults (256 MiB heap). Prefer {@link #resolve()} for effective policy. */
    public static final JkEngineConfig DEFAULTS = new JkEngineConfig(DEFAULT_MAX_HEAP_MB, null);

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
        Parsed p = parse(userConfig);
        int defaultHeap = defaultMaxHeapMb(env);
        int heap = EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB")
                .filter(JkEngineConfig::validHeap)
                .or(() -> p.maxHeapMb().stream().boxed().findFirst())
                .orElse(defaultHeap);
        // Env jobs win over file (same layering as heap). CLI still wins over both via Jobs.resolve.
        Integer jobs = EnvValues.intValue(env, "JK_JOBS")
                .or(() -> EnvValues.intValue(env, "JK_ENGINE_JOBS"))
                .orElse(p.jobs());
        return new JkEngineConfig(heap, jobs);
    }

    /** Machine defaults only (CI-aware heap, no file/env heap override). */
    public static JkEngineConfig resolvedDefaults(Function<String, String> env) {
        return new JkEngineConfig(defaultMaxHeapMb(env), null);
    }

    /** Unset heap default: 512 MiB on CI, else 256 MiB. */
    public static int defaultMaxHeapMb(Function<String, String> env) {
        return isCi(env) ? CI_DEFAULT_MAX_HEAP_MB : DEFAULT_MAX_HEAP_MB;
    }

    static boolean isCi(Function<String, String> env) {
        String ci = env.apply("CI");
        return "1".equals(ci) || (ci != null && "true".equalsIgnoreCase(ci));
    }

    /** {@code [engine]} table; missing/malformed/out-of-range → non-CI defaults for that field. */
    public static JkEngineConfig fromToml(Path file) {
        Parsed p = parse(file);
        return new JkEngineConfig(p.maxHeapMb().orElse(DEFAULT_MAX_HEAP_MB), p.jobs());
    }

    private record Parsed(OptionalInt maxHeapMb, Integer jobs) {}

    private static Parsed parse(Path file) {
        TomlScan scan = TomlScan.scan(file, "engine.max-heap-mb", "engine.jobs");
        OptionalInt maxHeapMb = scanInt(scan, "engine.max-heap-mb")
                .filter(JkEngineConfig::validHeap)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
        Integer jobs = scanInt(scan, "engine.jobs").orElse(null);
        return new Parsed(maxHeapMb, jobs);
    }

    private static Optional<Integer> scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty(); // malformed value — advisory layer, fall back
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
