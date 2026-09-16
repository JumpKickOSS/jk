// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.config.Redacted;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Server → client event codec (the JSONL shape of domain plan/workspace events). Stays one
 * file so diagnostic/plan-finish overloads cannot drift (scoreboard 800–1,200).
 */
public final class ProtoEvents {

    private ProtoEvents() {}

    public static String preflight(String stage, int done, int total, String label) {
        return new PreflightEvent(stage, done, total, label).encode();
    }

    public static String invocationPhase(String phase, String status) {
        return new InvocationPhaseEvent(phase, status).encode();
    }

    public static String planModule(String dir, String coord, String planName, int weight, boolean fullyCached) {
        return new PlanModuleEvent(dir, coord, planName, weight, fullyCached).encode();
    }

    public static String planStep(String dir, String name, String label, String phase) {
        return new PlanTaskEvent(dir, name, label, phase).encode();
    }

    public static String planDone(int count) {
        return new PlanDoneEvent(count).encode();
    }

    public static String eta(long remainingMs) {
        return eta(remainingMs, -1);
    }

    public static String eta(long remainingMs, long fullMillis) {
        return new EtaEvent(remainingMs, fullMillis).encode();
    }

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
        double percent = Double.isNaN(progressPercent)
                ? WorkspaceProgressTracker.percentOf(numerator, denominator)
                : progressPercent;
        return new WorkspaceProgressEvent(
                        dir, numerator, denominator, percent, phase, modulesComplete, modulesTotal, remainingMs, R0ms)
                .encode();
    }

    public static String moduleStart(String dir) {
        return new ModuleStartEvent(dir).encode();
    }

    public static String planStart(
            String dir,
            String planName,
            long numerator,
            long denominator,
            int tasksTotal,
            int tasksComplete,
            boolean cancelled) {
        return new PlanStartEvent(dir, planName, numerator, denominator, tasksTotal, tasksComplete, cancelled).encode();
    }

    public static String stepStart(String dir, String step, String phase, int ticks) {
        return new TaskStartEvent(dir, step, phase, ticks).encode();
    }

    static String progressPercent(long numerator, long denominator) {
        return WorkspaceProgressTracker.progressToken(WorkspaceProgressTracker.percentOf(numerator, denominator));
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
        return new ProgressEvent(dir, step, delta, numerator, denominator, tasksTotal, tasksComplete, cancelled)
                .encode();
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
        return new TickUpdateEvent(dir, step, delta, numerator, denominator, tasksTotal, tasksComplete, cancelled)
                .encode();
    }

    public static String label(String dir, String step, String label) {
        return new LabelEvent(dir, step, label).encode();
    }

    public static String output(String dir, String step, String line) {
        return new OutputEvent(dir, step, line).encode();
    }

    private static final TestFailureInfo NO_FAILURE = failure("", "", "", "", "", "");

    private static TestFailureInfo failure(
            String module, String engine, String className, String method, String exceptionClass, String stack) {
        return new TestFailureInfo(
                module, engine, className, method, exceptionClass, "", stack, 0, "", 0, 0, List.of());
    }

    /** The three diagnostic shapes share one field order; the record is chosen by the wire type. */
    private static String diagnostic(
            String type, String dir, String step, String code, String message, String test, TestFailureInfo f) {
        String msg = message == null || message.isEmpty() ? f.message() : message;
        return switch (type) {
            case EngineProtocol.WARN ->
                WarnEvent.of(dir, step, code, msg, test, f).encode();
            case EngineProtocol.ERROR_LINE ->
                ErrorLineEvent.of(dir, step, code, msg, test, f).encode();
            default -> PlanDiagnosticEvent.of(dir, step, code, msg, test, f).encode();
        };
    }

    public static String warn(String dir, String step, String code, String message) {
        return diagnostic(EngineProtocol.WARN, dir, step, code, message, "", NO_FAILURE);
    }

    public static String errorLine(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnostic(
                EngineProtocol.ERROR_LINE, dir, step, code, message, test, failure("", "", "", "", exceptionClass, ""));
    }

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
        return diagnostic(
                EngineProtocol.ERROR_LINE,
                dir,
                step,
                code,
                message,
                "",
                failure(module, engine, className, method, exceptionClass, stack));
    }

    public static String errorLine(String dir, String step, String code, String message, TestFailureInfo failure) {
        return diagnostic(
                EngineProtocol.ERROR_LINE, dir, step, code, message, "", failure == null ? NO_FAILURE : failure);
    }

    public static String planDiagnostic(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnostic(
                EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                dir,
                step,
                code,
                message,
                test,
                failure("", "", "", "", exceptionClass, ""));
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
        return diagnostic(
                EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                dir,
                step,
                code,
                message,
                "",
                failure(module, engine, className, method, exceptionClass, stack));
    }

    public static String planDiagnostic(String dir, String step, String code, String message, TestFailureInfo failure) {
        return diagnostic(
                EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                dir,
                step,
                code,
                message,
                "",
                failure == null ? NO_FAILURE : failure);
    }

    public static String stepFinish(
            String dir, String step, String phase, String status, long millis, long waitMillis) {
        return new TaskFinishEvent(dir, step, phase, status, millis, waitMillis).encode();
    }

    public static String planFinish(String dir, boolean success) {
        return planFinish(dir, success, false);
    }

    public static String planFinish(String dir, boolean success, boolean cancelled) {
        return new PlanFinishEvent(dir, success, cancelled).encode();
    }

    public static String planFinish(
            String dir, boolean success, long total, long succeeded, long failed, long skipped) {
        return planFinish(dir, success, null, total, succeeded, failed, skipped);
    }

    public static String planFinish(
            String dir,
            boolean success,
            @Nullable String buildOutcome,
            long total,
            long succeeded,
            long failed,
            long skipped) {
        return new PlanFinishOutcomeEvent(dir, success, buildOutcome, total, succeeded, failed, skipped).encode();
    }

    public static String planFinishLock(
            String dir,
            boolean success,
            long packages,
            long sources,
            long plugins,
            long unverified,
            List<String> insecureRepos) {
        return new PlanFinishLockEvent(dir, success, packages, sources, plugins, unverified, insecureRepos).encode();
    }

    public static String planFinishSync(String dir, boolean success, long fetched, long upToDate) {
        return new PlanFinishSyncEvent(dir, success, fetched, upToDate).encode();
    }

    public static String lockModule(String dir, String coord) {
        return new LockModuleEvent(dir, coord).encode();
    }

    public static String lockPackage(String dir, String name, String version) {
        return lockPackage(dir, name, version, -1);
    }

    public static String lockPackage(@Nullable String dir, String name, @Nullable String version, int totalSeen) {
        return new LockPackageEvent(dir, name, version, totalSeen).encode();
    }

    public static String lockFinish(boolean success, int exitCode, List<String> errors, int refreshed) {
        return new LockFinishEvent(success, exitCode, errors, refreshed).encode();
    }

    public static String updateRewrite(String dir, String table, String handle, String module, String from, String to) {
        return new UpdateRewriteEvent(dir, table, handle, module, from, to).encode();
    }

    public static String auditFinding(String dir, AuditReport.Finding finding) {
        return AuditFindingEvent.of(dir, finding).encode();
    }

    public static String formatFile(
            String dir, String path, String status, @Nullable String message, int index, int total) {
        return new FormatFileEvent(dir, path, status, message, index, total).encode();
    }

    public static String importNote(String dir, String kind, String text) {
        return new ImportNoteEvent(dir, kind, text).encode();
    }

    public static String provisionResult(
            @Nullable String bin,
            @Nullable String version,
            @Nullable String source,
            @Nullable String verification,
            @Nullable String error,
            int exit) {
        return new ProvisionResultEvent(bin, version, source, verification, error, exit).encode();
    }

    public static String mvnResultsResult(@Nullable String results, @Nullable String error) {
        return new MvnResultsResultEvent(results, error).encode();
    }

    public static String planFinishFormat(String dir, boolean success, int total, int workerExit) {
        return new PlanFinishFormatEvent(dir, success, total, workerExit).encode();
    }

    public static String planFinishGitFetch(
            String dir, boolean success, @Nullable String checkout, @Nullable String sha) {
        return new PlanFinishGitFetchEvent(dir, success, checkout, sha).encode();
    }

    public static String planFinishPublish(String dir, boolean success, int files) {
        return planFinishPublish(dir, success, files, List.of());
    }

    public static String planFinishPublish(String dir, boolean success, int files, List<String> written) {
        return new PlanFinishPublishEvent(dir, success, files, written).encode();
    }

    public static String planFinishImport(
            String dir, boolean success, int exitCode, int warnings, @Nullable String error, @Nullable String diag) {
        return new PlanFinishImportEvent(dir, success, exitCode, warnings, error, diag).encode();
    }

    public static String planFinishImage(
            String dir,
            boolean success,
            long total,
            long succeeded,
            long failed,
            long skipped,
            @Nullable String ref,
            @Nullable String tarball,
            @Nullable String name,
            @Nullable String version,
            @Nullable String daemonExe) {
        return new PlanFinishImageEvent(
                        dir, success, total, succeeded, failed, skipped, ref, tarball, name, version, daemonExe)
                .encode();
    }

    public static String moduleFinish(String dir, String coord, boolean success, int exitCode, long millis) {
        return moduleFinish(dir, coord, success, exitCode, millis, true);
    }

    public static String moduleFinish(
            String dir, String coord, boolean success, int exitCode, long millis, boolean didWork) {
        return moduleFinish(dir, coord, success, exitCode, millis, didWork, false);
    }

    public static String moduleFinish(
            String dir, String coord, boolean success, int exitCode, long millis, boolean didWork, boolean cancelled) {
        return moduleFinish(dir, coord, success, exitCode, millis, didWork, cancelled, null);
    }

    public static String moduleFinish(
            String dir,
            String coord,
            boolean success,
            int exitCode,
            long millis,
            boolean didWork,
            boolean cancelled,
            ModuleOutcome.@Nullable Image image) {
        return new ModuleFinishEvent(dir, coord, success, exitCode, millis, didWork, cancelled, image).encode();
    }

    public static String workspaceFinish(boolean success, int exitCode, List<Redacted> errors, boolean cancelled) {
        return new WorkspaceFinishEvent(
                        success, exitCode, errors.stream().map(Redacted::text).toList(), cancelled)
                .encode();
    }

    public static String withCancelled(@Nullable String jsonLine, boolean cancelled) {
        if (jsonLine == null) throw new IllegalArgumentException("jsonLine must be an encoded object");
        return Jsonl.append(jsonLine, "\"cancelled\":" + cancelled);
    }
}
