// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * Server → client event codec (the JSONL shape of domain plan/workspace events). Stays one
 * file so diagnostic/plan-finish overloads cannot drift (scoreboard 800–1,200).
 */
public final class ProtoEvents {

    private ProtoEvents() {}

    public static String preflight(String stage, int done, int total, String label) {
        return "{\"type\":\""
                + EngineProtocol.PREFLIGHT
                + "\",\"stage\":"
                + Jsonl.quote(stage == null ? "" : stage)
                + ",\"done\":"
                + done
                + ",\"total\":"
                + total
                + ",\"label\":"
                + Jsonl.quote(label == null ? "" : label)
                + "}";
    }

    /** Outer invocation phase: {@code phase} wire name + {@code status} ({@code start}|{@code finish}). */
    public static String invocationPhase(String phase, String status) {
        return "{\""
                + EngineProtocol.TYPE_FIELD
                + "\":\""
                + EngineProtocol.INVOCATION_PHASE
                + "\",\"phase\":"
                + Jsonl.quote(phase == null ? "" : phase)
                + ",\"status\":"
                + Jsonl.quote(status == null ? "" : status)
                + "}";
    }

    public static String planModule(String dir, String coord, String planName, int weight, boolean fullyCached) {
        return "{\"type\":\""
                + EngineProtocol.PLAN_MODULE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"planName\":"
                + Jsonl.quote(planName)
                + ",\"weight\":"
                + weight
                + ",\"fullyCached\":"
                + fullyCached
                + "}";
    }

    public static String planStep(String dir, String name, String label, String phase) {
        return "{\"type\":\""
                + EngineProtocol.PLAN_TASK
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"label\":"
                + Jsonl.quote(label)
                + ",\"stage\":"
                + Jsonl.quote(phase)
                + "}";
    }

    public static String planDone(int count) {
        return "{\"type\":\"" + EngineProtocol.PLAN_DONE + "\",\"count\":" + count + "}";
    }

    /**
     * Remaining wall-work {@code R(t)} in ms. {@code millis} duplicates {@code remainingMs} for
     * older readers. No {@code R0} field: nothing consumed it (the CLI seeds from remainingMs,
     * the web from workspace-progress), and at emit time it either equaled remainingMs or was 0
     * (JK-1831).
     */
    public static String eta(long remainingMs) {
        return eta(remainingMs, -1);
    }

