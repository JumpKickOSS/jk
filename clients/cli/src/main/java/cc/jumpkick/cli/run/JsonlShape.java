// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Stable wire format for plan events as one-JSON-object-per-line text. Shared by {@link
 * JsonlListener} (stdout for {@code --output json}/{@code jsonl}) and {@link CliSessionTranscript}
 * ({@code details.jsonl}).
 * Centralising the shape here means agents, CI, and future MCP tools share one schema — see
 * {@code docs/machine-output.md}.
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
    public static final int SCHEMA = 1;

    /** Hot-tick event types: heartbeat flush to disk (M4/M5); everything else flushes per line. */
    static final Set<String> HOT_TYPES = Set.of("progress", "tick-update", "workspace-progress", "label", "output");

    private static final Object STDOUT_LOCK = new Object();

    private JsonlShape() {}

    /**
     * Emit one workspace-envelope line: progress rider applied, dual-written to the active {@link
     * CliSessionTranscript}; printed to stdout only when {@code toStdout} ({@code --output
     * json}/{@code jsonl}).
     */
    public static void emitJsonl(String line, boolean toStdout) {
        String decorated = withProgress(line);
        if (toStdout) {
            synchronized (STDOUT_LOCK) {
                System.out.println(decorated);
                System.out.flush();
            }
        }
        CliSessionTranscript.appendActive(decorated);
    }

    /** Shared prefix: schema + ts + type. */
    private static StringBuilder open(String type) {
        return new StringBuilder(96)
                .append("{\"schema\":")
                .append(SCHEMA)
                .append(",\"ts\":")
                .append(nowMillis())
                .append(",\"type\":")
                .append(js(type));
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
     * does not add {@code progress_num}/{@code progress_den}.
     */
    public static String withProgress(String line, Double progress) {
        if (line == null || line.isEmpty()) return line;
        int end = line.length() - 1;
        if (line.charAt(end) != '}') return line;
        // Avoid double-append if a caller already decorated the line.
        if (line.contains("\"progress\":")) return line;
        StringBuilder sb = new StringBuilder(line.length() + 24);
        sb.append(line, 0, end);
        sb.append(",\"progress\":");
        sb.append(progress == null ? "null" : cc.jumpkick.runtime.WorkspaceProgressTracker.progressToken(progress));
        sb.append('}');
        return sb.toString();
    }

    /** Command session opened (details under project run dir). */
    public static String sessionStart(String command, List<String> argv) {
        StringBuilder sb = open("session-start").append(",\"command\":").append(js(command));
        sb.append(",\"argv\":[");
        if (argv != null) {
            for (int i = 0; i < argv.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(argv.get(i)));
            }
        }
        return sb.append(']').append('}').toString();
    }

    /**
     * Engine job binding — jid (cancel), buildNumber (run dir), ETA, details path. Written into
     * details.jsonl so agents can diagnose a run without other files.
     */
    public static String jobMeta(long jid, long buildNumber, long etaMs, String detailsPath) {
        StringBuilder sb = open("job");
        if (jid > 0) sb.append(",\"jid\":").append(jid);
        if (buildNumber > 0) sb.append(",\"buildNumber\":").append(buildNumber);
        if (etaMs >= 0) sb.append(",\"etaMs\":").append(etaMs);
        if (detailsPath != null && !detailsPath.isBlank())
            sb.append(",\"detailsPath\":").append(js(detailsPath));
        return sb.append('}').toString();
    }

    /** ETA estimate event (ms wall). */
    public static String eta(long etaMs) {
        return open("eta")
                .append(",\"etaMs\":")
                .append(Math.max(0, etaMs))
                .append('}')
                .toString();
    }

    /** Command session finished — exit code + wall duration (+ optional summary fields). */
    public static String sessionFinish(int exit, long durationMs, String wedge, List<String> modules) {
        StringBuilder sb = open("session-finish")
                .append(",\"exit\":")
                .append(exit)
                .append(",\"duration_ms\":")
                .append(durationMs);
        if (wedge != null && !wedge.isBlank()) sb.append(",\"wedge\":").append(js(wedge));
        if (modules != null && !modules.isEmpty()) {
            sb.append(",\"modules\":[");
            for (int i = 0; i < modules.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(modules.get(i)));
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    static String planStart(BuildPlanView v) {
        return open("buildplan-start")
                .append(",\"plan\":")
                .append(js(v.planName()))
                .append(",\"denominator\":")
                .append(v.denominator())
                .append(",\"tasks\":")
                .append(v.stepsTotal())
                .append('}')
                .toString();
    }

    static String stepStart(String step, String group, int ticks) {
        return open("task-start")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"stage\":")
                .append(js(group))
                .append(",\"ticks\":")
                .append(ticks)
                .append('}')
                .toString();
    }

    static String progress(String step, int delta, BuildPlanView v) {
        return open("progress")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"delta\":")
                .append(delta)
                .append(",\"numerator\":")
                .append(v.numerator())
                .append(",\"denominator\":")
                .append(v.denominator())
                .append('}')
                .toString();
    }

    static String tickUpdate(String step, int delta, BuildPlanView v) {
        return open("tick-update")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"delta\":")
                .append(delta)
                .append(",\"denominator\":")
                .append(v.denominator())
                .append('}')
                .toString();
    }

    static String label(String step, String label) {
        return open("label")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"label\":")
                .append(js(label))
                .append('}')
                .toString();
    }

    static String output(String step, String line) {
        return open("output")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"line\":")
                .append(js(line))
                .append('}')
                .toString();
    }

    static String warn(String step, String code, String msg) {
        return open("warn")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(msg))
                .append('}')
                .toString();
    }

    static String error(String step, String code, String msg) {
        return error(step, code, msg, "", "");
    }

    /**
     * Error event with optional discrete {@code test} / {@code exceptionClass} fields — emitted (in
     * addition to {@code message}) only when non-empty, so a test failure's parts stay separate on
     * the wire without bloating the common diagnostic shape.
     */
    static String error(String step, String code, String msg, String test, String exceptionClass) {
        StringBuilder sb = open("error")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(msg));
        if (test != null && !test.isEmpty()) sb.append(",\"test\":").append(js(test));
        if (exceptionClass != null && !exceptionClass.isEmpty())
            sb.append(",\"exceptionClass\":").append(js(exceptionClass));
        return sb.append('}').toString();
    }

    /**
     * Enriched test-failure error for details.jsonl / --output json: module, engine, class, method,
     * exceptionClass, and a single top-level stack (no nested throwable duplicate —).
     */
    static String error(String step, String code, String msg, cc.jumpkick.run.TestFailureInfo failure) {
        if (failure == null) return error(step, code, msg);
        String message = msg == null || msg.isEmpty() ? failure.message() : msg;
        StringBuilder sb = open("error")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(message));
        if (!failure.module().isEmpty()) sb.append(",\"module\":").append(js(failure.module()));
        if (!failure.engine().isEmpty()) sb.append(",\"engine\":").append(js(failure.engine()));
        if (!failure.className().isEmpty()) sb.append(",\"class\":").append(js(failure.className()));
        if (!failure.method().isEmpty()) sb.append(",\"method\":").append(js(failure.method()));
        if (!failure.exceptionClass().isEmpty())
            sb.append(",\"exceptionClass\":").append(js(failure.exceptionClass()));
        if (failure.worker() > 0) sb.append(",\"worker\":").append(failure.worker());
        if (!failure.file().isEmpty()) sb.append(",\"file\":").append(js(failure.file()));
        if (failure.line() > 0) sb.append(",\"line\":").append(failure.line());
        if (failure.snippetStart() > 0) sb.append(",\"snippetStart\":").append(failure.snippetStart());
        if (!failure.snippet().isEmpty()) {
            sb.append(",\"snippet\":[");
            for (int i = 0; i < failure.snippet().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(js(failure.snippet().get(i)));
            }
            sb.append(']');
        }
        if (!failure.stack().isEmpty()) {
            sb.append(",\"stack\":").append(js(failure.stack()));
        }
        return sb.append('}').toString();
    }

    static String stepFinish(String step, String group, TaskStatus status, Duration duration) {
        return open("task-finish")
                .append(",\"task\":")
                .append(js(step))
                .append(",\"stage\":")
                .append(js(group))
                .append(",\"status\":")
                .append(js(status.name()))
                .append(",\"duration_ms\":")
                .append(duration.toMillis())
                .append('}')
                .toString();
    }

    static String planFinish(BuildPlanResult r) {
        return open("buildplan-finish")
                .append(",\"plan\":")
                .append(js(r.planName()))
                .append(",\"success\":")
                .append(r.success())
                .append(",\"duration_ms\":")
                .append(r.duration().toMillis())
                .append(",\"warnings\":")
                .append(r.warnings().size())
                .append(",\"errors\":")
                .append(r.errors().size())
                .append('}')
                .toString();
    }

    /**
     * Engine workspace aggregate progress. Clients mirror the engine's tracker snapshot;
     * do not recompute from module ticks.
     */
    public static String workspaceProgress(
            String dir, long numerator, long denominator, String phase, int modulesComplete, int modulesTotal) {
        return open("workspace-progress")
                .append(",\"dir\":")
                .append(js(dir == null ? "" : dir))
                .append(",\"numerator\":")
                .append(numerator)
                .append(",\"denominator\":")
                .append(denominator)
                .append(",\"phase\":")
                .append(js(phase == null ? "" : phase))
                .append(",\"modulesComplete\":")
                .append(modulesComplete)
                .append(",\"modulesTotal\":")
                .append(modulesTotal)
                .append('}')
                .toString();
    }

    public static String workspaceStart(int modules) {
        return open("workspace-start")
                .append(",\"modules\":")
                .append(modules)
                .append('}')
                .toString();
    }

    /** Workspace graph finished (all modules done or aborted on graph error). */
    public static String workspaceFinish(boolean success, long durationMs, int modules) {
        return open("workspace-finish")
                .append(",\"success\":")
                .append(success)
                .append(",\"duration_ms\":")
                .append(durationMs)
                .append(",\"modules\":")
                .append(modules)
                .append('}')
                .toString();
    }

    /** A workspace module is about to run its plan (brackets nested step events). */
    public static String moduleStart(String dir, String coord) {
        return open("module-start")
                .append(",\"dir\":")
                .append(js(dir))
                .append(",\"coord\":")
                .append(js(coord))
                .append('}')
                .toString();
    }

    /** A workspace module finished (success or failure). */
    public static String moduleFinish(String dir, String coord, boolean success, long durationMs) {
        return open("module-finish")
                .append(",\"dir\":")
                .append(js(dir))
                .append(",\"coord\":")
                .append(js(coord))
                .append(",\"success\":")
                .append(success)
                .append(",\"duration_ms\":")
                .append(durationMs)
                .append('}')
                .toString();
    }

    /**
     * JSON string escaping — delegates to the shared {@link Jsonl#quote} codec (same escaping the
     * worker wire protocol uses) so there's one implementation to keep correct. A {@code null}
     * encodes as the bare literal {@code null}.
     */
    static String js(String s) {
        return Jsonl.quote(s);
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
