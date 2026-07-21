// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Machine-scoped {@code [engine]} policy ({@code max-heap-mb}, {@code jobs}); not
 * project-overridable. Read once at engine start — not hot-reloaded.
 *
 * <p>{@code jobs} is Mill-shaped concurrent-work budget (JK-1082): {@code null} = default (cores),
 * {@code 0} = all cores, {@code 1} = serial, {@code N} = cap. Resolved via {@link Jobs}.
 */
public record JkEngineConfig(int maxHeapMb, Integer jobs) {

    /** Default engine-process heap ceiling ({@code -Xmx}); heavy work stays in worker JVMs. */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /** Initial engine heap ({@code -Xms}), clamped to {@link #maxHeapMb}. */
    public static final int MIN_HEAP_MB = 32;

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
        JkEngineConfig base = fromToml(userConfig);
        int heap = EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB")
                .filter(JkEngineConfig::validHeap)
                .orElse(base.maxHeapMb);
        // Env jobs win over file (same layering as heap). CLI still wins over both via Jobs.resolve.
        Integer jobs = EnvValues.intValue(env, "JK_JOBS")
                .or(() -> EnvValues.intValue(env, "JK_ENGINE_JOBS"))
                .orElse(base.jobs());
        return new JkEngineConfig(heap, jobs);
    }

    /** {@code [engine]} table; missing/malformed/out-of-range → defaults for that field. */
    public static JkEngineConfig fromToml(Path file) {
        TomlScan scan = TomlScan.scan(file, "engine.max-heap-mb", "engine.jobs");
        int maxHeapMb = scanInt(scan, "engine.max-heap-mb")
                .filter(JkEngineConfig::validHeap)
                .orElse(DEFAULTS.maxHeapMb);
        Integer jobs = scanInt(scan, "engine.jobs").orElse(null);
        return new JkEngineConfig(maxHeapMb, jobs);
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
