// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * Engine wire vocabulary: JSONL {@code "type"} discriminators. Builders live in {@link ProtoLifecycle},
 * {@link ProtoJobs}, {@link ProtoReads}, {@link ProtoEvents}, and {@link ProtoSession}. Pre-1.0 the
 * codec matches domain events; leftover tokens are deleted, not aliased.
 */
public final class EngineProtocol {

    private EngineProtocol() {}

    public static final String TYPE_FIELD = "type";

    /**
     * Client → server first line on loopback TCP only: shared secret from {@code paths.token}.
     * Never used on the Unix-domain socket (filesystem perms gate access).
     */
    public static final String AUTH = "auth";

    /** Client → server, once per connection: announces the client's jk version. */
    public static final String HELLO = "hello";

    /** Server → client: the engine's own jk version, pid, and start time. */
    public static final String HELLO_ACK = "hello-ack";

    /** Client → server: liveness probe. */
    public static final String PING = "ping";

    /** Server → client: liveness reply. */
    public static final String PONG = "pong";

    /** Client → server: request a status snapshot. */
    public static final String STATUS = "status";

    /** Server → client: the status snapshot. */
    public static final String STATUS_ACK = "status-ack";

    /**
     * Client → server: run (or re-run) host hardware calibration. Optional {@code force},
     * {@code engineColdStartMs} (client-measured cold engine spawn).
     */
    public static final String CALIBRATE_REQUEST = "calibrate-request";

    /** Client → server: pre-train worker AOT caches ({@code jk optimize}). */
    public static final String OPTIMIZE_REQUEST = "optimize-request";

    /** Server → client: optimize finished. */
    public static final String OPTIMIZE_ACK = "optimize-ack";

    /** Server → client: calibration finished ({@code ok}, component timings, summary text). */
    public static final String CALIBRATE_ACK = "calibrate-ack";

    /** Client → server: ask the engine to shut down gracefully. */
    public static final String SHUTDOWN = "shutdown";

    /** Server → client: acknowledges {@link #SHUTDOWN} just before closing the connection. */
    public static final String BYE = "bye";

    /** Client → server: start a workspace build (see {@link #buildRequest}). */
    public static final String BUILD_REQUEST = "build-request";

    /** Client → server, on the same connection as an in-flight {@link #BUILD_REQUEST}: best-effort cancel. */
    public static final String BUILD_CANCEL = "build-cancel";

    /**
     * Client → server (any connection): cancel a live job by {@code jid} (or {@code requestId}
     * alias). Terminal: {@link #CANCEL_ACK}.
     */
    public static final String CANCEL_REQUEST = "cancel-request";

    /** Server → client: outcome of {@link #CANCEL_REQUEST}. */
    public static final String CANCEL_ACK = "cancel-ack";

    /**
     * Server → client: job admitted; carries {@code jid} (and {@code requestId} alias), kind, dir,
     * optional {@code buildNumber}. Clients track this for Ctrl-C / {@code jk cancel}.
     */
    public static final String JOB_START = "job-start";

    /**
     * Server → client: workspace preflight progress ({@code onPreflight}) before the plan burst
     * lock freshen, graph, prepare-module, etc.
     */
    public static final String PREFLIGHT = "preflight";

    /** Server → client: outer InvocationPhase enter/leave ({@code phase}, {@code status}). */
    public static final String INVOCATION_PHASE = "invocation-phase";

    /** Server → client, repeated once per module: {@code onPlan}'s per-module identity/sizing. */
    public static final String PLAN_MODULE = "plan-module";

    /** Server → client, repeated once per (module, step): a plan-module's step list entry. */
    public static final String PLAN_TASK = "plan-task";

    /** Server → client: the plan burst ({@link #PLAN_MODULE}/{@link #PLAN_TASK}) is complete. */
    public static final String PLAN_DONE = "plan-done";

    /** Server → client: {@code onEtaEstimate}. */
    public static final String ETA = "eta";

    /**
     * Server → client: workspace-level aggregate progress. Filterable whole-job %
     * preflight reservation + module weight slices. Fine-grained {@link #PROGRESS} remains
     * module-local.
     */
    public static final String WORKSPACE_PROGRESS = "workspace-progress";

    /** Server → client: a module's plan is about to run — {@code onModuleStart}. */
    public static final String MODULE_START = "module-start";

    /** Server → client: {@code BuildPlanListener.planStart}. */
    public static final String BUILDPLAN_START = "buildplan-start";

    /** Server → client: {@code BuildPlanListener.stepStart}. */
    public static final String TASK_START = "task-start";

