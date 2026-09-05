// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.model.command.Exit;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The structured outcome of one build run, frozen at request-finish and persisted as {@code
 * record.json} inside a {@link BuildJournal} entry. This is the source of truth for the dashboard's
 * backfilled activity feed and the {@code jk history} CLI; the heavier per-run artifacts
 * ({@code jk-results.md}, a {@code jk-lock.toml} snapshot, flattened diagnostics) sit beside it in
 * the same entry directory.
 *
 * <p>Deliberately a plain value object with no engine dependencies so it round-trips cleanly through
 * {@link Json}. {@code schema} is a stamp carried on the written record; no reader branches on it.
 *
 * <p>{@code running=true} marks an in-flight admission written at request-start so the web UI can
 * rehydrate active builds after refresh. Finished records keep {@code running=false}.
 */
public record BuildRecord(
        @Nullable String id,
        long buildNumber,
        int schema,
        String kind,
        String dir,
        @Nullable String coord,
        @Nullable String projectId,
        long startedAt,
        long finishedAt,
        long millis,
        boolean success,
        boolean cancelled,
        int exitCode,
        String jkVersion,
        @Nullable Tests tests,
        List<Module> modules,
        List<Task> steps,
        List<Diag> diagnostics,
        String trigger,
        @Nullable String commit,
        @Nullable CacheBenefit benefit,
        boolean running,
        @Nullable Io io,
        long requestId) {

    /**
     * The on-disk schema version stamped into every {@code record.json}. Purely descriptive: a
     * record carrying any other value still parses, because additive fields absent from it read
     * back as their defaults.
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
                projectId,
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
                io,
                requestId);
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
                projectId,
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
                io,
                requestId);
    }

    /**
     * This row closed out as abandoned: an engine died with it still {@code running}, so nothing is
     * ever going to finish it.
     *
     * <p>Not a cancellation. A Ctrl-C reaches a live engine, which completes the row itself; the
     * only rows that reach here are the ones whose engine was killed, crashed, or lost its machine.
     * A user cannot act on that, so the code is {@link Exit#SOFTWARE}. Until it was 130 —
     * {@code 128 + SIGINT} — with {@code cancelled} set, reporting a machine's death as something
     * the user did. Vacating it left the journal with no 130 at all for a whole release; since
     * a genuinely cancelled row carries {@link Exit#INTERRUPTED} and this one still does
     * not, which is the distinction the two codes exist to draw.
     *
     * <p>The in-flight step, module and diagnostic lists are dropped: a half-written plan is not a
     * result, and the row is kept only so the history does not show a run that never ends.
     */
    public BuildRecord abandoned(long finishedAt, String jkVersion) {
        return new BuildRecord(
                id,
                buildNumber,
                schema,
                kind,
                dir,
                coord,
                projectId,
                startedAt,
                finishedAt,
                Math.max(0, finishedAt - startedAt),
                /* success */ false,
                /* cancelled */ false,
                Exit.SOFTWARE,
                jkVersion != null ? jkVersion : this.jkVersion,
                /* tests */ null,
                List.of(),
                List.of(),
                List.of(),
                trigger,
                commit,
                /* benefit */ null,
                /* running */ false,
                io,
                requestId);
    }

    /**
     * True when this run was started by install/optimize/calibrate fixtures and must not appear in
     * {@code jk history} or the web activity feed ({@code trigger} is {@code optimize},
     * {@code calibrate}, or {@code synthetic}).
     */
    public boolean synthetic() {
        return isSyntheticTrigger(trigger);
    }

    /**
     * The one definition of a fixture trigger, shared with the raw journal scan so a record that
     * {@link #synthetic()} would hide is hidden on the verbatim path too — the journal writes
     * {@code trigger}, never a {@code synthetic} key.
     */
    public static boolean isSyntheticTrigger(@Nullable String trigger) {
        if (trigger == null || trigger.isBlank()) return false;
        String t = trigger.trim().toLowerCase(Locale.ROOT);
        return "optimize".equals(t) || "calibrate".equals(t) || "synthetic".equals(t);
    }

    /** In-flight stub at admission. */
    public static BuildRecord running(
            long buildNumber,
            String kind,
            String dir,
            String coord,
            String projectId,
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
                projectId,
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
                null,
                0L);
    }

    /** In-flight stub that records the engine {@code requestId} (MCP wait / job lookup). */
    public static BuildRecord running(
            long buildNumber,
            String kind,
            String dir,
            String coord,
            String projectId,
            long startedAt,
            String jkVersion,
            String trigger,
            long requestId) {
        return new BuildRecord(
                null,
                buildNumber,
                SCHEMA,
                kind,
                dir,
                coord,
                projectId,
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
                null,
                requestId);
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
     * One module's outcome in a workspace build (the {@code modules} list is empty for a single-plan
     * build/test, whose steps sit in the record's top-level {@code steps}). {@code steps} is this
     * module's own step chain, so the dashboard shows a chain per module.
     */
    public record Module(
            @Nullable String coord, String dir, boolean success, int exitCode, long millis, List<Task> steps) {
        public Module {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * One task's aggregate outcome: {@code status} is {@code SUCCESS} / {@code FAIL} /
     * {@code CANCELLED} / {@code SKIPPED}; {@code stage} is the task's {@code BuildStage} wire name
     * ({@code ""} when unknown) so the dashboard folds reloaded cards into the same chain the live
     * cards render, and so the metrics rollup buckets by what the plan declared rather than
     * re-guessing from the task name.
     */
    /**
     * @param millis the step's wall clock, queue wait included
     * @param waitMillis the part of that wall spent blocked on a shared resource (a compiler
     *     worker's queue); {@code millis - waitMillis} is the step's own work
     */
    public record Task(String name, String stage, String status, long millis, long waitMillis) {
        public Task {
            stage = stage == null ? "" : stage;
            waitMillis = Math.max(0, waitMillis);
        }
    }

    /**
     * One diagnostic: {@code severity} is {@code "error"} or {@code "warning"}; {@code dir} is the
     * module the failure belongs to ({@code ""} for a single-plan build), so the dashboard can nest
     * the failure output under the failed module inside its "failure details" roll-up.
     */
    public record Diag(
            String severity,
            String dir,
            @Nullable String step,
            String code,
            String message,
            @Nullable String test,
            @Nullable String exceptionClass,
            @Nullable String module,
            @Nullable String engine,
            @Nullable String className,
            @Nullable String method,
            @Nullable String stack,
            String file,
            int line,
            int col,
            int snippetStart,
            List<String> snippet,
            int worker) {

        public Diag {
            if (snippet == null) snippet = List.of();
            else snippet = List.copyOf(snippet);
            if (file == null) file = "";
        }

        /** Without source snippet. */
        public Diag(
                String severity,
                String dir,
                String step,
                String code,
                String message,
                String test,
                String exceptionClass,
                String module,
                String engine,
                String className,
                String method,
                String stack) {
            this(
                    severity,
                    dir,
                    step,
                    code,
                    message,
                    test,
                    exceptionClass,
                    module,
                    engine,
                    className,
                    method,
                    stack,
                    "",
                    0,
                    0,
                    0,
                    List.of(),
                    0);
        }

        /** Legacy ctor without structured test fields. */
        public Diag(
                String severity,
                String dir,
                String step,
                String code,
                String message,
                String test,
                String exceptionClass) {
            this(severity, dir, step, code, message, test, exceptionClass, "", "", "", "", "");
        }
    }
}
