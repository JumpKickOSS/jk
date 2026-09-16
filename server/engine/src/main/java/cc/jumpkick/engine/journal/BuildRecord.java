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
        @Nullable String trigger,
        @Nullable String session,
        @Nullable String commit,
        @Nullable CacheBenefit benefit,
        boolean running,
        @Nullable Io io,
        long requestId,
        @Nullable Publish publish,
        List<Coverage> coverage,
        @Nullable JobDelta delta) {

    /**
     * The on-disk schema version stamped into every {@code record.json}: 1 until 1.0, like every
     * schema jk writes. Descriptive: a record carrying another value still parses, because additive
     * fields absent from it read back as their defaults.
     */
    public static final int SCHEMA = 1;

    public BuildRecord {
        modules = modules == null ? List.of() : List.copyOf(modules);
        steps = steps == null ? List.of() : List.copyOf(steps);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
    }

    /** A run with no {@link JobDelta} yet: every producer but the journal writer's finish. */
    public BuildRecord(
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
            @Nullable String trigger,
            @Nullable String session,
            @Nullable String commit,
            @Nullable CacheBenefit benefit,
            boolean running,
            @Nullable Io io,
            long requestId,
            @Nullable Publish publish,
            List<Coverage> coverage) {
        this(
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                publish,
                coverage,
                null);
    }

    /** A run that measured no coverage — every producer but the journal's drain of a coverage run. */
    public BuildRecord(
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
            @Nullable String trigger,
            @Nullable String session,
            @Nullable String commit,
            @Nullable CacheBenefit benefit,
            boolean running,
            @Nullable Io io,
            long requestId) {
        this(
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                null,
                List.of());
    }

    /** This record with the coverage its modules measured. */
    public BuildRecord withCoverage(List<Coverage> coverage) {
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                publish,
                coverage,
                delta);
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                publish,
                coverage,
                delta);
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                publish,
                coverage,
                delta);
    }

    /** This record with what changed since the run before it from the same origin. */
    public BuildRecord withDelta(@Nullable JobDelta delta) {
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
                session,
                commit,
                benefit,
                running,
                io,
                requestId,
                publish,
                coverage,
                delta);
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
                session,
                commit,
                /* benefit */ null,
                /* running */ false,
                io,
                requestId,
                publish,
                coverage,
                delta);
    }

    /** {@code trigger}, then the session that asked when there is one: {@code mcp · claude-code 3f9a}. */
    public @Nullable String origin() {
        if (trigger == null || trigger.isBlank()) return null;
        return session == null || session.isBlank() ? trigger : trigger + " · " + session;
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

    /** In-flight stub at admission, with no session and no engine {@code requestId}. */
    public static BuildRecord running(
            long buildNumber,
            String kind,
            String dir,
            @Nullable String coord,
            @Nullable String projectId,
            long startedAt,
            String jkVersion,
            @Nullable String trigger) {
        return running(buildNumber, kind, dir, coord, projectId, startedAt, jkVersion, trigger, null, 0L);
    }

    /**
     * In-flight stub at admission: the origin that asked and the engine {@code requestId} (MCP wait
     * / job lookup).
     */
    public static BuildRecord running(
            long buildNumber,
            String kind,
            String dir,
            @Nullable String coord,
            @Nullable String projectId,
            long startedAt,
            String jkVersion,
            @Nullable String trigger,
            @Nullable String session,
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
                session,
                null,
                null,
                true,
                null,
                requestId,
                null,
                List.of());
    }

    /** Aggregate test counts for the run, or {@code null} when no tests ran. */
    public record Tests(long total, long succeeded, long failed, long skipped) {}

    /**
     * What a {@code jk publish} run sent where, or {@code null} for every other kind. {@code
     * destination} is the repository URL or the Central Portal; {@code files} the files uploaded (or
     * assembled, on a dry run). A Central deployment carries the id the Portal assigned, the
     * {@code state} the poll ended in and every validation {@code error} it listed; {@code bundle}
     * is the entries of the bundle it uploaded or, on a dry run, wrote.
     */
    public record Publish(
            String destination,
            int files,
            boolean dryRun,
            @Nullable String deploymentId,
            @Nullable String deploymentState,
            List<String> deploymentErrors,
            List<String> bundle) {
        public Publish {
            deploymentErrors = deploymentErrors == null ? List.of() : List.copyOf(deploymentErrors);
            bundle = bundle == null ? List.of() : List.copyOf(bundle);
        }
    }

    /**
     * One module's coverage from a coverage run ({@code --coverage} or {@code [test] coverage}):
     * the whole-report LINE and BRANCH counters of its {@code jacoco.xml}, and its HTML report.
     * {@code dir} is the module directory; {@code html} is the report's {@code index.html}, both
     * absolute. The list is empty for a run that measured nothing.
     */
    public record Coverage(
            String dir,
            String label,
            long linesCovered,
            long linesMissed,
            long branchesCovered,
            long branchesMissed,
            String html) {

        /** Covered lines as a percentage of all lines; {@code 100} when the module has none. */
        public double linePercent() {
            return percent(linesCovered, linesMissed);
        }

        /** Covered branches as a percentage of all branches; {@code 100} when the module has none. */
        public double branchPercent() {
            return percent(branchesCovered, branchesMissed);
        }

        static double percent(long covered, long missed) {
            long total = covered + missed;
            return total == 0 ? 100.0 : covered * 100.0 / total;
        }
    }

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
     * {@link cc.jumpkick.runtime.base.CacheBenefit}.
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
     * the failure output under the failed module inside its "failure details" roll-up. {@code code}
     * names the tool ({@code javac}, {@code kotlinc}, a guard rule); {@code key} is that tool's own
     * name for the diagnostic ({@code compiler.err.cant.resolve.location}), {@code ""} when the tool
     * reports text only.
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
            int worker,
            String key) {

        public Diag {
            if (snippet == null) snippet = List.of();
            else snippet = List.copyOf(snippet);
            if (file == null) file = "";
            if (key == null) key = "";
        }

        /** Every field but the tool's key, which is {@code ""}. */
        public Diag(
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
                    file,
                    line,
                    col,
                    snippetStart,
                    snippet,
                    worker,
                    "");
        }

        /** Without source snippet. */
        public Diag(
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
                @Nullable String stack) {
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

        /** Diagnostic without structured test fields. */
        public Diag(
                String severity,
                String dir,
                @Nullable String step,
                String code,
                String message,
                @Nullable String test,
                @Nullable String exceptionClass) {
            this(severity, dir, step, code, message, test, exceptionClass, "", "", "", "", "");
        }
    }
}