    /** Server → client: {@code BuildPlanListener.progress}. */
    public static final String PROGRESS = "progress";

    /** Server → client: {@code BuildPlanListener.tickUpdate}. */
    public static final String TICK_UPDATE = "tick-update";

    /** Server → client: {@code BuildPlanListener.label}. */
    public static final String LABEL = "label";

    /** Server → client: {@code BuildPlanListener.output}. */
    public static final String OUTPUT = "output";

    /** Server → client: {@code BuildPlanListener.warn}. */
    public static final String WARN = "warn";

    /** Server → client: {@code BuildPlanListener.error} — one line of error-stream diagnostics. */
    public static final String ERROR_LINE = "error-line";

    /** Server → client, repeated, immediately before {@link #BUILDPLAN_FINISH}: one of its result's diagnostics. */
    public static final String BUILDPLAN_DIAGNOSTIC = "buildplan-diagnostic";

    /** Server → client: {@code BuildPlanListener.stepFinish}. */
    public static final String TASK_FINISH = "task-finish";

    /** Server → client: {@code BuildPlanListener.planFinish} (success flag only; see {@link #BUILDPLAN_DIAGNOSTIC}). */
    public static final String BUILDPLAN_FINISH = "buildplan-finish";

    /** Server → client: {@code onModuleFinish}. */
    public static final String MODULE_FINISH = "module-finish";

    /** Server → client, terminal: {@code onWorkspaceFinish}. */
    public static final String WORKSPACE_FINISH = "workspace-finish";

    /**
     * Server → client: chrome timeline written under the project's {@code target/} (or override).
     * Path is absolute. Engine owns the file; clients only announce or archive.
     */
    public static final String TIMELINE = "timeline";

    /**
     * Server → client transport/control error envelope ({@code code} + {@code message}). Not the
     * result-payload {@code errors} on finish messages (those are command output).
     */
    public static final String ERROR = "error";

    public static final String ERR_REQUEST_FAILED = "request-failed";
    public static final String ERR_PROTOCOL = "protocol";
    public static final String ERR_VERSION_SKEW = "version-skew";
    /** Engine is draining after {@link #SHUTDOWN}; job refused. */
    public static final String ERR_SHUTTING_DOWN = "shutting-down";

    public static final String ERR_AUTH = "auth";
    /** Engine cancelled a job that exceeded {@code JK_ENGINE_JOB_DEADLINE_MS}. */
    public static final String ERR_DEADLINE = "deadline";
    /**
     * A build/test with the same job fingerprint is already running. Message is human text
     * like {@code Build #27 is already running}; optional {@code buildNumber}/{@code requestId}
     * fields ride alongside when known.
     */
    public static final String ERR_ALREADY_RUNNING = "already-running";

    /**
     * Server → client keep-alive while a long job runs. Resets client stream idle
     * ({@code JK_STREAM_IDLE_MS}); clients ignore the payload. Interval: {@code JK_ENGINE_HEARTBEAT_MS}
     * (default 30s; {@code 0} disables).
     */
    public static final String HEARTBEAT = "heartbeat";

    /** Client → server: single-project test plan ({@code jk test}). */
    public static final String TEST_REQUEST = "test-request";

    /** Client → server: single-project build; same wire shape as {@link #TEST_REQUEST}. */
    public static final String SINGLE_BUILD_REQUEST = "single-build-request";

    /** The {@code dir} tag {@link #TEST_REQUEST}/{@link #SINGLE_BUILD_REQUEST}'s single plan events carry. */
    public static final String SINGLE_PLAN_DIR = "";

    /**
     * Client → server: build forecast ({@code jk explain}). Synchronous inline burst of
     * {@link #EXPLAIN_MODULE}/{@link #EXPLAIN_TASK}/{@link #EXPLAIN_EDGE} then {@link #EXPLAIN_DONE}.
     */
    public static final String EXPLAIN_REQUEST = "explain-request";

    /** Server → client, repeated once per module: {@code TaskForecast.Module}'s identity/sizing. */
    public static final String EXPLAIN_MODULE = "explain-module";

    /** Server → client, repeated once per (module, step): a {@code TaskForecast.Module}'s step list entry. */
    public static final String EXPLAIN_TASK = "explain-task";

    /** Server → client, repeated once per dependency edge. */
    public static final String EXPLAIN_EDGE = "explain-edge";

    /**
     * Client → server: dirty-set forecast without building ({@code force}/{@code rerun} honored).
     * Synchronous; one {@link #FORECAST_ACK}.
     */
    public static final String FORECAST_REQUEST = "forecast-request";

