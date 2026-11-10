// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.run.jsonl.JobLine;
import cc.jumpkick.cli.run.jsonl.SessionFinishLine;
import cc.jumpkick.cli.run.jsonl.SlowestTestsLine;
import cc.jumpkick.cli.run.jsonl.WorkspaceStartLine;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.protocol.ClassFinishEvent;
import cc.jumpkick.wire.protocol.DownloadsEvent;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ErrorLineEvent;
import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.EventEnvelope;
import cc.jumpkick.wire.protocol.FailureTextRefs;
import cc.jumpkick.wire.protocol.LabelEvent;
import cc.jumpkick.wire.protocol.ModuleFinishEvent;
import cc.jumpkick.wire.protocol.ModuleStartEvent;
import cc.jumpkick.wire.protocol.NoteEvent;
import cc.jumpkick.wire.protocol.OutputEvent;
import cc.jumpkick.wire.protocol.PlanFinishEvent;
import cc.jumpkick.wire.protocol.PlanStartEvent;
import cc.jumpkick.wire.protocol.PreflightEvent;
import cc.jumpkick.wire.protocol.ProgressEvent;
import cc.jumpkick.wire.protocol.RequestEnvironment;
import cc.jumpkick.wire.protocol.TaskFinishEvent;
import cc.jumpkick.wire.protocol.TaskStartEvent;
import cc.jumpkick.wire.protocol.TickUpdateEvent;
import cc.jumpkick.wire.protocol.WarnEvent;
import cc.jumpkick.wire.protocol.WorkspaceFinishEvent;
import cc.jumpkick.wire.protocol.WorkspaceProgressEvent;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import cc.jumpkick.wire.transcript.SessionStartLine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Stable wire format for plan events as one-JSON-object-per-line text. Shared by {@link
 * JsonlListener} (stdout for {@code -O jsonl}) and {@link CliSessionTranscript}
 * ({@code details.jsonl}). Every line is a record in {@link cc.jumpkick.cli.run.jsonl}; the methods
 * here stamp the clock and hand over. Centralising the shape means agents, CI, and future MCP tools
 * share one schema — see {@code docs/user/machine-output.md}.
 *
 * <p>Every object includes {@code schema} ({@link #SCHEMA}), {@code ts} (epoch ms), and {@code type}.
 * Most lines also carry an additive {@code progress} rider (0–100 aggregate % — via
 * {@link #withProgress(String)}.
 */
public final class JsonlShape {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release version churn (see
     * {@code docs/contributors/architecture.md} schema freeze). Additive fields only.
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

    /**
     * Whether a {@code job} line goes to stdout as the engine starts a job: armed by a workspace
     * view writing {@code -O jsonl}, so an agent has the job's id and its {@code details.jsonl} path
     * from the stream itself; never for a command whose stdout is one JSON document.
     */
    private static volatile boolean jobLineOnStdout;

    /**
     * The failure texts this process's event stream (stdout and the session transcript) has
     * carried. One for the process: every module's listener writes the same stream.
     */
    static final FailureTextRefs STREAM_TEXTS = new FailureTextRefs();

    /** A step's events name their module by the {@code module} rider; the module's dir is noise. */
    private static final Set<String> STEP_DROPS = Set.of("dir");

    /**
     * As {@link #STEP_DROPS}, and the plan-local percent a plan's start and ticks carry: the line's
     * {@code progress} rider is the run's.
     */
    private static final Set<String> PROGRESS_DROPS = Set.of("dir", "progress");

    /** A download frame's per-transfer rows draw the terminal's region; the console sums them. */
    private static final Set<String> DOWNLOAD_DROPS = Set.of("rows");

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

    /** Arm (or disarm) the stdout {@code job} line; see {@link #jobLineOnStdout}. */
    public static void jobLineOnStdout(boolean on) {
        if (on) CliSessionTranscript.listenForJobs();
        jobLineOnStdout = on;
    }

    /** The engine started a job: its {@code job} line on stdout, when a JSONL workspace view armed it. */
    static void jobStarted(long jid, long buildNumber, long etaMs, @Nullable String detailsPath) {
        if (!jobLineOnStdout) return;
        synchronized (STDOUT_LOCK) {
            System.out.println(jobMeta(jid, buildNumber, etaMs, detailsPath));
            System.out.flush();
        }
    }

    /**
     * Engine job binding — jid (cancel), buildNumber (run dir), ETA, details path. Written into
     * details.jsonl so agents can diagnose a run without other files, and to {@code -O jsonl}
     * stdout ({@link #jobStarted}).
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
        return console(new PreflightEvent(stage, done, totalUnits, label).encode());
    }

    /** One workspace-level line the user keeps, journalled for the same reason as {@link #preflight}. */
    public static String note(String text) {
        return console(new NoteEvent(text).encode());
    }

    /** ETA estimate event: the work remaining, in ms. */
    public static String eta(long remainingMs) {
        return console(new EtaEvent(remainingMs, -1).encode());
    }

    /** Command session finished — exit code + wall duration (+ optional summary fields). */
    public static String sessionFinish(int exit, long durationMs, @Nullable String wedge, List<String> modules) {
        return new SessionFinishLine(nowMillis(), exit, durationMs, wedge, modules == null ? List.of() : modules)
                .encode();
    }

    static String planStart(BuildPlanView v) {
        return console(
                new PlanStartEvent(
                                "",
                                v.planName(),
                                v.numerator(),
                                v.denominator(),
                                v.stepsTotal(),
                                v.stepsComplete(),
                                v.cancelled())
                        .encode(),
                null,
                PROGRESS_DROPS);
    }

    static String stepStart(String step, String group, int ticks) {
        return step(new TaskStartEvent("", step, group, ticks).encode());
    }

    static String progress(String step, int delta, BuildPlanView v) {
        return console(
                new ProgressEvent(
                                "",
                                step,
                                delta,
                                v.numerator(),
                                v.denominator(),
                                v.stepsTotal(),
                                v.stepsComplete(),
                                v.cancelled())
                        .encode(),
                null,
                PROGRESS_DROPS);
    }

    static String tickUpdate(String step, int delta, BuildPlanView v) {
        return console(
                new TickUpdateEvent(
                                "",
                                step,
                                delta,
                                v.numerator(),
                                v.denominator(),
                                v.stepsTotal(),
                                v.stepsComplete(),
                                v.cancelled())
                        .encode(),
                null,
                PROGRESS_DROPS);
    }

    static String label(String step, String label) {
        return step(new LabelEvent("", step, label).encode());
    }

    /** One line a step printed, as {@code -O jsonl} writes it. */
    public static String output(String step, String line) {
        return step(new OutputEvent("", step, line).encode());
    }

    static String warn(String step, String code, String msg) {
        return warn(step, code, msg, false);
    }

    /** A warning as {@code -O jsonl} writes it; {@code replayed} when a step that did not run said it again. */
    static String warn(String step, String code, String msg, boolean replayed) {
        return step(new WarnEvent(
                        "", step, code, msg, "", "", "", "", "", "", "", "", 0, 0, 0, List.of(), "", 0, 0, replayed)
                .encode());
    }

    /** A replayed warning of {@code module}, a module the build did not run, as {@code -O jsonl} writes it. */
    public static String moduleWarn(String module, String step, String code, String msg) {
        return Jsonl.append(warn(step, code, msg, true), "\"module\":" + Jsonl.quote(module));
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
        return error(new ErrorLineEvent(
                "",
                step,
                code,
                msg,
                test == null ? "" : test,
                "",
                "",
                "",
                "",
                "",
                exceptionClass == null ? "" : exceptionClass,
                "",
                0,
                0,
                0,
                List.of(),
                "",
                0,
                0));
    }

    /** A diagnostic record goes out as {@code error}, the name the console has always given it. */
    private static String error(ErrorLineEvent event) {
        return console(event.encode(), EventCatalog.JSONL_ERROR, STEP_DROPS);
    }

    /**
     * Enriched test-failure error for details.jsonl / -O jsonl: module, engine, class, method,
     * exceptionClass, and a single top-level stack (no nested throwable duplicate). With {@code
     * texts}, a message and stack the stream already carried are sent as {@code sameText}.
     */
    static String error(
            String step, String code, String msg, @Nullable TestFailureInfo failure, @Nullable FailureTextRefs texts) {
        if (failure == null) return error(step, code, msg);
        String message = msg == null || msg.isEmpty() ? failure.message() : msg;
        String stack = failure.stack();
        FailureTextRefs.Ref ref = texts == null ? FailureTextRefs.Ref.NONE : texts.ref(message, stack);
        if (ref.sameText() > 0) {
            message = "";
            stack = "";
        }
        return error(new ErrorLineEvent(
                "",
                step,
                code,
                message,
                "",
                failure.module(),
                failure.suite(),
                failure.engine(),
                failure.className(),
                failure.method(),
                failure.exceptionClass(),
                failure.file(),
                failure.line(),
                failure.snippetStart(),
                failure.worker(),
                failure.snippet(),
                stack,
                ref.textId(),
                ref.sameText()));
    }

    static String classFinish(String step, TestClassResult result) {
        return step(new ClassFinishEvent("", step, result).encode());
    }

    /** The run's slowest classes and methods, once, as the run settles; see {@link SlowestTestsLine}. */
    public static String slowest(SlowestTests.Ranking ranking) {
        return new SlowestTestsLine(nowMillis(), ranking.classes(), ranking.methods()).encode();
    }

    static String stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        return stepFinish(step, group, status, duration, waited, "");
    }

    /** As above, with the step's last word on what it did. */
    static String stepFinish(
            String step, String group, TaskStatus status, Duration duration, Duration waited, String label) {
        return step(new TaskFinishEvent(
                        "",
                        step,
                        group,
                        status.name(),
                        duration.toMillis(),
                        waited == null ? 0 : waited.toMillis(),
                        label)
                .encode());
    }

    static String planFinish(BuildPlanResult r) {
        return step(new PlanFinishEvent(
                        "",
                        r.success(),
                        r.cancelled(),
                        r.planName(),
                        r.duration().toMillis(),
                        r.warnings().size(),
                        r.errors().size())
                .encode());
    }

    /**
     * Engine workspace aggregate progress. Clients mirror the engine's tracker snapshot;
     * do not recompute from module ticks.
     */
    public static String workspaceProgress(String dir, WorkspaceProgressTracker.Snapshot snap) {
        return console(new WorkspaceProgressEvent(
                        dir,
                        snap.numerator(),
                        snap.denominator(),
                        snap.percent(),
                        snap.phase(),
                        snap.modulesComplete(),
                        snap.modulesTotal(),
                        snap.remainingMs(),
                        snap.R0ms())
                .encode());
    }

    /** The run's downloads summed up, without the per-transfer rows; {@code done} closes a burst. */
    public static String downloads(TransferBoard.Snapshot d, boolean done) {
        return console(new DownloadsEvent(d, done).encode(), null, DOWNLOAD_DROPS);
    }

    public static String workspaceStart(int modules) {
        return new WorkspaceStartLine(nowMillis(), modules).encode();
    }

    /** Workspace graph finished with no result to read (aborted before the engine answered). */
    public static String workspaceFinish(boolean success, long millis, int modules) {
        return workspaceFinish(success, millis, modules, List.of());
    }

    /** As above, with why it failed: the reason a client has, in the result's {@code errors}. */
    public static String workspaceFinish(boolean success, long millis, int modules, List<String> errors) {
        return console(
                new WorkspaceFinishEvent(success, success ? 0 : 1, errors, false, null, millis, modules).encode());
    }

    /** Workspace graph finished: the engine's result, with the run's wall time and module count. */
    public static String workspaceFinish(WorkspaceResult result, boolean success, long millis, int modules) {
        return console(new WorkspaceFinishEvent(
                        success, result.exitCode(), result.errors(), result.cancelled(), result.sync(), millis, modules)
                .encode());
    }

    /** A workspace module is about to run its plan (brackets nested step events). */
    public static String moduleStart(String dir, String coord) {
        return console(new ModuleStartEvent(dir, coord).encode());
    }

    /**
     * One guard violation row of the last run, as {@code jk guard -O jsonl} streams them
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

    /** A workspace module finished: succeeded, failed, or stopped by another module's failure. */
    public static String moduleFinish(ModuleOutcome o) {
        return console(ModuleFinishEvent.of(o).encode());
    }

    /** A record a step sent, under the console envelope; the step's module is the line's rider. */
    private static String step(String record) {
        return console(record, null, STEP_DROPS);
    }

    private static String console(String record) {
        return console(record, null, Set.of());
    }

    /**
     * {@code record} under the console envelope ({@code schema}, {@code ts}, and the type: its own,
     * or {@code as} where the console names the event otherwise), less the {@code dropped} fields.
     */
    private static String console(String record, @Nullable String as, Set<String> dropped) {
        String type = as != null ? as : Objects.requireNonNull(EngineProtocol.typeOf(record), record);
        return EventEnvelope.project(JsonlEnvelope.open(nowMillis(), type).finish(), record, dropped);
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
