// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * In-process buffer: module path → the suite its {@code run-tests} just ran, in the run currently
 * finishing. Written by {@code StepTimingsRecorder} when the module's plan finishes; drained when
 * the journal writes {@code metrics.toml}, so the suite's wall lands under the selection that
 * produced it ({@code run-tests@integration}) and not in the module's one blended row.
 *
 * <p>A sibling of {@link TestSuiteRunners} and {@link TestClassWalls}, for the same reason: the
 * datum has to reach {@code metrics.toml} beside the wall it belongs to, and threading a test
 * selection through {@code BuildRecord.Task} would put a test-only concern into the journal's
 * vocabulary.
 */
public final class TestSuiteTiers {

    /**
     * @param step the suite's metrics name: {@code run-tests}, or {@code run-tests@<selection>}
     * @param partial whether the run ran only some of the suite's classes ({@code --class}): its wall
     *     measures those classes, not the suite, and is not recorded as the suite's
     */
    public record Suite(String step, boolean partial) {}

    private static final ConcurrentHashMap<String, Suite> BY_MODULE = new ConcurrentHashMap<>();

    private TestSuiteTiers() {}

    public static void put(String moduleDir, Suite suite) {
        if (moduleDir == null || moduleDir.isBlank() || suite == null) return;
        BY_MODULE.put(moduleDir, suite);
    }

    /** Drain for the journal write (one-shot). {@code null} when this module recorded nothing. */
    public static @Nullable Suite take(String moduleDir) {
        return moduleDir == null ? null : BY_MODULE.remove(moduleDir);
    }
}
