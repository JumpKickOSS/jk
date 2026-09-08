// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process buffer: module path → FQCN → wall-ms for the run currently finishing. Written by
 * {@link StepTimingsRecorder} from {@link cc.jumpkick.run.TestSummary#classWallMs()}; consumed when
 * the journal writes {@code metrics.toml} so harvest can fold class means without a second scan.
 */
public final class TestClassWalls {

    private static final ConcurrentHashMap<String, Map<String, Long>> BY_MODULE = new ConcurrentHashMap<>();

    private TestClassWalls() {}

    public static void put(String moduleDir, Map<String, Long> walls) {
        if (moduleDir == null || moduleDir.isBlank() || walls == null || walls.isEmpty()) return;
        Map<String, Long> copy = new LinkedHashMap<>();
        walls.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && v > 0) copy.put(k, v);
        });
        if (!copy.isEmpty()) BY_MODULE.put(moduleDir, Map.copyOf(copy));
    }

    /** Snapshot without clearing (ETA within the same process after a train). */
    public static Map<String, Long> get(String moduleDir) {
        if (moduleDir == null) return Map.of();
        return BY_MODULE.getOrDefault(moduleDir, Map.of());
    }

    /** Drain walls for journal write (one-shot). */
    public static Map<String, Long> take(String moduleDir) {
        if (moduleDir == null) return Map.of();
        Map<String, Long> m = BY_MODULE.remove(moduleDir);
        return m == null ? Map.of() : m;
    }

    /** All modules that recorded class walls this process (journal may multi-module). */
    public static Map<String, Map<String, Long>> takeAll() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String k : BY_MODULE.keySet()) {
            Map<String, Long> v = BY_MODULE.remove(k);
            if (v != null && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }
}