    /** Client → server: parsed-project summary at {@code dir}; one {@link #PROJECT_INFO_ACK}. */
    public static final String PROJECT_INFO_REQUEST = "project-info-request";

    /** Server → client, terminal for {@link #PROJECT_INFO_REQUEST}. */
    public static final String PROJECT_INFO_ACK = "project-info-ack";

    /** Client → server: read-only outdated deps ({@code jk outdated}); one {@link #OUTDATED_ACK}. */
    public static final String OUTDATED_REQUEST = "outdated-request";

    /** Server → client, terminal for {@link #OUTDATED_REQUEST}. */
    public static final String OUTDATED_ACK = "outdated-ack";

    /** Client → server: execution plan ({@link ExecPlan}) for {@code dir}; one {@link #EXEC_PLAN_ACK}. */
    public static final String EXEC_PLAN_REQUEST = "exec-plan-request";

    /** Server → client, terminal for {@link #EXEC_PLAN_REQUEST}. */
    public static final String EXEC_PLAN_ACK = "exec-plan-ack";

    /** Client → server: named {@code jk.toml} edit; one {@link #EDIT_ACK}. */
    public static final String EDIT_REQUEST = "edit-request";

    /** Server → client, terminal for {@link #EDIT_REQUEST}. */
    public static final String EDIT_ACK = "edit-ack";

    /**
     * Client → server: freshen a network-backed catalog ({@code templates}, {@code libraries}, or
     * {@code jdks}) on demand before {@code jk new}/{@code init} (templates), {@code jk lock}/{@code
     * update} (libraries), or JDK install/update (jdks) reads the local cache. Every fetch is
     * engine-hosted and always attempted (no client-side TTL to go stale across a per-invocation
     * process). {@code url}/{@code cacheFile} override the default source/destination ({@code
     * libraries}/{@code jdks} only, for tests and power users); one {@link #FRESHEN_CATALOG_ACK}.
     *
     * <p>JDK installs themselves ride one shared path everywhere: {@code JdkService.install}
     * fetches the catalog and installs (CLI {@code jk jdk install} and MCP {@code jk_jdk} both
     * call it). This request is the engine-hosted <em>catalog refresh</em> channel; the CLI only
     * sends it when an engine already answers — the engine is a JVM process that needs a JDK to
     * run, so this cannot be the sole path to provisioning the first JDK on a bare machine.
     */
    public static final String FRESHEN_CATALOG_REQUEST = "freshen-catalog-request";

    /** Server → client, terminal for {@link #FRESHEN_CATALOG_REQUEST}. */
    public static final String FRESHEN_CATALOG_ACK = "freshen-catalog-ack";

    /**
     * Client → server: read the layered library catalog ({@code jk library list}/{@code search},
     * wizard picker). Engine walks {@code LibraryCatalog} + cached artifact versions; one {@link
     * #CATALOG_READ_ACK}.
     */
    public static final String CATALOG_READ_REQUEST = "catalog-read-request";

    /** Server → client, terminal for {@link #CATALOG_READ_REQUEST}. */
    public static final String CATALOG_READ_ACK = "catalog-read-ack";

    /**
     * Client → server: evaluate {@code [deny]} against the lock ({@code jk deny}). Engine-hosted so
     * policy is never client-parsed; one {@link #DENY_CHECK_ACK}.
     */
    public static final String DENY_CHECK_REQUEST = "deny-check-request";

    /** Server → client, terminal for {@link #DENY_CHECK_REQUEST}. */
    public static final String DENY_CHECK_ACK = "deny-check-ack";

    /** Client → server: dependency tree with marker tags; one {@link #TREE_ACK}. */
    public static final String TREE_REQUEST = "tree-request";

    /** Server → client, terminal for {@link #TREE_REQUEST}. */
    public static final String TREE_ACK = "tree-ack";

    /** Client → server: why a module is in the graph; one {@link #WHY_ACK}. */
    public static final String WHY_REQUEST = "why-request";

    /** Server → client, terminal for {@link #WHY_REQUEST}. */
    public static final String WHY_ACK = "why-ack";

    /** Client → server: full-model generator payloads; one {@link #GENERATE_ACK}. */
    public static final String GENERATE_REQUEST = "generate-request";

    /** Server → client, terminal for {@link #GENERATE_REQUEST}. */
    public static final String GENERATE_ACK = "generate-ack";

    /** Client → server: scaffold a project ({@code jk new} / HTTP / MCP). */
    public static final String NEW_PROJECT_REQUEST = "new-project-request";