    /**
     * As {@link #eta(long)} with optional {@code fullMillis}: schedule-aware EngineProtocol.ETA for a full
     * rebuild of the same plan ({@code jk build --redo}). Used by {@code jk explain} as the
     * denominator for rebuild effort ({@code remaining / full}). Negative {@code fullMillis}
     * omits the field (non-explain EngineProtocol.ETA emitters).
     */
    public static String eta(long remainingMs, long fullMillis) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"type\":\"")
                .append(EngineProtocol.ETA)
                .append("\",\"millis\":")
                .append(remainingMs)
                .append(",\"remainingMs\":")
                .append(remainingMs);
        if (fullMillis >= 0) {
            sb.append(",\"fullMillis\":").append(fullMillis);
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Workspace aggregate progress. {@code progress} is 0–100 (one decimal) from the
     * engine tracker; {@code numerator}/{@code denominator} are the same abstract bar units.
     * {@code phase} is {@code preflight}, {@code execute}, or {@code done}.
     * {@code remainingMs}/{@code R0} mirror the EngineProtocol.ETA remaining-work oracle when seeded.
     */
    public static String workspaceProgress(
            String dir, long numerator, long denominator, String phase, int modulesComplete, int modulesTotal) {
        return workspaceProgress(dir, numerator, denominator, phase, modulesComplete, modulesTotal, -1, 0);
    }

    public static String workspaceProgress(
            String dir,
            long numerator,
            long denominator,
            String phase,
            int modulesComplete,
            int modulesTotal,
            long remainingMs,
            long R0ms) {
        return workspaceProgress(
                dir, numerator, denominator, phase, modulesComplete, modulesTotal, remainingMs, R0ms, Double.NaN);
    }

    /**
     * @param progressPercent explicit aggregate % from {@link
     *     cc.jumpkick.runtime.WorkspaceProgressTracker} (clock or weighted); {@link Double#NaN}
     *     falls back to num/den
     */
    public static String workspaceProgress(
            String dir,
            long numerator,
            long denominator,
            String phase,
            int modulesComplete,
            int modulesTotal,
            long remainingMs,
            long R0ms,
            double progressPercent) {
        String prog = Double.isNaN(progressPercent)
                ? progressPercent(numerator, denominator)
                : cc.jumpkick.runtime.WorkspaceProgressTracker.progressToken(progressPercent);
        return "{\"schema\":1,\"type\":\""
                + EngineProtocol.WORKSPACE_PROGRESS
                + "\",\"dir\":"
                + Jsonl.quote(dir == null ? "" : dir)
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + prog
                + ",\"phase\":"
                + Jsonl.quote(phase == null ? "" : phase)
                + ",\"modulesComplete\":"
                + modulesComplete
                + ",\"modulesTotal\":"
                + modulesTotal
                + ",\"remainingMs\":"
                + remainingMs
                + ",\"R0\":"
                + Math.max(0, R0ms)
                + "}";
    }

    public static String moduleStart(String dir) {
        return "{\"type\":\"" + EngineProtocol.MODULE_START + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }

    public static String planStart(
            String dir,
            String planName,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return "{\"schema\":1,\"type\":\""
                + EngineProtocol.BUILDPLAN_START
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"planName\":"
                + Jsonl.quote(planName)
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + progressPercent(numerator, denominator)
                + ",\"tasksTotal\":"
                + tasksTotal
                + ",\"tasksComplete\":"
                + tasksComplete
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    public static String stepStart(String dir, String step, String phase, int ticks) {
        return "{\"schema\":1,\"type\":\""
                + EngineProtocol.TASK_START
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"task\":"
                + Jsonl.quote(step)
                + ",\"stage\":"
                + Jsonl.quote(phase)
                + ",\"ticks\":"
                + ticks
                + "}";
    }

    private static String progressLike(
            String type,
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return "{\"schema\":1,\"type\":\""
                + type
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"task\":"
                + Jsonl.quote(step)
                + ",\"delta\":"
                + delta
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + progressPercent(numerator, denominator)
                + ",\"tasksTotal\":"
                + tasksTotal
                + ",\"tasksComplete\":"
                + tasksComplete
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    /**
     * Aggregate percent 0–100 (one decimal) matching CLI {@code LiveProgress} / JSONL rider. Emits the
     * JSON token {@code null} when {@code denominator <= 0}.
     */
    static String progressPercent(long numerator, long denominator) {
        return cc.jumpkick.runtime.WorkspaceProgressTracker.progressToken(
                cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(numerator, denominator));
    }

    public static String progress(
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return progressLike(
                EngineProtocol.PROGRESS,
                dir,
                step,
                delta,
                numerator,
                denominator,
                tasksTotal,
                tasksComplete,
                cancelled);
    }

    public static String tickUpdate(
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return progressLike(
                EngineProtocol.TICK_UPDATE,
                dir,
                step,
                delta,
                numerator,
                denominator,
                tasksTotal,
                tasksComplete,
                cancelled);
    }

    public static String label(String dir, String step, String label) {
        return "{\"type\":\""
                + EngineProtocol.LABEL
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"task\":"
                + Jsonl.quote(step)
                + ",\"label\":"
                + Jsonl.quote(label)
                + "}";
    }

    public static String output(String dir, String step, String line) {
        return "{\"type\":\""
                + EngineProtocol.OUTPUT
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"task\":"
                + Jsonl.quote(step)
                + ",\"line\":"
                + Jsonl.quote(line)
                + "}";
    }

    private static String diagnosticLike(
            String type, String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(
                type,
                dir,
                step,
                code,
                message,
                test,
                exceptionClass,
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                java.util.List.of(),
                0);
    }

    /**
     * Diagnostic/error line. Additive fields ({@code module}, {@code engine}, {@code class},
     * {@code method}, {@code stack}, source {@code file}/{@code line}/{@code snippet}, nested
     * {@code throwable}) are omitted when empty so non-test diagnostics stay small.
     */
    private static String diagnosticLike(
            String type,
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
        return diagnosticLike(
                type,
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
                java.util.List.of(),
                0);
    }

    private static String diagnosticLike(
            String type,
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
            String stack,
            String file,
            int line,
            int snippetStart,
            java.util.List<String> snippet,
            int worker) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"type\":")
                .append(Jsonl.quote(type))
                .append(",\"dir\":")
                .append(Jsonl.quote(dir))
                .append(",\"task\":")
                .append(Jsonl.quote(step))
                .append(",\"code\":")
                .append(Jsonl.quote(code))
                .append(",\"message\":")
                .append(Jsonl.quote(message));
        if (test != null && !test.isEmpty()) b.append(",\"test\":").append(Jsonl.quote(test));
        if (module != null && !module.isEmpty()) b.append(",\"module\":").append(Jsonl.quote(module));
        if (engine != null && !engine.isEmpty()) b.append(",\"engine\":").append(Jsonl.quote(engine));
        if (className != null && !className.isEmpty()) {
            b.append(",\"testClass\":").append(Jsonl.quote(className));
            b.append(",\"class\":").append(Jsonl.quote(className));
        }
        if (method != null && !method.isEmpty()) b.append(",\"method\":").append(Jsonl.quote(method));
        if (exceptionClass != null && !exceptionClass.isEmpty())
            b.append(",\"exceptionClass\":").append(Jsonl.quote(exceptionClass));
        if (file != null && !file.isEmpty()) b.append(",\"file\":").append(Jsonl.quote(file));
        if (line > 0) b.append(",\"line\":").append(line);
        if (snippetStart > 0) b.append(",\"snippetStart\":").append(snippetStart);
        if (worker > 0) b.append(",\"worker\":").append(worker);
        if (snippet != null && !snippet.isEmpty()) {
            b.append(",\"snippet\":[");
            for (int i = 0; i < snippet.size(); i++) {
                if (i > 0) b.append(',');
                b.append(Jsonl.quote(snippet.get(i)));
            }
            b.append(']');
        }
        // The stack is serialized exactly once, top-level. The old nested "throwable" object
        // duplicated class/message/stack per line — with no reader that could not already use
        // the top-level fields — doubling every failure's wire cost.
        if (stack != null && !stack.isEmpty()) {
            b.append(",\"stack\":").append(Jsonl.quote(stack));
        }
        return b.append('}').toString();
    }

    public static String warn(String dir, String step, String code, String message) {
        return diagnosticLike(EngineProtocol.WARN, dir, step, code, message, "", "");
    }

    public static String errorLine(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(EngineProtocol.ERROR_LINE, dir, step, code, message, test, exceptionClass);
    }

    /** Enriched test-failure error line (module / engine / class / method / stack). */
    public static String errorLine(
            String dir,
            String step,
            String code,
            String message,
            String module,
            String engine,
            String className,
            String method,
            String exceptionClass,
            String stack) {
        return diagnosticLike(
                EngineProtocol.ERROR_LINE,
                dir,
                step,
                code,
                message,
                "",
                exceptionClass,
                module,
                engine,
                className,
                method,
                stack);
    }

    /** Full test-failure error line including optional source snippet. */
    public static String errorLine(
            String dir, String step, String code, String message, cc.jumpkick.run.TestFailureInfo failure) {
        if (failure == null) return errorLine(dir, step, code, message, "", "");
        return diagnosticLike(
                EngineProtocol.ERROR_LINE,
                dir,
                step,
                code,
                message == null || message.isEmpty() ? failure.message() : message,
                "",
                failure.exceptionClass(),
                failure.module(),
                failure.engine(),
                failure.className(),
                failure.method(),
                failure.stack(),
                failure.file(),
                failure.line(),
                failure.snippetStart(),
                failure.snippet(),
                failure.worker());
    }

    public static String planDiagnostic(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(EngineProtocol.BUILDPLAN_DIAGNOSTIC, dir, step, code, message, test, exceptionClass);
    }

    public static String planDiagnostic(
            String dir,
            String step,
            String code,
            String message,
            String module,
            String engine,
            String className,
            String method,
            String exceptionClass,
            String stack) {
        return diagnosticLike(
                EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                dir,
                step,
                code,
                message,
                "",
                exceptionClass,
                module,
                engine,
                className,
                method,
                stack);
    }

    public static String planDiagnostic(
            String dir, String step, String code, String message, cc.jumpkick.run.TestFailureInfo failure) {
        if (failure == null) return planDiagnostic(dir, step, code, message, "", "");
        return diagnosticLike(
                EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                dir,
                step,
                code,
                message == null || message.isEmpty() ? failure.message() : message,
                "",
                failure.exceptionClass(),
                failure.module(),
                failure.engine(),
                failure.className(),
                failure.method(),
                failure.stack(),
                failure.file(),
                failure.line(),
                failure.snippetStart(),
                failure.snippet(),
                failure.worker());
    }

    /** @see #stepFinish(String, String, String, String, long) */
    public static String stepFinish(String dir, String step, String phase, String status) {
        return stepFinish(dir, step, phase, status, 0L);
    }

    /**
     * Server → client: step terminal. {@code millis} is wall-clock duration (additive schema field;
     * pre-1.0 clients may ignore it).
     */
    public static String stepFinish(String dir, String step, String phase, String status, long millis) {
        return "{\"type\":\""
                + EngineProtocol.TASK_FINISH
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"task\":"
                + Jsonl.quote(step)
                + ",\"stage\":"
                + Jsonl.quote(phase)
                + ",\"status\":"
                + Jsonl.quote(status)
                + ",\"millis\":"
                + millis
                + "}";
    }

    public static String planFinish(String dir, boolean success) {
        return planFinish(dir, success, false);
    }

    /** Single-plan terminal with optional cancel flag. */
    public static String planFinish(String dir, boolean success, boolean cancelled) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"build\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk test} run's counts
     * (total/succeeded/failed/skipped) — absent (-1) for a plain {@code buildWorkspace} plan-finish.
     * Bundled into the same message rather than a separate terminal one because the client must know
     * these counts <em>before</em> dispatching this event to its console listener: the listener's own
     * {@code planFinish} handler is what renders the "Passed N tests" summary line.
     */
    public static String planFinish(
            String dir, boolean success, long total, long succeeded, long failed, long skipped) {
        return planFinish(dir, success, null, total, succeeded, failed, skipped);
    }

    /**
     * As {@link #planFinish(String, boolean, long, long, long, long)}, additionally carrying a {@code
     * jk build} run's outcome (see {@code BuildPlanner.BUILD_OUTCOME}, e.g. {@code "up-to-date"}/
     * {@code "no-sources"}) — {@code null} when not applicable (a workspace per-module plan, or a
     * test-only run). Like the test counts, this rides along on {@code plan-finish} because the
     * client's console listener renders its summary line from within its own {@code planFinish}
     * handler, before any later message could arrive.
     */
    public static String planFinish(
            String dir, boolean success, String buildOutcome, long total, long succeeded, long failed, long skipped) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"build\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"buildOutcome\":"
                + Jsonl.quote(buildOutcome)
                + ",\"testTotal\":"
                + total
                + ",\"testSucceeded\":"
                + succeeded
                + ",\"testFailed\":"
                + failed
                + ",\"testSkipped\":"
                + skipped
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk lock}/{@code jk
     * update} module's written-lockfile counts (packages / packages-with-sources / plugins) — the
     * structured ingredients of the client's summary lines, which its console listener renders from
     * within its own {@code planFinish} handler.
     */
    public static String planFinishLock(String dir, boolean success, long packages, long sources, long plugins) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"lock\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"lockPackages\":"
                + packages
                + ",\"lockSources\":"
                + sources
                + ",\"lockPlugins\":"
                + plugins
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk sync} run's
     * fetched/up-to-date counts for the client's summary line ({@code "N fetched, M up-to-date"}).
     */
    public static String planFinishSync(String dir, boolean success, long fetched, long upToDate) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"sync\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"syncFetched\":"
                + fetched
                + ",\"syncUpToDate\":"
                + upToDate
                + "}";
    }

    /** Opens one module's event scope in a {@code jk lock}/{@code jk update} cascade (see {@link EngineProtocol#LOCK_MODULE}). */
    public static String lockModule(String dir, String coord) {
        return "{\"type\":\"" + EngineProtocol.LOCK_MODULE + "\",\"dir\":" + Jsonl.quote(dir) + ",\"coord\":"
                + Jsonl.quote(coord) + "}";
    }

    /** One resolved package, streamed as it is recorded (see {@link EngineProtocol#LOCK_PACKAGE}). */
    public static String lockPackage(String dir, String name, String version) {
        return lockPackage(dir, name, version, -1);
    }

    /**
     * One resolved package (or a coalesced sample). {@code totalSeen} ≥ 0 is the cumulative package
     * count at emit time (human-paced coalescing,; {@code -1} means “one package, no total”.
     */
    public static String lockPackage(String dir, String name, String version, int totalSeen) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"type\":\"")
                .append(EngineProtocol.LOCK_PACKAGE)
                .append("\",\"dir\":")
                .append(Jsonl.quote(dir))
                .append(",\"name\":")
                .append(Jsonl.quote(name))
                .append(",\"version\":")
                .append(Jsonl.quote(version));
        if (totalSeen >= 0) {
            sb.append(",\"total\":").append(totalSeen);
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Terminal for a lock/update request. {@code exitCode} is computed engine-side (the engine saw
     * the step statuses: a failed {@code resolve} step exits 6, other failures exit 2/CONFIG);
     * {@code errors} carries pre-plan failures (manifest parse, workspace module load) as plain
     * uncolored text for the client to render. {@code refreshed} is {@code jk update --git}'s
     * refreshed-dependency count, {@code -1} for every other request.
     */
    public static String lockFinish(boolean success, int exitCode, List<String> errors, int refreshed) {
        return "{\"type\":\""
                + EngineProtocol.LOCK_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + EngineProtocol.quoteArray(errors)
                + ",\"refreshed\":"
                + refreshed
                + "}";
    }

    // ---- hosted worker-command events (server → client) --------------------------------------------

    /** One OSV finding (see {@link EngineProtocol#AUDIT_FINDING}) — plain structured fields, no theming. */
    public static String auditFinding(
            String dir, String module, String version, String vulnId, String severity, String summary) {
        return "{\"type\":\""
                + EngineProtocol.AUDIT_FINDING
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"module\":"
                + Jsonl.quote(module)
                + ",\"version\":"
                + Jsonl.quote(version)
                + ",\"vulnId\":"
                + Jsonl.quote(vulnId)
                + ",\"severity\":"
                + Jsonl.quote(severity)
                + ",\"summary\":"
                + Jsonl.quote(summary)
                + "}";
    }

    /**
     * One file's format result (see {@link EngineProtocol#FORMAT_FILE}). {@code index}/{@code total} drive the
     * client's per-file progress bar ({@code total} is known engine-side before the worker forks).
     */
    public static String formatFile(String dir, String path, String status, String message, int index, int total) {
        return "{\"type\":\""
                + EngineProtocol.FORMAT_FILE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"path\":"
                + Jsonl.quote(path)
                + ",\"status\":"
                + Jsonl.quote(status)
                + ",\"message\":"
                + Jsonl.quote(message)
                + ",\"index\":"
                + index
                + ",\"total\":"
                + total
                + "}";
    }

    /** One import progress note (see {@link EngineProtocol#IMPORT_NOTE}). */
    public static String importNote(String dir, String kind, String text) {
        return "{\"type\":\""
                + EngineProtocol.IMPORT_NOTE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"kind\":"
                + Jsonl.quote(kind)
                + ",\"text\":"
                + Jsonl.quote(text)
                + "}";
    }

    /**
     * Terminal for {@link EngineProtocol#PROVISION_REQUEST}. {@code bin} is the provisioned tool's launcher path
     * ({@code null} on failure); {@code source}/{@code version} feed the client's one-line
     * "Maven X downloaded" note; {@code diag} is the worker's passthrough chatter, carried only when
     * {@code exit != 0}.
     */
    public static String provisionResult(
            String bin, String version, String source, String error, int exit, String diag) {
        return "{\"type\":\""
                + EngineProtocol.PROVISION_RESULT
                + "\",\"bin\":"
                + Jsonl.quote(bin)
                + ",\"version\":"
                + Jsonl.quote(version)
                + ",\"source\":"
                + Jsonl.quote(source)
                + ",\"error\":"
                + Jsonl.quote(error)
                + ",\"exit\":"
                + exit
                + ",\"diag\":"
                + Jsonl.quote(diag)
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk format} run's
     * counts and the formatter worker's exit code ({@code jk format --check} exits non-zero when
     * files need formatting — a legitimate outcome, not a plan failure, so it rides here rather
     * than failing the plan). {@code total} of 0 means no sources were found.
     */
    public static String planFinishFormat(
            String dir, boolean success, int changed, int clean, int errors, int total, int workerExit) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"format\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"formatChanged\":"
                + changed
                + ",\"formatClean\":"
                + clean
                + ",\"formatErrors\":"
                + errors
                + ",\"formatTotal\":"
                + total
                + ",\"formatWorkerExit\":"
                + workerExit
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link EngineProtocol#GIT_FETCH_REQUEST}'s
     * materialized checkout path and resolved commit sha ({@code null} when the fetch failed).
     */
    public static String planFinishGitFetch(String dir, boolean success, String checkout, String sha) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"git-fetch\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"gitCheckout\":"
                + Jsonl.quote(checkout)
                + ",\"gitSha\":"
                + Jsonl.quote(sha)
                + "}";
    }

    /** As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk publish} run's uploaded-file count. */
    public static String planFinishPublish(String dir, boolean success, int files) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"publish\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"publishFiles\":"
                + files
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk import} run's
     * worker exit code, warning count, and error/diagnostic text (all plain — the client prefixes
     * and renders).
     */
    public static String planFinishImport(
            String dir, boolean success, int exitCode, int warnings, String error, String diag) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"import\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"importExit\":"
                + exitCode
                + ",\"importWarnings\":"
                + warnings
                + ",\"importError\":"
                + Jsonl.quote(error)
                + ",\"importDiag\":"
                + Jsonl.quote(diag)
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean, long, long, long, long)} (the image plan runs the full
     * plan, so test counts ride along for the exit-code logic), additionally carrying the
     * structured ingredients of the Image chip's success tail: exactly one of {@code imageTarball}
     * (tarball mode), {@code imageDaemonExe} (local-daemon load), or neither (registry push, render
     * {@code imageRef}) is non-null; {@code imageName}/{@code imageVersion} name the image in
     * daemon mode.
     */
    public static String planFinishImage(
            String dir,
            boolean success,
            long testTotal,
            long testSucceeded,
            long testFailed,
            long testSkipped,
            String ref,
            String tarball,
            String name,
            String version,
            String daemonExe) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"image\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"testTotal\":"
                + testTotal
                + ",\"testSucceeded\":"
                + testSucceeded
                + ",\"testFailed\":"
                + testFailed
                + ",\"testSkipped\":"
                + testSkipped
                + ",\"imageRef\":"
                + Jsonl.quote(ref)
                + ",\"imageTarball\":"
                + Jsonl.quote(tarball)
                + ",\"imageName\":"
                + Jsonl.quote(name)
                + ",\"imageVersion\":"
                + Jsonl.quote(version)
                + ",\"imageDaemonExe\":"
                + Jsonl.quote(daemonExe)
                + "}";
    }

    public static String moduleFinish(String dir, String coord, boolean success, int exitCode, long millis) {
        return moduleFinish(dir, coord, success, exitCode, millis, true);
    }

    /**
     * @param didWork whether a productive step actually ran (false = pure cache check;.
     * Additive field — older clients ignore it.
     */
    public static String moduleFinish(
            String dir, String coord, boolean success, int exitCode, long millis, boolean didWork) {
        return "{\"type\":\""
                + EngineProtocol.MODULE_FINISH
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"millis\":"
                + millis
                + ",\"didWork\":"
                + didWork
                + "}";
    }

    public static String workspaceFinish(boolean success, int exitCode, List<String> errors) {
        return workspaceFinish(success, exitCode, errors, false);
    }

    /**
     * Workspace terminal. {@code cancelled} is additive so clients can settle as
     * "cancelled" rather than treating a user kill as a crash/disconnect.
     */
    public static String workspaceFinish(boolean success, int exitCode, List<String> errors, boolean cancelled) {
        return "{\"type\":\""
                + EngineProtocol.WORKSPACE_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + EngineProtocol.quoteArray(errors)
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    /**
     * Append {@code "cancelled":true|false} to a plan-finish (or similar) JSON object. Additive
     * field for without churning every {@code planFinish*} overload.
     */
    public static String withCancelled(String jsonLine, boolean cancelled) {
        if (jsonLine == null || jsonLine.isEmpty()) return jsonLine;
        int end = jsonLine.lastIndexOf('}');
        if (end <= 0) return jsonLine;
        return jsonLine.substring(0, end) + ",\"cancelled\":" + cancelled + "}";
    }
}
