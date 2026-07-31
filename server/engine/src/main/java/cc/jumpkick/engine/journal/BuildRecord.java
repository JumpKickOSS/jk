// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.List;

/**
 * The structured outcome of one build run, frozen at request-finish and persisted as {@code
 * record.json} inside a {@link BuildJournal} entry. This is the source of truth for the dashboard's
 * backfilled activity feed and the {@code jk history} CLI; the heavier per-run artifacts
 * (test-results markdown, a {@code jk-lock.toml} snapshot, flattened diagnostics) sit beside it in the
 * same entry directory.
 *
 * <p>Deliberately a plain value object with no engine dependencies so it round-trips cleanly through
 * {@link Json}. {@code schema} lets a future reader detect and reject/upgrade an older layout.
 *
 * <p>{@code running=true} marks an in-flight admission written at request-start so the web UI can
 * rehydrate active builds after refresh (JK-1251). Finished records keep {@code running=false}.
 */
public record BuildRecord(
        String id,
        long buildNumber,
        int schema,
        String kind,
        String dir,
        String coord,
        long startedAt,
        long finishedAt,
        long millis,
        boolean success,
        boolean cancelled,
        int exitCode,
        String jkVersion,
        Tests tests,
        List<Module> modules,
        List<Step> steps,
        List<Diag> diagnostics,
        String trigger,
        String commit,
        CacheBenefit benefit,
        boolean running,
        Io io) {

    /**
     * The current on-disk schema version. Bumped to 2 when {@code buildNumber} — the durable,
     * monotonic per-project run counter (assigned from {@link cc.jumpkick.runtime.BuildMetrics}) —
     * was added. {@code trigger}, {@code commit}, {@code benefit}, {@code running}, and {@code io}
     * (the run's byte counts) were added without a bump — pre-1.0 additive fields simply read back as
     * defaults on older records.
     */
    public static final int SCHEMA = 2;

    public BuildRecord {
        modules = modules == null ? List.of() : List.copyOf(modules);
        steps = steps == null ? List.of() : List.copyOf(steps);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** This record with its per-project build number set. */
    public BuildRecord withBuildNumber(long buildNumber) {
        return new BuildRecord(
                id,
                buildNumber,
                schema,
                kind,
                dir,
                coord,
                startedAt,
                finishedAt,
                millis,
                success,
                cancelled,
                exitCode,
                jkVersion,
                tests,
                modules,
                steps,
                diagnostics,
                trigger,
                commit,
                benefit,
                running,
                io);
    }

    /** This record with its journal id set (begin path). */
    public BuildRecord withId(String id) {
        return new BuildRecord(
                id,
                buildNumber,
                schema,
                kind,
                dir,
                coord,
                startedAt,
                finishedAt,
                millis,
                success,
                cancelled,
                exitCode,
                jkVersion,
                tests,
                modules,
                steps,
                diagnostics,
                trigger,
                commit,
                benefit,
                running,
                io);
    }

    /** In-flight stub at admission (JK-1250 / JK-1251). */
    public static BuildRecord running(
            long buildNumber,
            String kind,
            String dir,
            String coord,
            long startedAt,
            String jkVersion,
            String trigger) {
        return new BuildRecord(
                null,
                buildNumber,
                SCHEMA,
                kind,
                dir,
                coord,
                startedAt,
                0L,
                0L,
                false,
                false,
                0,
                jkVersion,
                null,
                List.of(),
                List.of(),
                List.of(),
                trigger,
                null,
                null,
                true,
                null);
    }

    /** Aggregate test counts for the run, or {@code null} when no tests ran. */
    public record Tests(long total, long succeeded, long failed, long skipped) {}

    /**
     * Bytes this run moved, or {@code null} when it moved none (and on older records). {@code remote}
     * is network traffic — {@code down} fetched from repositories / toolchain downloads, {@code up}
     * published to a remote; {@code local} is build-cache traffic — {@code up} stored into the cache,
     * {@code down} restored back out of it. Every number is stat'ed off files at rest, never counted
     * through a stream. See {@link cc.jumpkick.task.IoLedger}.
     */
    public record Io(long remoteUp, long remoteDown, long localUp, long localDown) {}

    /**
     * The cache's estimated wall-clock benefit for the run, or {@code null} for a build that didn't
     * succeed cleanly (and on older records). {@code estimatedUncachedMillis} is the two-level
     * critical-path estimate of a cold-cache run of the same work; {@code savedMillis} is
     * {@code max(0, estimatedUncached - millis)}. {@code coveredSkips}/{@code totalSkips} report how
     * many cache-hit steps had a real historical baseline, so a reader can gauge confidence. See
     * {@link cc.jumpkick.runtime.CacheBenefit}.
     */
    public record CacheBenefit(long estimatedUncachedMillis, long savedMillis, long coveredSkips, long totalSkips) {}

    /**
     * One module's outcome in a workspace build (the {@code modules} list is empty for a single-pipeline
     * build/test, whose steps sit in the record's top-level {@code steps}). {@code steps} is this
     * module's own step chain, so the dashboard shows a chain per module.
     */
    public record Module(String coord, String dir, boolean success, int exitCode, long millis, List<Step> steps) {
        public Module {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * One step's aggregate outcome: {@code status} is {@code SUCCESS} / {@code FAIL} /
     * {@code CANCELLED} / {@code SKIPPED}; {@code phase} is the coarse pipeline phase's wire-name
     * ({@code ""} when unphased) so the dashboard can fold reloaded/finished cards into the same
     * phase-chain the live cards render.
     */
    public record Step(String name, String phase, String status, long millis) {
        public Step {
            phase = phase == null ? "" : phase;
        }
    }

    /**
     * One diagnostic: {@code severity} is {@code "error"} or {@code "warning"}; {@code dir} is the
     * module the failure belongs to ({@code ""} for a single-pipeline build), so the dashboard can nest
     * the failure output under the failed module inside its "failure details" roll-up.
     */
    public record Diag(
            String severity,
            String dir,
            String step,
            String code,
            String message,
            String test,
            String exceptionClass) {}
}