    /** Server → client, terminal for {@link #NEW_PROJECT_REQUEST}. */
    public static final String NEW_PROJECT_ACK = "new-project-ack";

    /** Client → server: {@code jk plugin install-local}. */
    public static final String PLUGIN_INSTALL_LOCAL_REQUEST = "plugin-install-local-request";

    /** Server → client, terminal for {@link #PLUGIN_INSTALL_LOCAL_REQUEST}. */
    public static final String PLUGIN_INSTALL_LOCAL_ACK = "plugin-install-local-ack";

    /** Client → server: plugin-declared command; one {@link #PLUGIN_VERB_ACK}. */
    public static final String PLUGIN_VERB_REQUEST = "plugin-command-request";

    /** Server → client, terminal for {@link #PLUGIN_VERB_REQUEST}. */
    public static final String PLUGIN_VERB_ACK = "plugin-command-ack";

    /** Client → server: IDE-agnostic workspace model ({@link IdeWireModel}); one {@link #IDE_MODEL_ACK}. */
    public static final String IDE_MODEL_REQUEST = "ide-model-request";

    /** Server → client, terminal for {@link #IDE_MODEL_REQUEST}. */
    public static final String IDE_MODEL_ACK = "ide-model-ack";

    /**
     * Server → client forecast: {@code dirtyDirs}, {@code lockStale}, {@code empty}, {@code errors}
     * (non-empty errors ⇒ no forecast).
     */
    public static final String FORECAST_ACK = "forecast-ack";

    /** Server → client, terminal: the explain burst is complete. */
    public static final String EXPLAIN_DONE = "explain-done";

    /** Client → server: write {@code jk-lock.toml} ({@code jk lock}). Terminal: {@link #LOCK_FINISH}. */
    public static final String LOCK_REQUEST = "lock-request";

    /** Client → server: re-resolve {@code jk-lock.toml} ({@code jk update}); same events as lock. */
    public static final String UPDATE_REQUEST = "update-request";

    /** Client → server: align CAS + toolchain with {@code jk-lock.toml} ({@code jk sync}). */
    public static final String SYNC_REQUEST = "sync-request";

    /** Server → client, repeated: opens one module's event scope in a lock/update cascade. */
    public static final String LOCK_MODULE = "lock-module";

    /** Server → client, repeated: one package was resolved and recorded ({@code ResolveObserver.onPackage}). */
    public static final String LOCK_PACKAGE = "lock-package";

    /** Server → client, terminal for {@link #LOCK_REQUEST}/{@link #UPDATE_REQUEST}: cascade outcome. */
    public static final String LOCK_FINISH = "lock-finish";

    // ---- hosted worker commands (single-plan shape; structured results as repeated messages) ----

    /** Client → server: OSV scan ({@code jk audit}). Findings stream as {@link #AUDIT_FINDING}. */
    public static final String AUDIT_REQUEST = "audit-request";

    /** Server → client, repeated: one OSV finding, streamed as the audit worker reports it. */
    public static final String AUDIT_FINDING = "audit-finding";

    /** Client → server: format sources ({@code jk format}); per-file {@link #FORMAT_FILE}. */
    public static final String FORMAT_REQUEST = "format-request";

    /** Server → client, repeated: one file's format result ({@code changed}/{@code clean}/{@code error}). */
    public static final String FORMAT_FILE = "format-file";

    /** Client → server: publish artifacts ({@code jk publish}); credentials ride the request. */
    public static final String PUBLISH_REQUEST = "publish-request";

    /** Client → server: build an OCI image ({@code jk image}). */
    public static final String IMAGE_REQUEST = "image-request";

    /** Client → server: Maven/Gradle → {@code jk.toml} ({@code jk import}). */
    public static final String IMPORT_REQUEST = "import-request";

    /** Server → client, repeated: one import progress note ({@code kind} = {@code wrote}/{@code note}). */
    public static final String IMPORT_NOTE = "import-note";

    /** Client → server: provision Maven/Gradle ({@code jk mvn}/{@code jk gradle}). */
    public static final String PROVISION_REQUEST = "provision-request";

    /** Server → client, terminal for {@link #PROVISION_REQUEST}: the provisioned tool's bin path. */
    public static final String PROVISION_RESULT = "provision-result";

    // ---- hosted plan commands ------------------------------------------------------------------

    /** Client → server: type-check ({@code jk compile}); single-plan shape. */
    public static final String COMPILE_REQUEST = "compile-request";

