// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Every event jk streams, and what each surface does with it: the engine wire to the CLI, the
 * CLI's console JSONL ({@code -O jsonl}), SSE to the web dashboard, and MCP to agents. A surface
 * either carries an event — under its own name on that surface, with how it samples it — or omits
 * it with the reason written here. Nothing is left out by an empty case: the SSE sink asks this
 * table, and the catalog tests drive each surface's real encoder and fail when it disagrees with
 * its row, or when an event the engine can stream has no row.
 *
 * <p>MCP subscribers receive every SSE frame but the dashboard's own ({@link #DASHBOARD_ONLY}: its
 * vitals and the session log's {@code session}, {@code episode} and {@code action}),
 * as {@code notifications/jk/event}; the CLI's MCP bridge folds their {@code progress}, {@code
 * task-start} and {@code label} into {@code notifications/progress}. So MCP's column follows SSE's
 * and is not written per row.
 *
 * <p>Request and reply traffic — a request, its ack, the rows of a reply, an error code — is not an
 * event and has no row; {@link #MESSAGES} names each such {@link EngineProtocol} token so that a
 * new token must be placed on one side or the other.
 */
public final class EventCatalog {

    private EventCatalog() {}

    /** A surface an event can reach. */
    public enum Surface {
        WIRE,
        CONSOLE_JSONL,
        SSE,
        MCP
    }

    /** What a surface does with an event. */
    public sealed interface Verdict permits Carried, Omitted {}

    /**
     * Carried, as {@code as} on that surface (the record or frame type it is written under), with
     * how it is sampled or routed. {@code fromEventSink} is for SSE alone: the frame is published
     * by the engine's event sink ({@code SseEventSink}) from the engine event, rather than by the
     * code named in {@code how}.
     */
    public record Carried(String as, String how, boolean fromEventSink) implements Verdict {
        public Carried {
            Objects.requireNonNull(as, "as");
            Objects.requireNonNull(how, "how");
        }

        /** Carried under {@code as}, every event, not through the SSE event sink. */
        public static Carried as(String as, String how) {
            return new Carried(as, how, false);
        }

        /** Carried on SSE under {@code as}, published by the event sink. */
        public static Carried bySink(String as, String how) {
            return new Carried(as, how, true);
        }
    }

    /** Omitted, for {@code reason}. */
    public record Omitted(String reason) implements Verdict {
        public Omitted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One event and its verdict on each written surface (MCP follows SSE). */
    public record Row(String type, Verdict wire, Verdict consoleJsonl, Verdict sse) {

        /** The verdict on {@code surface}; MCP's is SSE's, but for the dashboard's own frames. */
        public Verdict on(Surface surface) {
            return switch (surface) {
                case WIRE -> wire;
                case CONSOLE_JSONL -> consoleJsonl;
                case SSE -> sse;
                case MCP ->
                    DASHBOARD_ONLY.contains(type)
                            ? new Omitted("the dashboard's own frame, published to dashboard subscribers alone: an"
                                    + " agent asks for its own session's facts, it is not streamed them")
                            : sse;
            };
        }
    }

    // SSE frame names that are not wire types: the job lifecycle under the dashboard's names, the
    // plan's weight, and the dashboard's own vitals.
    public static final String SSE_REQUEST_QUEUED = "request-queued";
    public static final String SSE_REQUEST_START = "request-start";
    public static final String SSE_REQUEST_FINISH = "request-finish";
    public static final String SSE_PLAN = "plan";
    public static final String SSE_STATUS = "status";
    public static final String SSE_CACHE = "cache";
    public static final String SSE_SESSION = "session";
    public static final String SSE_EPISODE = "episode";
    public static final String SSE_ACTION = "action";
    public static final String SSE_DECISION = "decision";
    public static final String SSE_PRESENCE = "presence";
    public static final String SSE_NOTIFY = "notify";
    public static final String SSE_NOTICE = "notice";

    /** SSE frames published to dashboard subscribers alone ({@code publishDashboard}): not sent to MCP. */
    public static final Set<String> DASHBOARD_ONLY = Set.of(
            SSE_STATUS,
            SSE_CACHE,
            SSE_SESSION,
            SSE_EPISODE,
            SSE_ACTION,
            SSE_DECISION,
            SSE_PRESENCE,
            SSE_NOTIFY,
            SSE_NOTICE);

    // Console JSONL record names that are not wire types.
    public static final String JSONL_WORKSPACE_START = "workspace-start";
    public static final String JSONL_JOB = "job";
    public static final String JSONL_ERROR = "error";

    private static final String WIRE = "every event, as the engine sends it";

    private static Carried wire() {
        return Carried.as("", WIRE);
    }

    private static Omitted verb(String view) {
        return new Omitted("a " + view + " stream's event: that command's own view renders it, and -O jsonl prints the"
                + " command's result rather than its steps");
    }

    private static final Omitted VERB_NOT_SSE =
            new Omitted("a single command's progress, not a build's: the dashboard shows the job's request-start and"
                    + " request-finish for it");

    private static final Map<String, Row> ROWS = rows(List.of(
            // ---- the job's lifecycle ----------------------------------------------------------------
            new Row(
                    EngineProtocol.JOB_QUEUED,
                    wire(),
                    new Omitted(
                            "the CLI writes a waiting line to stderr; -O jsonl writes no record until the job starts"),
                    Carried.as(SSE_REQUEST_QUEUED, "every queued job")),
            new Row(
                    EngineProtocol.JOB_START,
                    wire(),
                    Carried.as(
                            JSONL_JOB,
                            "once per job, before workspace-start: its jid, build number and details.jsonl path"),
                    Carried.as(SSE_REQUEST_START, "once, when the job starts")),
            new Row(
                    EngineProtocol.JOB_FINISH,
                    wire(),
                    new Omitted("the stream's end on the socket; workspace-finish is the record that ends -O jsonl"),
                    Carried.as(SSE_REQUEST_FINISH, "once, when the job settles")),
            new Row(
                    EngineProtocol.HEARTBEAT,
                    wire(),
                    new Omitted("liveness for the socket the CLI reads; nothing to record"),
                    new Omitted("SSE keeps its own connection alive")),
            new Row(
                    EngineProtocol.ERROR,
                    wire(),
                    new Omitted("its message is the errors of the workspace-finish that ends -O jsonl, success false"),
                    Carried.as(EngineProtocol.ERROR, "the request's failure (publishRequestError)")),
            // ---- the run --------------------------------------------------------------------------
            new Row(
                    EngineProtocol.PREFLIGHT,
                    wire(),
                    Carried.as(EngineProtocol.PREFLIGHT, "each stage's verdict; its label counts the dirty modules"),
                    new Omitted("the dashboard draws the run from workspace-progress, whose phase names the stage")),
            new Row(
                    EngineProtocol.DOWNLOADS,
                    wire(),
                    Carried.as(EngineProtocol.DOWNLOADS, "a record when a burst starts, every 10 s, and when it ends"),
                    Carried.as(EngineProtocol.DOWNLOADS, "sampled every 125 ms, on change (JobEnvelope)")),
            new Row(
                    EngineProtocol.INVOCATION_PHASE,
                    wire(),
                    new Omitted("the request's coarse phase; workspace-progress's phase says the same"),
                    new Omitted("the request's coarse phase; workspace-progress's phase says the same")),
            new Row(
                    EngineProtocol.NOTE,
                    wire(),
                    Carried.as(EngineProtocol.NOTE, "every note"),
                    new Omitted("a run-level aside for the person at the terminal; run notices reach SSE as warn")),
            new Row(
                    EngineProtocol.PLAN_MODULE,
                    wire(),
                    new Omitted("folded into the module plan the CLI draws; workspace-start carries the counts"),
                    new Omitted("the dashboard learns modules as they start (module-start)")),
            new Row(
                    EngineProtocol.PLAN_TASK,
                    wire(),
                    new Omitted("folded into the module plan the CLI draws"),
                    new Omitted("the dashboard learns tasks as they start (task-start)")),
            new Row(
                    EngineProtocol.PLAN_DONE,
                    wire(),
                    Carried.as(JSONL_WORKSPACE_START, "once, when the plan is known"),
                    new Omitted("the plan's weight reaches the dashboard as plan")),
            new Row(
                    EngineProtocol.ETA,
                    wire(),
                    new Omitted("drives the terminal's countdown; workspace-progress carries remainingMs"),
                    Carried.bySink(EngineProtocol.ETA, "every estimate")),
            new Row(
                    EngineProtocol.WORKSPACE_PROGRESS,
                    wire(),
                    Carried.as(EngineProtocol.WORKSPACE_PROGRESS, "every change"),
                    Carried.as(EngineProtocol.WORKSPACE_PROGRESS, "every change (SsePublisher.emitWorkspaceProgress)")),
            new Row(
                    EngineProtocol.MODULE_START,
                    wire(),
                    Carried.as(EngineProtocol.MODULE_START, "every module"),
                    Carried.bySink(EngineProtocol.MODULE_START, "every module")),
            new Row(
                    EngineProtocol.MODULE_FINISH,
                    wire(),
                    Carried.as(EngineProtocol.MODULE_FINISH, "every module"),
                    Carried.bySink(EngineProtocol.MODULE_FINISH, "every module")),
            new Row(
                    EngineProtocol.WORKSPACE_FINISH,
                    wire(),
                    Carried.as(EngineProtocol.WORKSPACE_FINISH, "once, last"),
                    new Omitted("the job's request-finish is the dashboard's terminal")),
            new Row(
                    EngineProtocol.TIMELINE,
                    wire(),
                    new Omitted("names the profile file the build wrote; not an event a reader acts on"),
                    new Omitted("names the profile file the build wrote; not an event a reader acts on")),
            // ---- a module's plan ------------------------------------------------------------------
            new Row(
                    EngineProtocol.BUILDPLAN_START,
                    wire(),
                    Carried.as(EngineProtocol.BUILDPLAN_START, "once per plan"),
                    Carried.bySink(EngineProtocol.PROGRESS, "as the plan's first progress")),
            new Row(
                    EngineProtocol.TASK_START,
                    wire(),
                    Carried.as(EngineProtocol.TASK_START, "every task"),
                    Carried.bySink(EngineProtocol.TASK_START, "every task")),
            new Row(
                    EngineProtocol.PROGRESS,
                    wire(),
                    Carried.as(EngineProtocol.PROGRESS, "every tick"),
                    Carried.bySink(EngineProtocol.PROGRESS, "every tick")),
            new Row(
                    EngineProtocol.TICK_UPDATE,
                    wire(),
                    Carried.as(EngineProtocol.TICK_UPDATE, "every change of a task's ticks"),
                    Carried.bySink(EngineProtocol.PROGRESS, "as progress")),
            new Row(
                    EngineProtocol.LABEL,
                    wire(),
                    Carried.as(EngineProtocol.LABEL, "every label"),
                    Carried.bySink(EngineProtocol.LABEL, "every label, redacted")),
            new Row(
                    EngineProtocol.OUTPUT,
                    wire(),
                    Carried.as(EngineProtocol.OUTPUT, "every line"),
                    Carried.bySink(EngineProtocol.OUTPUT, "every line, redacted")),
            new Row(
                    EngineProtocol.WARN,
                    wire(),
                    Carried.as(EngineProtocol.WARN, "every warning"),
                    Carried.as(
                            EngineProtocol.WARN,
                            "run notices only (JobEnvelope.publishNotice); a step's warnings stay" + " in its log")),
            new Row(
                    EngineProtocol.ERROR_LINE,
                    wire(),
                    Carried.as(JSONL_ERROR, "every error line or test failure"),
                    Carried.as(EngineProtocol.ERROR, "the plan's diagnostics at its end, capped (publishDiagnostics)")),
            new Row(
                    EngineProtocol.BUILDPLAN_DIAGNOSTIC,
                    wire(),
                    new Omitted("collected into the plan's result; its errors are written as error records"),
                    Carried.as(EngineProtocol.ERROR, "the plan's diagnostics at its end, capped (publishDiagnostics)")),
            new Row(
                    EngineProtocol.CLASS_FINISH,
                    wire(),
                    Carried.as(EngineProtocol.CLASS_FINISH, "every test class"),
                    new Omitted("per-class test detail; failures reach the dashboard as error frames")),
            new Row(
                    EngineProtocol.TASK_FINISH,
                    wire(),
                    Carried.as(EngineProtocol.TASK_FINISH, "every task"),
                    Carried.bySink(EngineProtocol.TASK_FINISH, "every task")),
            new Row(
                    EngineProtocol.BUILDPLAN_FINISH,
                    wire(),
                    Carried.as(EngineProtocol.BUILDPLAN_FINISH, "once per plan"),
                    Carried.bySink(EngineProtocol.BUILDPLAN_FINISH, "once per plan")),
            // ---- a single command's stream ------------------------------------------------------------
            new Row(EngineProtocol.LOCK_MODULE, wire(), verb("jk lock"), VERB_NOT_SSE),
            new Row(EngineProtocol.LOCK_PACKAGE, wire(), verb("jk lock"), VERB_NOT_SSE),
            new Row(EngineProtocol.LOCK_PHASE, wire(), verb("jk lock"), VERB_NOT_SSE),
            new Row(EngineProtocol.LOCK_FINISH, wire(), verb("jk lock"), VERB_NOT_SSE),
            new Row(EngineProtocol.UPDATE_REWRITE, wire(), verb("jk update"), VERB_NOT_SSE),
            new Row(EngineProtocol.UPDATE_CHANGE, wire(), verb("jk update"), VERB_NOT_SSE),
            new Row(EngineProtocol.AUDIT_FINDING, wire(), verb("jk audit"), VERB_NOT_SSE),
            new Row(EngineProtocol.FORMAT_FILE, wire(), verb("jk format"), VERB_NOT_SSE),
            new Row(EngineProtocol.IMPORT_NOTE, wire(), verb("jk import"), VERB_NOT_SSE),
            new Row(EngineProtocol.PROVISION_PROGRESS, wire(), verb("tool provisioning"), VERB_NOT_SSE),
            new Row(EngineProtocol.PROVISION_RESULT, wire(), verb("tool provisioning"), VERB_NOT_SSE),
            new Row(EngineProtocol.MVN_RESULTS_RESULT, wire(), verb("jk mvn results"), VERB_NOT_SSE),
            new Row(EngineProtocol.OUTDATED_PROGRESS, wire(), verb("jk outdated"), VERB_NOT_SSE),
            new Row(EngineProtocol.PRUNE_WAIT, wire(), verb("jk cache"), VERB_NOT_SSE),
            new Row(EngineProtocol.EXPLAIN_MODULE, wire(), verb("jk explain"), VERB_NOT_SSE),
            new Row(EngineProtocol.EXPLAIN_TASK, wire(), verb("jk explain"), VERB_NOT_SSE),
            new Row(EngineProtocol.EXPLAIN_EDGE, wire(), verb("jk explain"), VERB_NOT_SSE),
            new Row(EngineProtocol.EXPLAIN_DONE, wire(), verb("jk explain"), VERB_NOT_SSE),
            new Row(EngineProtocol.GRAPH_PROGRESS, wire(), verb("jk graph"), VERB_NOT_SSE),
            // ---- events one surface has alone ---------------------------------------------------------
            new Row(
                    SSE_PLAN,
                    new Omitted("the dashboard's plan weight; the CLI learns the plan from plan-module and plan-done"),
                    new Omitted("the dashboard's plan weight; workspace-start carries the counts"),
                    Carried.bySink(SSE_PLAN, "once per run")),
            new Row(
                    SSE_STATUS,
                    new Omitted("the dashboard's engine vitals; the CLI asks for status"),
                    new Omitted("the dashboard's engine vitals"),
                    Carried.as(SSE_STATUS, "dashboard only, sampled while a dashboard is attached (LiveVitals)")),
            new Row(
                    SSE_CACHE,
                    new Omitted("the dashboard's cache vitals"),
                    new Omitted("the dashboard's cache vitals"),
                    Carried.as(SSE_CACHE, "dashboard only, after a job settles (LiveVitals)")),
            new Row(
                    SSE_SESSION,
                    new Omitted("the engine's session log; the CLI is one of its sessions, not a reader of them"),
                    new Omitted("the engine's session log; -O jsonl prints one command's run"),
                    Carried.as(
                            SSE_SESSION,
                            "dashboard only: started, idle, live again, ended, current episode changed"
                                    + " (SessionLog)")),
            new Row(
                    SSE_EPISODE,
                    new Omitted("the engine's session log; the CLI is one of its sessions, not a reader of them"),
                    new Omitted("the engine's session log; -O jsonl prints one command's run"),
                    Carried.as(SSE_EPISODE, "dashboard only: started, title changed, outcome changed (SessionLog)")),
            new Row(
                    SSE_ACTION,
                    new Omitted("the engine's session log; the CLI is one of its sessions, not a reader of them"),
                    new Omitted("the engine's session log; -O jsonl prints one command's run"),
                    Carried.as(
                            SSE_ACTION,
                            "dashboard only: one non-run MCP action, in its compact display form" + " (SessionLog)")),
            new Row(
                    SSE_DECISION,
                    new Omitted("the engine's decision queue; the CLI reads it with jk decide"),
                    new Omitted("the engine's decision queue; -O jsonl prints one command's run"),
                    Carried.as(SSE_DECISION, "dashboard only: a decision opened or settled (DecisionQueue)")),
            new Row(
                    SSE_PRESENCE,
                    new Omitted("the dashboard's own stream: its id and the ping each tab answers with its state"),
                    new Omitted("the dashboard's own stream"),
                    Carried.as(SSE_PRESENCE, "dashboard only, to one stream: on connect and every 15s (Dashboards)")),
            new Row(
                    SSE_NOTIFY,
                    new Omitted("a browser notification; the CLI's are the engine's native ones"),
                    new Omitted("a browser notification"),
                    Carried.as(SSE_NOTIFY, "dashboard only, to the one tab chosen to notify (Dashboards)")),
            new Row(
                    SSE_NOTICE,
                    new Omitted("the bell's notices and its seen marker; the CLI has no bell"),
                    new Omitted("the bell's notices"),
                    Carried.as(SSE_NOTICE, "dashboard only: a notice added or the seen marker moved (Notices)")),
            new Row(
                    EngineProtocol.GUARD_EVENT,
                    new Omitted("written by the CLI from the guard's result"),
                    Carried.as(EngineProtocol.GUARD_EVENT, "once per guard run"),
                    new Omitted("the guard's verdict reaches the dashboard as the job's request-finish")),
            new Row(
                    EngineProtocol.SLOWEST_TESTS,
                    new Omitted("written by the CLI from the run's test results"),
                    Carried.as(EngineProtocol.SLOWEST_TESTS, "once, after the tests"),
                    new Omitted("the dashboard reads test timings from the run's history"))));

    /**
     * Every {@link EngineProtocol} token that is not an event: requests, replies and the rows of a
     * reply, error codes, field names and rows the CLI prints on its own. A new token belongs here
     * or in a row.
     */
    public static final Map<String, String> MESSAGES = messages();

    private static Map<String, Row> rows(List<Row> rows) {
        Map<String, Row> out = new LinkedHashMap<>();
        for (Row row : rows) {
            if (out.put(row.type(), row) != null) throw new IllegalStateException("two rows for " + row.type());
        }
        return out;
    }

    private static Map<String, String> messages() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String field :
                List.of(EngineProtocol.TYPE_FIELD, EngineProtocol.TEST_CLASS_FIELD, EngineProtocol.SINGLE_PLAN_DIR)) {
            out.put(field, "a field name or value inside a message, not a message type");
        }
        for (String code : List.of(
                EngineProtocol.ERR_REQUEST_FAILED,
                EngineProtocol.ERR_PROTOCOL,
                EngineProtocol.ERR_VERSION_SKEW,
                EngineProtocol.ERR_SHUTTING_DOWN,
                EngineProtocol.ERR_DEADLINE,
                EngineProtocol.ERR_QUEUE_WAIT,
                EngineProtocol.ERR_ENGINE_HEAP,
                EngineProtocol.ERR_DISK,
                EngineProtocol.ERR_ALREADY_RUNNING)) {
            out.put(code, "an error code inside an error frame");
        }
        for (String request : List.of(
                EngineProtocol.AUTH,
                EngineProtocol.HELLO,
                EngineProtocol.PING,
                EngineProtocol.STATUS,
                EngineProtocol.SHUTDOWN,
                EngineProtocol.STOP_SESSIONS)) {
            out.put(request, "client to engine: a request of the connection");
        }
        for (String reply : List.of(
                EngineProtocol.HELLO_ACK,
                EngineProtocol.PONG,
                EngineProtocol.STATUS_ACK,
                EngineProtocol.BYE,
                EngineProtocol.DRAIN_STATUS,
                EngineProtocol.DRAIN_DONE,
                EngineProtocol.SESSIONS_STOPPED)) {
            out.put(reply, "engine to client: the reply to a connection request");
        }
        for (String row : List.of(
                EngineProtocol.HISTORY_ENTRY,
                EngineProtocol.HISTORY_RECORD,
                EngineProtocol.HISTORY_MODULE,
                EngineProtocol.HISTORY_TASK,
                EngineProtocol.HISTORY_DIAG,
                EngineProtocol.HISTORY_DONE,
                EngineProtocol.HISTORY_DELETED,
                EngineProtocol.METRICS_ENTRY,
                EngineProtocol.METRICS_DONE)) {
            out.put(row, "a row of a history or metrics reply");
        }
        for (String node : List.of(
                EngineProtocol.NODE_LIST,
                EngineProtocol.NODE_REMOTE,
                EngineProtocol.NODE_INSTALLED,
                EngineProtocol.NODE_UNINSTALLED,
                EngineProtocol.NODE_WHICH,
                EngineProtocol.NODE_PINNED,
                EngineProtocol.NODE_VERIFY,
                EngineProtocol.NODE_UPDATE)) {
            out.put(node, "a row jk node prints for -O jsonl, written by the CLI");
        }
        return out;
    }

    /** Every row, in catalog order. */
    public static List<Row> rows() {
        return List.copyOf(ROWS.values());
    }

    /** The row for {@code type}, or null when it is no event. */
    public static @Nullable Row row(String type) {
        return ROWS.get(type);
    }

    /** True when {@code surface} carries {@code type}; a type with no row is carried nowhere. */
    public static boolean carries(Surface surface, String type) {
        Row row = ROWS.get(type);
        return row != null && row.on(surface) instanceof Carried;
    }

    /**
     * True for a type every request/reply token is: ends in {@code -request} or {@code -ack}, or is
     * listed in {@link #MESSAGES}.
     */
    public static boolean isMessage(String token) {
        return token.endsWith("-request") || token.endsWith("-ack") || MESSAGES.containsKey(token);
    }
}
