// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process buffer: module path → how many runner JVMs executed its suite in the run currently
 * finishing. Written by {@link StepTimingsRecorder} from
 * {@link cc.jumpkick.run.TestSummary#workers()}; drained when the journal writes
 * {@code metrics.toml}, so the next build's forecast can re-schedule a stored suite wall instead of
 * re-using it verbatim.
 *
 * <p>A sibling of {@link TestClassWalls} and deliberately the same shape. The datum has to reach
 * {@code metrics.toml} beside the wall it belongs to, and this hand-off already exists for class
 * walls — threading a runner count through {@code BuildRecord.Step} instead would put a test-only
 * concern into the journal's step vocabulary for no gain.
 */
public final class TestSuiteRunners {

    private static final ConcurrentHashMap<String, Integer> BY_MODULE = new ConcurrentHashMap<>();

    private TestSuiteRunners() {}

    public static void put(String moduleDir, int runners) {
        if (moduleDir == null || moduleDir.isBlank() || runners <= 0) return;
        BY_MODULE.put(moduleDir, runners);
    }

    /** Drain for the journal write (one-shot). {@code 0} when this module recorded nothing. */
    public static int take(String moduleDir) {
        if (moduleDir == null) return 0;
        Integer n = BY_MODULE.remove(moduleDir);
        return n == null ? 0 : n;
    }

    /** All modules that recorded a runner count this process (the journal may be multi-module). */
    public static Map<String, Integer> takeAll() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : BY_MODULE.keySet()) {
            Integer v = BY_MODULE.remove(k);
            if (v != null && v > 0) out.put(k, v);
        }
        return out;
    }
}
