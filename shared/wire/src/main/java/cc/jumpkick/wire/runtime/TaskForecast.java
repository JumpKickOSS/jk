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

    /**
     * One module's forecast: identity plus ordered steps. {@code reason} is set when the preflight
     * scheduled the module on its own account — an input it could not read — and reads as a
     * sentence ({@code rebuilt because …}); such a module is dirty whatever its steps say.
     */
    public record Module(
            Path dir,
            String coord,
            List<Task> steps,
            int sourceCount,
            int testCount,
            boolean producesJar,
            boolean producesImage,
            @Nullable String reason) {

        /** A module the preflight had no reason of its own to schedule. */
        public Module(
                Path dir,
                String coord,
                List<Task> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage) {
            this(dir, coord, steps, sourceCount, testCount, producesJar, producesImage, null);
        }

        /** This module with the preflight's reason for scheduling it. */
        public Module withReason(String reason) {
            return new Module(dir, coord, steps, sourceCount, testCount, producesJar, producesImage, reason);
        }

        /** Reconstruct a forecast module client-side from wire-level data. */
        public static Module fromWire(
                Path dir,
                @Nullable String coord,
                @Nullable List<Task> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage,
                @Nullable String reason) {
            return new Module(
                    dir,
                    coord == null ? "" : coord,
                    steps == null ? List.of() : steps,
                    sourceCount,
                    testCount,
                    producesJar,
                    producesImage,
                    reason);
        }

        /** As above for a module the wire carried no reason for. */
        public static Module fromWire(
                Path dir,
                @Nullable String coord,
                @Nullable List<Task> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage) {
            return fromWire(dir, coord, steps, sourceCount, testCount, producesJar, producesImage, null);
        }

        /**
         * True when this module has material work that is not cache-restorable (compile, tests,
         * package, native, image, or source-generating build-logic). Always-run bookkeeping
         * ({@code parse-build}, {@code resolve-deps}, {@code write-stamp}, …) is not dirty.
         */
        public boolean dirty() {
            return reason != null || steps.stream().anyMatch(p -> !p.cached() && isMaterialWork(p.name()));
        }

        /**
         * True when the build schedules this module only to bring its outputs back from the
         * caches: every step is a hit and the one non-cached material step is the restore gate.
         * Nothing compiles, packages or runs — the work is a copy out of the CAS, priced in
         * seconds where a rebuild of the same module is priced in its full wall.
         */
        public boolean restoreOnly() {
            if (reason != null) return false;
            boolean sawRestore = false;
            for (Task s : steps) {
                if (s.cached() || isBookkeepingStep(s.name())) continue;
                if (TaskNames.RESTORE_OUTPUTS.equals(s.name())) {
                    sawRestore = true;
                    continue;
                }
                return false;
            }
            return sawRestore;
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
                        TaskNames.BUILD_LOGIC_GUARD -> true;
                default -> false;
            };
        }
    }
}
