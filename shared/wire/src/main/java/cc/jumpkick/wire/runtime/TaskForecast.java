// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Front-end-safe build forecast data for {@code jk explain} and dirty-set reporting — pure data, no
 * engine internals.
 */
public final class TaskForecast {

    private TaskForecast() {}

    /**
     * Detail suffix on a {@code run-tests} step whose last run under the same inputs was red. The
     * ETA reads it as evidence of a suite to run; without it a module whose only dirty step is its
     * suite is priced as a drifted stamp.
     */
    public static final String LAST_RUN_FAILED = "last run failed";

    /** Per-step verdict. CACHED = restored from cache; the rest do real work. */
    public enum Status {
        CACHED,
        FULL,
        PARTIAL,
        RUN
    }

    /**
     * One step of a module's build. {@code text} is the right-hand detail after the status glyph;
     * {@code key} is the 8-char action key when known (cached).
     */
    public record Task(
            String name,
            Status status,
            String text,
            @Nullable String key) {
        public boolean cached() {
            return status == Status.CACHED;
        }
    }

    /** One module's forecast: identity plus ordered steps. */
    public record Module(
            Path dir,
            String coord,
            List<Task> steps,
            int sourceCount,
            int testCount,
            boolean producesJar,
            boolean producesImage) {

        /** Reconstruct a forecast module client-side from wire-level data. */
        public static Module fromWire(
                Path dir,
                @Nullable String coord,
                @Nullable List<Task> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage) {
            return new Module(
                    dir,
                    coord == null ? "" : coord,
                    steps == null ? List.of() : steps,
                    sourceCount,
                    testCount,
                    producesJar,
                    producesImage);
        }

        /**
         * True when this module has material work that is not cache-restorable (compile, tests,
         * package, native, image, or source-generating build-logic). Always-run bookkeeping
         * ({@code parse-build}, {@code resolve-deps}, {@code write-stamp}, …) is not dirty.
         */
        public boolean dirty() {
            return steps.stream().anyMatch(p -> !p.cached() && isMaterialWork(p.name()));
        }

        /**
         * Steps whose cache miss means real wall work for ETA / dirty-set (not stamp-check
         * bookkeeping). Anything not on the bookkeeping denylist is material (plugin source-gen,
         * native-image, run-tests, compile-*, package-*, …).
         */
        public static boolean isMaterialWork(@Nullable String stepName) {
            if (stepName == null || stepName.isBlank()) return false;
            return !isBookkeepingStep(stepName);
        }

        /**
         * Always-run / stamp-check steps that must not alone mark a module dirty.
         * {@code copy-resources} / {@code copy-test-resources} are not bookkeeping: they emit only
         * on real resource drift, which must schedule the module.
         */
        public static boolean isBookkeepingStep(@Nullable String stepName) {
            if (stepName == null) return true;
            return switch (stepName) {
                case TaskNames.PARSE_BUILD,
                        TaskNames.ENSURE_JDK,
                        TaskNames.RESOLVE_DEPS,
                        TaskNames.WRITE_STAMP,
                        TaskNames.WRITE_STAMP_KOTLIN,
                        TaskNames.WRITE_STAMP_GROOVY,
                        TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                        TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                        TaskNames.BUILD_LOGIC_BEFORE_PACKAGE,
                        TaskNames.BUILD_LOGIC_AFTER_BUILD,
                        TaskNames.BUILD_LOGIC_GATE -> true;
                default -> false;
            };
        }
    }
}