    /** Client → server: native-image ({@code jk native}); Graal homes resolved client-side. */
    public static final String NATIVE_REQUEST = "native-request";

    /**
     * Client → server: observe a full-app run under the tracing agent ({@code jk train}); Graal home
     * resolved client-side when available.
     */
    public static final String TRAIN_REQUEST = "train-request";

    /** Client → server: build + cache install ({@code jk install}); launcher write is client-side. */
    public static final String INSTALL_REQUEST = "install-request";

    /** Client → server: materialize a git checkout for {@code jk install <git-url>}. */
    public static final String GIT_FETCH_REQUEST = "git-fetch-request";

    // ---- hosted long-tail commands -----------------------------------------------------------------

    /** Client → server: resolve a Maven CLI tool ({@code jk tool install|run}). */
    public static final String TOOL_RESOLVE_REQUEST = "tool-resolve-request";

    /**
     * Client → server: cache maintenance ({@code prune}/{@code purge}/{@code gc}) at an idle
     * boundary under {@code.prune.lock}; may emit {@link #PRUNE_WAIT} first.
     */
    public static final String CACHE_PRUNE_REQUEST = "cache-prune-request";

    /**
     * Client → server: prepare a script/jar for {@code jk tool run} (header parse, deps, compile);
     * client keeps the exec that owns the terminal.
     */
    public static final String SCRIPT_PREPARE_REQUEST = "script-prepare-request";

    /**
     * Server → client, before the plan burst of a {@link #CACHE_PRUNE_REQUEST}: the operation is
     * queued behind in-flight work — {@code plans} in-engine plans ({@code 0} with {@code
     * external=true} means another process's prune holds {@code.prune.lock}).
     */
    public static final String PRUNE_WAIT = "prune-wait";

    // ---- build-history journal ({@code jk history}) --------------------------------
    // The engine owns the journal on disk; the thin CLI reaches it only over these RPCs. Responses
    // are flat JSONL lines (one object per line, scalar fields) so the CLI renders them with the
    // Jsonl helpers it already has — it never parses nested JSON.

    /** Client → server: list journal entries (newest first, up to {@code limit}). */
    public static final String HISTORY_LIST_REQUEST = "history-list-request";

    /** Client → server: the full detail of one entry by {@code id}. */
    public static final String HISTORY_SHOW_REQUEST = "history-show-request";

    /** Client → server: delete one entry by {@code id}. */
    public static final String HISTORY_DELETE_REQUEST = "history-delete-request";

    /** Server → client, repeated for {@code HISTORY_LIST}: one entry's flat summary. */
    public static final String HISTORY_ENTRY = "history-entry";

    /** Server → client, once for {@code HISTORY_SHOW}: the entry's flat scalar header. */
    public static final String HISTORY_RECORD = "history-record";

    /** Server → client, repeated for {@code HISTORY_SHOW}: one module row. */
    public static final String HISTORY_MODULE = "history-module";

    /** Server → client, repeated for {@code HISTORY_SHOW}: one step row. */
    public static final String HISTORY_TASK = "history-task";

    /** Server → client, repeated for {@code HISTORY_SHOW}: one diagnostic row. */
    public static final String HISTORY_DIAG = "history-diag";

    /** Server → client: terminal for a list/show stream ({@code count} entries/rows emitted). */
    public static final String HISTORY_DONE = "history-done";

    /** Server → client: terminal for a delete ({@code deleted} true/false). */
    public static final String HISTORY_DELETED = "history-deleted";

    // ---- running build metrics ({@code jk status}) ----------------------------------
    // Aggregates (count/total/min/max/avg per outcome) the engine folds at every build/test finish:
    // global, per project, per step, and per project per step — same flat-JSONL discipline as
    // the history RPCs.

    /** Client → server: stream aggregate rows; optional {@code dir} filters project-tier rows. */
    public static final String METRICS_REQUEST = "metrics-request";

    /** Server → client, repeated: one aggregate row ({@code scope} names its tier). */
    public static final String METRICS_ENTRY = "metrics-entry";

    /** Server → client: terminal for a metrics stream ({@code count} rows emitted). */
    public static final String METRICS_DONE = "metrics-done";

    /** Frozen handshake version. Add fields freely; never rename/retype/remove. */
    public static final int PROTOCOL = 1;

    /** The {@code "type"} discriminator of a decoded message, or {@code null} if absent/malformed. */
    public static String typeOf(String json) {
        return ProtoLifecycle.typeOf(json);
    }

    static String quoteArray(List<String> values) {
        return Jsonl.array(values);
    }
}
