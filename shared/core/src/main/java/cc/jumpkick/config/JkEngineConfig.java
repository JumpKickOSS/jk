// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Machine-scoped {@code [engine]} policy ({@code max-heap-mb}); not project-overridable. Read once
 * at engine start — not hot-reloaded.
 */
public record JkEngineConfig(int maxHeapMb) {

    /** Default engine-process heap ceiling ({@code -Xmx}); heavy work stays in worker JVMs. */
    public static final int DEFAULT_MAX_HEAP_MB = 256;

    /** Initial engine heap ({@code -Xms}), clamped to {@link #maxHeapMb}. */
    public static final int MIN_HEAP_MB = 32;

    public static final JkEngineConfig DEFAULTS = new JkEngineConfig(DEFAULT_MAX_HEAP_MB);

    /** Effective machine config: user-global file + {@code JK_ENGINE_MAX_HEAP_MB}. */
    public static JkEngineConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkEngineConfig resolve(Path userConfig, Function<String, String> env) {
        JkEngineConfig base = fromToml(userConfig);
        return new JkEngineConfig(EnvValues.intValue(env, "JK_ENGINE_MAX_HEAP_MB")
                .filter(JkEngineConfig::validHeap)
                .orElse(base.maxHeapMb));
    }

    /** {@code [engine]} table; missing/malformed/out-of-range → {@link #DEFAULTS}. */
    public static JkEngineConfig fromToml(Path file) {
        TomlScan scan = TomlScan.scan(file, "engine.max-heap-mb");
        int maxHeapMb = scanInt(scan, "engine.max-heap-mb")
                .filter(JkEngineConfig::validHeap)
                .orElse(DEFAULTS.maxHeapMb);
        return new JkEngineConfig(maxHeapMb);
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
