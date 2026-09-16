// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.run.jsonl.ErrorLine;
import cc.jumpkick.cli.run.jsonl.EtaLine;
import cc.jumpkick.cli.run.jsonl.JobLine;
import cc.jumpkick.cli.run.jsonl.JsonlEnvelope;
import cc.jumpkick.cli.run.jsonl.LabelLine;
import cc.jumpkick.cli.run.jsonl.ModuleFinishLine;
import cc.jumpkick.cli.run.jsonl.ModuleStartLine;
import cc.jumpkick.cli.run.jsonl.OutputLine;
import cc.jumpkick.cli.run.jsonl.PlanFinishLine;
import cc.jumpkick.cli.run.jsonl.PlanStartLine;
import cc.jumpkick.cli.run.jsonl.PreflightLine;
import cc.jumpkick.cli.run.jsonl.ProgressLine;
import cc.jumpkick.cli.run.jsonl.SessionFinishLine;
import cc.jumpkick.cli.run.jsonl.SessionStartLine;
import cc.jumpkick.cli.run.jsonl.TaskFinishLine;
import cc.jumpkick.cli.run.jsonl.TaskStartLine;
import cc.jumpkick.cli.run.jsonl.TestFailureErrorLine;
import cc.jumpkick.cli.run.jsonl.TickUpdateLine;
import cc.jumpkick.cli.run.jsonl.WarnLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceFinishLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceProgressLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceStartLine;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.RequestEnvironment;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Stable wire format for plan events as one-JSON-object-per-line text. Shared by {@link
 * JsonlListener} (stdout for {@code --output json}/{@code jsonl}) and {@link CliSessionTranscript}
 * ({@code details.jsonl}). Every line is a record in {@link cc.jumpkick.cli.run.jsonl}; the methods
 * here stamp the clock and hand over. Centralising the shape means agents, CI, and future MCP tools
 * share one schema — see {@code docs/machine-output.md}.
 *
 * <p>Every object includes {@code schema} ({@link #SCHEMA}), {@code ts} (epoch ms), and {@code type}.
 * Most lines also carry an additive {@code progress} rider (0–100 aggregate % — via
 * {@link #withProgress(String)}.
 */
public final class JsonlShape {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release version churn (see
     * {@code docs/architecture.md} schema freeze). Additive fields only.
     */
    public static final int SCHEMA = JsonlEnvelope.SCHEMA;

    /** Hot-tick event types: heartbeat flush to disk (M4/M5); everything else flushes per line. */
    static final Set<String> HOT_TYPES = Set.of(
            EngineProtocol.PROGRESS,
            EngineProtocol.TICK_UPDATE,
            EngineProtocol.WORKSPACE_PROGRESS,
            EngineProtocol.LABEL,
            EngineProtocol.OUTPUT);

    private static final Object STDOUT_LOCK = new Object();

    private JsonlShape() {}

    /**
     * Emit one workspace-envelope line: progress rider applied, dual-written to the active {@link
     * CliSessionTranscript}; printed to stdout only when {@code toStdout} ({@code --output
     * json}/{@code jsonl}).
     */
    public static void emitJsonl(String line, boolean toStdout) {
        emitEvent(withProgress(line), toStdout);
    }

    /**
     * Emit one line as encoded, with no progress rider: a dev session's process events are not a
     * build's, and a percent on them would describe nothing. Printed to stdout when {@code
     * toStdout}, and appended to the active {@link CliSessionTranscript} either way — the same
     * bytes in both places, so a transcript reads the same whatever the output mode was.
     */
    public static void emitEvent(String line, boolean toStdout) {
        if (!toStdout) {
            CliSessionTranscript.appendActive(line);
            return;
        }
        // One lock around both writes: events arrive on many threads, and the transcript must
        // replay them in the order stdout showed them.
        synchronized (STDOUT_LOCK) {
            System.out.println(line);
            System.out.flush();
            CliSessionTranscript.appendActive(line);
        }
    }

    /**
     * Attach the aggregate {@code progress} percent rider from {@link LiveProgress}.
     * Idempotent if the line already ends with a progress field. Returns {@code line} unchanged when
     * null/blank or not a single JSON object.
     */
    public static String withProgress(String line) {
        return withProgress(line, LiveProgress.get().percent());
    }

    /**
     * Attach {@code progress} (0–100 or JSON {@code null}) before the final {@code }}. Additive only;
     * does not add {@code progress_num}/{@code progress_den}. The one splice in this file: a rider on
     * a line that was already encoded, whatever record encoded it.
     */
    public static String withProgress(String line, @Nullable Double progress) {
        if (line == null || line.isEmpty()) return line;
        int end = line.length() - 1;
        if (line.charAt(end) != '}' || line.charAt(0) != '{') return line;
        // Avoid double-append if a caller already decorated the line.
        if (line.contains("\"progress\":")) return line;
        String token = progress == null ? "null" : WorkspaceProgressTracker.progressToken(progress);
        return Jsonl.append(line, "\"progress\":" + token);
    }

    /** Command session opened (details under project run dir). */
    public static String sessionStart(String command, List<String> argv) {
        String trigger = RequestEnvironment.trigger();
        return new SessionStartLine(
                        nowMillis(),
                        command,
                        argv == null ? List.of() : argv,
                        trigger == null ? "cli" : trigger,
                        RequestEnvironment.session())
                .encode();
    }

    /**
     * Engine job binding — jid (cancel), buildNumber (run dir), ETA, details path. Written into
     * details.jsonl so agents can diagnose a run without other files.
     */
    public static String jobMeta(long jid, long buildNumber, long etaMs, @Nullable String detailsPath) {
        return new JobLine(nowMillis(), jid, buildNumber, etaMs, detailsPath).encode();
    }

    /**
     * Workspace preflight verdict — the stage, its unit counts, and the engine's own label, which
     * is where the dirty-module count is spelled ({@code "7 module(s) dirty"}).
     *
     * <p>Journalled because it was not, and that is very likely why `jk explain` and `jk build`
     * could disagree about how much work a build would do without anyone noticing: the engine
     * decides, tells the TUI, and the decision was gone the moment the bar redrew. An agent reading
     * `details.jsonl` could see every step that ran but not the verdict that chose them.
     */
    public static String preflight(String stage, int done, int totalUnits, String label) {
        return new PreflightLine(nowMillis(), stage, done, totalUnits, label).encode();
    }

    /** ETA estimate event (ms wall). */
    public static String eta(long etaMs) {
        return new EtaLine(nowMillis(), etaMs).encode();
    }

    /** Command session finished — exit code + wall duration (+ optional summary fields). */
    public static String sessionFinish(int exit, long durationMs, @Nullable String wedge, List<String> modules) {
        return new SessionFinishLine(nowMillis(), exit, durationMs, wedge, modules == null ? List.of() : modules)
                .encode();
    }

    static String planStart(BuildPlanView v) {
        return new PlanStartLine(nowMillis(), v.planName(), v.denominator(), v.stepsTotal()).encode();
    }

    static String stepStart(String step, String group, int ticks) {
        return new TaskStartLine(nowMillis(), step, group, ticks).encode();
    }

    static String progress(String step, int delta, BuildPlanView v) {
        return new ProgressLine(nowMillis(), step, delta, v.numerator(), v.denominator()).encode();
    }

    static String tickUpdate(String step, int delta, BuildPlanView v) {
        return new TickUpdateLine(nowMillis(), step, delta, v.denominator()).encode();
    }

    static String label(String step, String label) {
        return new LabelLine(nowMillis(), step, label).encode();
    }

    static String output(String step, String line) {
        return new OutputLine(nowMillis(), step, line).encode();
    }

    static String warn(String step, String code, String msg) {
        return new WarnLine(nowMillis(), step, code, msg).encode();
    }

    static String error(String step, String code, String msg) {
        return error(step, code, msg, "", "");
    }

    /**
     * Error event with optional discrete {@code test} / {@code exceptionClass} fields — emitted (in
     * addition to {@code message}) only when non-empty, so a test failure's parts stay separate on
     * the wire without bloating the common diagnostic shape.
     */
    static String error(String step, String code, String msg, @Nullable String test, @Nullable String exceptionClass) {
        return new ErrorLine(nowMillis(), step, code, msg, test, exceptionClass).encode();
    }

    /**
     * Enriched test-failure error for details.jsonl / --output json: module, engine, class, method,
     * exceptionClass, and a single top-level stack (no nested throwable duplicate).
     */
    static String error(String step, String code, String msg, @Nullable TestFailureInfo failure) {
        if (failure == null) return error(step, code, msg);
        String message = msg == null || msg.isEmpty() ? failure.message() : msg;
        return new TestFailureErrorLine(
                        nowMillis(),
                        step,
                        code,
                        message,
                        failure.module(),
                        failure.engine(),
                        failure.className(),
                        failure.method(),
                        failure.exceptionClass(),
                        failure.worker(),
                        failure.file(),
                        failure.line(),
                        failure.snippetStart(),
                        failure.snippet(),
                        failure.stack())
                .encode();
    }

    static String stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        return new TaskFinishLine(
                        nowMillis(),
                        step,
                        group,
                        status.name(),
                        duration.toMillis(),
                        waited == null ? 0 : waited.toMillis())
                .encode();
    }

    static String planFinish(BuildPlanResult r) {
        return new PlanFinishLine(
                        nowMillis(),
                        r.planName(),
                        r.success(),
                        r.duration().toMillis(),
                        r.warnings().size(),
                        r.errors().size())
                .encode();
    }

    /**
     * Engine workspace aggregate progress. Clients mirror the engine's tracker snapshot;
     * do not recompute from module ticks.
     */
    public static String workspaceProgress(
            String dir, long numerator, long denominator, String phase, int modulesComplete, int modulesTotal) {
        return new WorkspaceProgressLine(nowMillis(), dir, numerator, denominator, phase, modulesComplete, modulesTotal)
                .encode();
    }

    public static String workspaceStart(int modules) {
        return new WorkspaceStartLine(nowMillis(), modules).encode();
    }

    /** Workspace graph finished (all modules done or aborted on graph error). */
    public static String workspaceFinish(boolean success, long durationMs, int modules) {
        return new WorkspaceFinishLine(nowMillis(), success, durationMs, modules).encode();
    }

    /** A workspace module is about to run its plan (brackets nested step events). */
    public static String moduleStart(String dir, String coord) {
        return new ModuleStartLine(nowMillis(), dir, coord).encode();
    }

    /**
     * One guard violation row of the last run, as {@code jk guard --output json|jsonl} streams them
     * after the build: the fields of {@code target/jk-guards.jsonl} under the envelope. The row is
     * already an object, so this is the other splice: the envelope's fields ahead of the row's own.
     */
    public static String guard(String rowJson) {
        String body = rowJson.strip();
        String envelope =
                JsonlEnvelope.open(nowMillis(), EngineProtocol.GUARD_EVENT).finish();
        if (body.length() < 2 || body.charAt(0) != '{' || body.charAt(body.length() - 1) != '}') return envelope;
        return Jsonl.append(envelope, body.substring(1, body.length() - 1));
    }

    /** A workspace module finished (success or failure). */
    public static String moduleFinish(String dir, String coord, boolean success, long durationMs) {
        return new ModuleFinishLine(nowMillis(), dir, coord, success, durationMs).encode();
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
