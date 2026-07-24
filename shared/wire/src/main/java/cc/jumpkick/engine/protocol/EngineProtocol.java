// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;

/**
 * Engine wire vocabulary: JSONL with a {@code "type"} discriminator (same name as CLI JSONL / SSE /
 * MCP). Same-version client only (unversioned). Variable-length collections are repeated typed
 * messages plus a terminal marker — never nested object arrays.
 */
public final class EngineProtocol {

    private EngineProtocol() {}

    public static final String TYPE_FIELD = "type";

    /**
     * Client → server first line on loopback TCP only: shared secret from {@code paths.token()}.
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

    /** Client → server: ask the engine to shut down gracefully. */
    public static final String SHUTDOWN = "shutdown";

    /** Server → client: acknowledges {@link #SHUTDOWN} just before closing the connection. */
    public static final String BYE = "bye";

    /** Client → server: start a workspace build (see {@link #buildRequest}). */
    public static final String BUILD_REQUEST = "build-request";

    /** Client → server, on the same connection as an in-flight {@link #BUILD_REQUEST}: best-effort cancel. */
    public static final String BUILD_CANCEL = "build-cancel";

    /**
     * Server → client: workspace preflight progress ({@code onPreflight}) before the plan burst —
     * lock freshen, graph, prepare-module, etc.
     */
    public static final String PREFLIGHT = "preflight";

    /** Server → client, repeated once per module: {@code onPlan}'s per-module identity/sizing. */
    public static final String PLAN_MODULE = "plan-module";

    /** Server → client, repeated once per (module, step): a plan-module's step list entry. */
    public static final String PLAN_STEP = "plan-step";

    /** Server → client: the plan burst ({@link #PLAN_MODULE}/{@link #PLAN_STEP}) is complete. */
    public static final String PLAN_DONE = "plan-done";

    /** Server → client: {@code onEtaEstimate}. */
    public static final String ETA = "eta";

    /**
     * Server → client: workspace-level aggregate progress (JK-1120). Filterable whole-job % —
     * preflight reservation + module weight slices. Fine-grained {@link #PROGRESS} remains
     * module-local.
     */
    public static final String WORKSPACE_PROGRESS = "workspace-progress";

    /** Server → client: a module's pipeline is about to run — {@code onModuleStart}. */
    public static final String MODULE_START = "module-start";

    /** Server → client: {@code PipelineListener.pipelineStart}. */
    public static final String PIPELINE_START = "pipeline-start";

    /** Server → client: {@code PipelineListener.stepStart}. */
    public static final String STEP_START = "step-start";

    /** Server → client: {@code PipelineListener.progress}. */
    public static final String PROGRESS = "progress";

    /** Server → client: {@code PipelineListener.tickUpdate}. */
    public static final String TICK_UPDATE = "tick-update";

    /** Server → client: {@code PipelineListener.label}. */
    public static final String LABEL = "label";

    /** Server → client: {@code PipelineListener.output}. */
    public static final String OUTPUT = "output";

    /** Server → client: {@code PipelineListener.warn}. */
    public static final String WARN = "warn";

    /** Server → client: {@code PipelineListener.error} — one line of error-stream diagnostics. */
    public static final String ERROR_LINE = "error-line";

    /** Server → client, repeated, immediately before {@link #PIPELINE_FINISH}: one of its result's diagnostics. */
    public static final String PIPELINE_DIAGNOSTIC = "pipeline-diagnostic";

    /** Server → client: {@code PipelineListener.stepFinish}. */
    public static final String STEP_FINISH = "step-finish";

    /** Server → client: {@code PipelineListener.pipelineFinish} (success flag only; see {@link #PIPELINE_DIAGNOSTIC}). */
    public static final String PIPELINE_FINISH = "pipeline-finish";

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
     * result-payload {@code errors[]} on finish messages (those are command output).
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
     * Server → client keep-alive while a long job runs (ticket-1051). Resets client stream idle
     * ({@code JK_STREAM_IDLE_MS}); clients ignore the payload. Interval: {@code JK_ENGINE_HEARTBEAT_MS}
     * (default 30s; {@code 0} disables).
     */
    public static final String HEARTBEAT = "heartbeat";

    /** Client → server: single-project test pipeline ({@code jk test}). */
    public static final String TEST_REQUEST = "test-request";

    /** Client → server: single-project build; same wire shape as {@link #TEST_REQUEST}. */
    public static final String SINGLE_BUILD_REQUEST = "single-build-request";

    /** The {@code dir} tag {@link #TEST_REQUEST}/{@link #SINGLE_BUILD_REQUEST}'s single pipeline events carry. */
    public static final String SINGLE_PIPELINE_DIR = "";

    /**
     * Client → server: build forecast ({@code jk explain}). Synchronous inline burst of
     * {@link #EXPLAIN_MODULE}/{@link #EXPLAIN_STEP}/{@link #EXPLAIN_EDGE} then {@link #EXPLAIN_DONE}.
     */
    public static final String EXPLAIN_REQUEST = "explain-request";

    /** Server → client, repeated once per module: {@code BuildPlan.Module}'s identity/sizing. */
    public static final String EXPLAIN_MODULE = "explain-module";

    /** Server → client, repeated once per (module, step): a {@code BuildPlan.Module}'s step list entry. */
    public static final String EXPLAIN_STEP = "explain-step";

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

    /** Client → server: write {@code jk.lock} ({@code jk lock}). Terminal: {@link #LOCK_FINISH}. */
    public static final String LOCK_REQUEST = "lock-request";

    /** Client → server: re-resolve {@code jk.lock} ({@code jk update}); same events as lock. */
    public static final String UPDATE_REQUEST = "update-request";

    /** Client → server: align CAS + toolchain with {@code jk.lock} ({@code jk sync}). */
    public static final String SYNC_REQUEST = "sync-request";

    /** Server → client, repeated: opens one module's event scope in a lock/update cascade. */
    public static final String LOCK_MODULE = "lock-module";

    /** Server → client, repeated: one package was resolved and recorded ({@code ResolveObserver.onPackage}). */
    public static final String LOCK_PACKAGE = "lock-package";

    /** Server → client, terminal for {@link #LOCK_REQUEST}/{@link #UPDATE_REQUEST}: cascade outcome. */
    public static final String LOCK_FINISH = "lock-finish";

    // ---- hosted worker commands (single-pipeline shape; structured results as repeated messages) ----

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

    // ---- hosted pipeline commands ------------------------------------------------------------------

    /** Client → server: type-check ({@code jk compile}); single-pipeline shape. */
    public static final String COMPILE_REQUEST = "compile-request";

    /** Client → server: native-image ({@code jk native}); Graal homes resolved client-side. */
    public static final String NATIVE_REQUEST = "native-request";

    /** Client → server: build + cache install ({@code jk install}); launcher write is client-side. */
    public static final String INSTALL_REQUEST = "install-request";

    /** Client → server: materialize a git checkout for {@code jk install <git-url>}. */
    public static final String GIT_FETCH_REQUEST = "git-fetch-request";

    // ---- hosted long-tail commands -----------------------------------------------------------------

    /** Client → server: resolve a Maven CLI tool ({@code jk tool install|run}). */
    public static final String TOOL_RESOLVE_REQUEST = "tool-resolve-request";

    /**
     * Client → server: cache maintenance ({@code prune}/{@code purge}/{@code gc}) at an idle
     * boundary under {@code .prune.lock}; may emit {@link #PRUNE_WAIT} first.
     */
    public static final String CACHE_PRUNE_REQUEST = "cache-prune-request";

    /**
     * Client → server: prepare a script/jar for {@code jk tool run} (header parse, deps, compile);
     * client keeps the exec that owns the terminal.
     */
    public static final String SCRIPT_PREPARE_REQUEST = "script-prepare-request";

    /**
     * Server → client, before the plan burst of a {@link #CACHE_PRUNE_REQUEST}: the operation is
     * queued behind in-flight work — {@code pipelines} in-engine pipelines ({@code 0} with {@code
     * external=true} means another process's prune holds {@code .prune.lock}).
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
    public static final String HISTORY_STEP = "history-step";

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

    /** The {@code "type"} discriminator of a decoded message, or {@code null} if absent/malformed. */
    public static String typeOf(String json) {
        return Jsonl.str(json, TYPE_FIELD);
    }

    public static String auth(String token) {
        return "{\"type\":\"" + AUTH + "\",\"token\":" + Jsonl.quote(token) + "}";
    }

    /**
     * Frozen handshake version. {@code hello}/{@code hello-ack} ({@code version}+{@code proto}) and
     * graceful shutdown are permanent; add fields freely, never rename/retype/remove.
     */
    public static final int PROTOCOL = 1;

    public static String hello(String version) {
        return hello(version, "connect");
    }

    /** {@code purpose} is {@code connect} (working channel) or {@code probe} (liveness/version). */
    public static String hello(String version, String purpose) {
        return "{\"type\":\"" + HELLO + "\",\"version\":" + Jsonl.quote(version)
                + ",\"proto\":" + PROTOCOL
                + ",\"purpose\":" + Jsonl.quote(purpose) + "}";
    }

    /**
     * {@code buildId} is the engine's content identity ({@code BuildIdentity}): empty for
     * releases and identity-less contexts; a jar sha prefix for -SNAPSHOT dev builds, so a
     * REBUILT dev engine is distinguishable from a stale one under the same version string.
     */
    public static String helloAck(String version, long pid, long startedAtMillis, boolean draining, String buildId) {
        return "{\"type\":\""
                + HELLO_ACK
                + "\",\"version\":"
                + Jsonl.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + ",\"proto\":"
                + PROTOCOL
                + ",\"draining\":"
                + draining
                + ",\"buildId\":"
                + Jsonl.quote(buildId == null ? "" : buildId)
                + "}";
    }

    public static String ping() {
        return "{\"type\":\"" + PING + "\"}";
    }

    public static String pong() {
        return "{\"type\":\"" + PONG + "\"}";
    }

    public static String statusRequest() {
        return "{\"type\":\"" + STATUS + "\"}";
    }

    /**
     * The status snapshot. Memory fields are best-effort observations of the engine process itself:
     * heap from the runtime, {@code rssBytes} from the OS ({@code -1} where it exposes none). The
     * http fields describe the embedded HTTP server ({@code docs/http.md}): {@code httpUrl} is
     * non-null while it's serving, {@code httpError} when the {@code [http]} table is enabled but
     * the server failed to start; both null means disabled. {@code mcpUrl} is the HTTP base without a
     * trailing slash plus {@code /mcp} when HTTP is up (JK-1095), else null. (Keys are always
     * emitted — the protocol has ONE null convention: key present, value null.) {@code
     * aotTrainingPid} is the engine's sidecar AOT trainer while one is running, {@code -1}
     * otherwise — the client never talks to that process, it only reports it
     * (docs/architecture.md).
     */
    public static String statusAck(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activePipelines,
            boolean draining,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            long aotTrainingPid,
            String httpUrl,
            String httpError) {
        return statusAck(
                version,
                pid,
                startedAtMillis,
                activeRequests,
                activePipelines,
                draining,
                heapUsedBytes,
                heapCommittedBytes,
                heapMaxBytes,
                rssBytes,
                aotTrainingPid,
                httpUrl,
                httpError,
                true,
                activeRequests,
                activePipelines);
    }

    /**
     * Status ack with high-water concurrency marks (instrumentation for concurrent memory
     * decisions). Peaks are non-decreasing for the engine process lifetime.
     */
    public static String statusAck(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activePipelines,
            boolean draining,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            long aotTrainingPid,
            String httpUrl,
            String httpError,
            boolean mcpEnabled,
            int peakActiveRequests,
            int peakActivePipelines) {
        String mcpUrl = mcpEnabled ? mcpUrlFromHttp(httpUrl) : null;
        return "{\"type\":\""
                + STATUS_ACK
                + "\",\"version\":"
                + Jsonl.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + ",\"proto\":"
                + PROTOCOL
                + ",\"activeRequests\":"
                + activeRequests
                + ",\"activePipelines\":"
                + activePipelines
                + ",\"draining\":"
                + draining
                + ",\"heapUsedBytes\":"
                + heapUsedBytes
                + ",\"heapCommittedBytes\":"
                + heapCommittedBytes
                + ",\"heapMaxBytes\":"
                + heapMaxBytes
                + ",\"rssBytes\":"
                + rssBytes
                + ",\"aotTrainingPid\":"
                + aotTrainingPid
                + ",\"httpUrl\":"
                + Jsonl.quote(httpUrl)
                + ",\"httpError\":"
                + Jsonl.quote(httpError)
                + ",\"mcpUrl\":"
                + Jsonl.quote(mcpUrl)
                + ",\"peakActiveRequests\":"
                + peakActiveRequests
                + ",\"peakActivePipelines\":"
                + peakActivePipelines
                + "}";
    }

    /**
     * Derive MCP endpoint URL from the HTTP base. Strips trailing slashes so {@code
     * http://host:port/} becomes {@code http://host:port/mcp}, never {@code //mcp}.
     */
    static String mcpUrlFromHttp(String httpUrl) {
        if (httpUrl == null || httpUrl.isBlank()) return null;
        String base = httpUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.isEmpty() ? null : base + "/mcp";
    }

    public static String shutdown() {
        return shutdown(false);
    }

    /** {@code force=true} exits the engine now (abandoning in-flight jobs); false drains gracefully. */
    public static String shutdown(boolean force) {
        return "{\"type\":\"" + SHUTDOWN + "\",\"force\":" + force + "}";
    }

    public static String bye() {
        return bye(0, false);
    }

    /** Ack for {@link #SHUTDOWN}: reports the in-flight job count and whether a drain is now underway. */
    public static String bye(int pipelines, boolean draining) {
        return "{\"type\":\"" + BYE + "\",\"pipelines\":" + pipelines + ",\"draining\":" + draining + "}";
    }

    // ---- build-request (client → server) -------------------------------------------------------

    /**
     * Start a workspace build. {@code force} implies refresh; {@code rerun} bypasses action cache
     * only. {@code freshenLock} auto-refreshes a stale workspace lock ({@code jk verify} sends
     * false). Engine forecasts dirty modules itself — no client {@code dirtyHint}.
     */
    public static String buildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean freshenLock) {
        // noTimeline rides the session envelope ({@link #withSession}) only when true — never emit
        // a false default here (Jsonl.bool takes the first key match).
        return "{\"type\":\""
                + BUILD_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"maxModuleConcurrency\":"
                + maxModuleConcurrency
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"freshenLock\":"
                + freshenLock
                + "}";
    }

    public static String buildCancel() {
        return "{\"type\":\"" + BUILD_CANCEL + "\"}";
    }

    /** Start a single-project test run (see {@link #TEST_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String testRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force) {
        return testRequest(dir, cache, jdksDir, workers, profile, verbose, offline, force, false);
    }

    /**
     * Start a single-project test run. {@code parallelTests} lifts the engine's cross-module test
     * gate when several test-requests overlap (workspace {@code jk test --parallel-tests}).
     */
    public static String testRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force,
            boolean parallelTests) {
        // noTimeline: session envelope only (see {@link #withSession}).
        return "{\"type\":\""
                + TEST_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"verbose\":"
                + verbose
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"parallelTests\":"
                + parallelTests
                + "}";
    }

    /** Start a single-project build (see {@link #SINGLE_BUILD_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String singleBuildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force) {
        // noTimeline: session envelope only (see {@link #withSession}).
        return "{\"type\":\""
                + SINGLE_BUILD_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + "}";
    }

    /** Notify client that a chrome timeline file was written (absolute path). */
    public static String timeline(String absolutePath) {
        return "{\"type\":\"" + TIMELINE + "\",\"path\":" + Jsonl.quote(absolutePath) + "}";
    }

    /**
     * Resolve + write {@code jk.lock} (see {@link #LOCK_REQUEST}). {@code repoUrl} may be {@code
     * null}. {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side
     * (the same fields {@link #buildRequest} carries).
     */
    public static String lockRequest(
            String dir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            String repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + LOCK_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"features\":"
                + quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"sources\":"
                + sources
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Re-resolve fresh and overwrite {@code jk.lock} (see {@link #UPDATE_REQUEST}). {@code gitTarget}
     * is the {@code --git <name>} argument ({@code null} = every git dep) and is only read when
     * {@code gitOnly} is set.
     */
    public static String updateRequest(
            String dir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            String repoUrl,
            boolean gitOnly,
            String gitTarget,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + UPDATE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"features\":"
                + quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"gitOnly\":"
                + gitOnly
                + ",\"gitTarget\":"
                + Jsonl.quote(gitTarget)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Sync the CAS + toolchain with {@code jk.lock} (see {@link #SYNC_REQUEST}). {@code jdksDir}/
     * {@code repoUrl} may be {@code null}. {@code refresh} rides separately from {@code force} for
     * the same reason {@code rerun} does on {@link #buildRequest}: it re-downloads locked artifacts
     * without implying the rest of {@code force}'s cache bypasses.
     */
    public static String syncRequest(
            String dir,
            String cache,
            String jdksDir,
            String repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {
        return "{\"type\":\""
                + SYNC_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"sources\":"
                + sources
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"refresh\":"
                + refresh
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Scan the lockfile against OSV (see {@link #AUDIT_REQUEST}). {@code severity} is the client's
     * threshold, carried only for the evaluate step's label (the client applies the threshold
     * itself); {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides and may be
     * {@code null}.
     */
    public static String auditRequest(
            String dir, String cache, String severity, String osvBatchUrl, String osvVulnsUrl) {
        return "{\"type\":\""
                + AUDIT_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"severity\":"
                + Jsonl.quote(severity)
                + ",\"osvBatchUrl\":"
                + Jsonl.quote(osvBatchUrl)
                + ",\"osvVulnsUrl\":"
                + Jsonl.quote(osvVulnsUrl)
                + "}";
    }

    /**
     * Format sources (see {@link #FORMAT_REQUEST}). Style names arrive already resolved (flags +
     * env + the {@code [format]} block are client-side concerns); {@code rewriteConfig} may be
     * {@code null}.
     */
    public static String formatRequest(
            String dir,
            String cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            String rewriteConfig,
            boolean offline,
            boolean verbose) {
        return "{\"type\":\""
                + FORMAT_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"check\":"
                + check
                + ",\"javaStyle\":"
                + Jsonl.quote(javaStyle)
                + ",\"kotlinStyle\":"
                + Jsonl.quote(kotlinStyle)
                + ",\"optimizeImports\":"
                + optimizeImports
                + ",\"rewriteConfig\":"
                + Jsonl.quote(rewriteConfig)
                + ",\"offline\":"
                + offline
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Publish artifacts (see {@link #PUBLISH_REQUEST}). The credential fields ({@code authType} =
     * {@code basic}/{@code bearer}/{@code anonymous} + {@code user}/{@code pass}/{@code token}) and
     * {@code gpgPassphrase} were resolved client-side; nullable string fields may be {@code null}.
     */
    public static String publishRequest(
            String dir,
            String cache,
            String repoUrl,
            String region,
            String endpoint,
            String jar,
            boolean allowSnapshot,
            boolean dryRun,
            String keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            String authType,
            String user,
            String pass,
            String token,
            boolean verbose) {
        return "{\"type\":\""
                + PUBLISH_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"region\":"
                + Jsonl.quote(region)
                + ",\"endpoint\":"
                + Jsonl.quote(endpoint)
                + ",\"jar\":"
                + Jsonl.quote(jar)
                + ",\"allowSnapshot\":"
                + allowSnapshot
                + ",\"dryRun\":"
                + dryRun
                + ",\"keyFile\":"
                + Jsonl.quote(keyFile)
                + ",\"gpgPassphrase\":"
                + Jsonl.quote(gpgPassphrase)
                + ",\"sigstore\":"
                + sigstore
                + ",\"slsa\":"
                + slsa
                + ",\"sbom\":"
                + sbom
                + ",\"authType\":"
                + Jsonl.quote(authType)
                + ",\"user\":"
                + Jsonl.quote(user)
                + ",\"pass\":"
                + Jsonl.quote(pass)
                + ",\"token\":"
                + Jsonl.quote(token)
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build an OCI image (see {@link #IMAGE_REQUEST}). {@code tarball} is tri-state: {@code null}
     * (no tarball — daemon/push mode), {@code ""} (default layout path), or an explicit path — the
     * same tri-state {@code --tarball}'s optional value has. {@code offline}/{@code force}/{@code
     * rerun}/{@code verbose} reconstruct the session config engine-side, as on {@link
     * #buildRequest}.
     */
    public static String imageRequest(
            String dir,
            String cache,
            String jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarball,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + IMAGE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"registry\":"
                + Jsonl.quote(registry)
                + ",\"tag\":"
                + Jsonl.quote(tag)
                + ",\"tarball\":"
                + Jsonl.quote(tarball)
                + ",\"dockerExecutable\":"
                + Jsonl.quote(dockerExecutable)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Convert a foreign build to {@code jk.toml} (see {@link #IMPORT_REQUEST}). All paths are
     * absolute (the client pre-flighted detection/overwrite checks); {@code report} may be {@code
     * null}.
     */
    public static String importRequest(
            String source, String out, String baseDir, String tmpDir, boolean force, String report, String cache) {
        return "{\"type\":\""
                + IMPORT_REQUEST
                + "\",\"source\":"
                + Jsonl.quote(source)
                + ",\"out\":"
                + Jsonl.quote(out)
                + ",\"baseDir\":"
                + Jsonl.quote(baseDir)
                + ",\"tmpDir\":"
                + Jsonl.quote(tmpDir)
                + ",\"force\":"
                + force
                + ",\"report\":"
                + Jsonl.quote(report)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + "}";
    }

    /**
     * Provision a Maven/Gradle distribution (see {@link #PROVISION_REQUEST}). Project directory
     * field is {@code dir} — same spelling as every other hosted request.
     */
    public static String provisionRequest(
            String cache, String dir, String toolsRoot, boolean noDiscover, boolean gradle) {
        return "{\"type\":\""
                + PROVISION_REQUEST
                + "\",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"toolsRoot\":"
                + Jsonl.quote(toolsRoot)
                + ",\"noDiscover\":"
                + noDiscover
                + ",\"gradle\":"
                + gradle
                + "}";
    }

    /**
     * Type-check the project (see {@link #COMPILE_REQUEST}). {@code profile} may be {@code null};
     * {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side.
     */
    public static String compileRequest(
            String dir, String cache, String profile, boolean offline, boolean force, boolean verbose) {
        return "{\"type\":\""
                + COMPILE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build native artifacts (see {@link #NATIVE_REQUEST}). {@code mainClass} is the {@code --main}
     * override (may be {@code null} — the engine resolves {@code [native].main-class}/{@code
     * [image].main}/{@code [project].main} itself); {@code extraArgs} are forwarded to {@code
     * native-image}; {@code graalHomes} maps each native-eligible module dir to the GraalVM home
     * the client resolved for it (the one flat-map wire encoding — see {@code Jsonl.map}).
     */
    public static String nativeRequest(
            String dir,
            String cache,
            String jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            java.util.Map<String, String> graalHomes) {
        return "{\"type\":\""
                + NATIVE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"extraArgs\":"
                + quoteArray(extraArgs)
                + ",\"graalHomes\":"
                + Jsonl.map(graalHomes)
                + "}";
    }

    /**
     * Build + cache-install the project (see {@link #INSTALL_REQUEST}). {@code m2Dir} is the
     * resolved local Maven repo root ({@code ~/.m2} or {@code --m2-dir}); {@code graalHome} is
     * non-null only for a native application (resolved client-side).
     */
    public static String installRequest(
            String dir,
            String cache,
            String m2Dir,
            String graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + INSTALL_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"m2Dir\":"
                + Jsonl.quote(m2Dir)
                + ",\"graalHome\":"
                + Jsonl.quote(graalHome)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Materialize a git checkout (see {@link #GIT_FETCH_REQUEST}). {@code url} is the expanded
     * fetch URL, {@code canonicalUrl} its canonical identity, {@code ref} the tag-or-branch name;
     * {@code refresh} forces a re-fetch of an already-materialized ref.
     */
    public static String gitFetchRequest(
            String url, String canonicalUrl, String ref, String cache, boolean refresh, boolean requireJkToml) {
        return "{\"type\":\""
                + GIT_FETCH_REQUEST
                + "\",\"url\":"
                + Jsonl.quote(url)
                + ",\"canonicalUrl\":"
                + Jsonl.quote(canonicalUrl)
                + ",\"ref\":"
                + Jsonl.quote(ref)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"refresh\":"
                + refresh
                + ",\"requireJkToml\":"
                + requireJkToml
                + "}";
    }

    /**
     * Forecast a build (see {@link #EXPLAIN_REQUEST}). Beyond the plan itself, the fields carry the
     * plan-affecting {@code jk build} options the engine-side ETA estimate needs ({@code jdksDir}/
     * {@code profile} may be {@code null}); the computed estimate rides back as an {@link #ETA}
     * event inside the explain burst.
     */
    public static String explainRequest(
            String dir,
            String cache,
            int workers,
            boolean skipTests,
            String profile,
            String jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose) {
        return "{\"type\":\""
                + EXPLAIN_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"workers\":"
                + workers
                + ",\"skipTests\":"
                + skipTests
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"serial\":"
                + serial
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    // ---- explain events (server → client) --------------------------------------------------------

    public static String explainModule(
            String dir, String coord, int sourceCount, int testCount, boolean producesJar, boolean producesImage) {
        return "{\"type\":\""
                + EXPLAIN_MODULE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"sourceCount\":"
                + sourceCount
                + ",\"testCount\":"
                + testCount
                + ",\"producesJar\":"
                + producesJar
                + ",\"producesImage\":"
                + producesImage
                + "}";
    }

    public static String explainStep(String dir, String name, String status, String text, String key) {
        return "{\"type\":\""
                + EXPLAIN_STEP
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"status\":"
                + Jsonl.quote(status)
                + ",\"text\":"
                + Jsonl.quote(text)
                + ",\"key\":"
                + Jsonl.quote(key)
                + "}";
    }

    public static String explainEdge(String dir, String dependsOnDir) {
        return "{\"type\":\""
                + EXPLAIN_EDGE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"dependsOnDir\":"
                + Jsonl.quote(dependsOnDir)
                + "}";
    }

    public static String forecastRequest(
            String dir, String cache, boolean skipTests, boolean offline, boolean force, boolean rerun) {
        return "{\"type\":\""
                + FORECAST_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"rebuild\":"
                + rerun
                + "}";
    }

    public static String treeRequest(
            String dir, int maxDepth, boolean flatten, boolean stack, java.util.List<String> scopes) {
        return "{\"type\":\"" + TREE_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"maxDepth\":" + maxDepth
                + ",\"flatten\":" + flatten
                + ",\"stack\":" + stack
                + ",\"scopes\":" + quoteArray(scopes)
                + "}";
    }

    public static String treeAck(String error, String rendered) {
        return "{\"type\":\"" + TREE_ACK + "\",\"error\":" + Jsonl.quote(error)
                + ",\"rendered\":" + Jsonl.quote(rendered == null ? "" : rendered)
                + "}";
    }

    public static String whyRequest(String dir, String query) {
        return "{\"type\":\"" + WHY_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + ",\"query\":" + Jsonl.quote(query) + "}";
    }

    public static String ideModelRequest(String dir, String cache, String jdksDir) {
        return "{\"type\":\"" + IDE_MODEL_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"jdksDir\":" + Jsonl.quote(jdksDir)
                + "}";
    }

    public static String generateRequest(String dir, String kind) {
        return generateRequest(dir, kind, java.util.Map.of());
    }

    /** As above with generator parameters (scaffold inputs etc.) as a flat map. */
    public static String generateRequest(String dir, String kind, java.util.Map<String, String> params) {
        return "{\"type\":\"" + GENERATE_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"params\":" + Jsonl.map(params)
                + "}";
    }

    /** Decode side of {@link #generateRequest(String, String, java.util.Map)}. */
    public static java.util.Map<String, String> generateParams(String requestLine) {
        return Jsonl.strMap(requestLine, "params");
    }

    public static String pluginCommandRequest(String dir, String cache, String command, java.util.List<String> args) {
        return "{\"type\":\"" + PLUGIN_VERB_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"command\":" + Jsonl.quote(command)
                + ",\"args\":" + quoteArray(args)
                + "}";
    }

    public static String denyCheckRequest(String dir) {
        return "{\"type\":\"" + DENY_CHECK_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }

    public static String editRequest(String file, String op, java.util.List<String> args) {
        return "{\"type\":\"" + EDIT_REQUEST + "\",\"file\":" + Jsonl.quote(file)
                + ",\"op\":" + Jsonl.quote(op)
                + ",\"args\":" + quoteArray(args) + "}";
    }

    public static String editAck(boolean changed, String error) {
        return "{\"type\":\"" + EDIT_ACK + "\",\"changed\":" + changed + ",\"error\":" + Jsonl.quote(error) + "}";
    }

    public static String projectInfoRequest(String dir, String cache) {
        return "{\"type\":\"" + PROJECT_INFO_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + ",\"cache\":"
                + Jsonl.quote(cache) + "}";
    }

    public static String outdatedRequest(String dir, String cache, String repoUrl, boolean offline, boolean force) {
        return "{\"type\":\"" + OUTDATED_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"repoUrl\":" + Jsonl.quote(repoUrl)
                + ",\"offline\":" + offline
                + ",\"force\":" + force
                + "}";
    }

    public static String execPlanRequest(
            String dir, String cache, String kind, String mainOverride, String binName, String binDir, String libDir) {
        return "{\"type\":\"" + EXEC_PLAN_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"mainOverride\":" + Jsonl.quote(mainOverride)
                + ",\"binName\":" + Jsonl.quote(binName)
                + ",\"binDir\":" + Jsonl.quote(binDir)
                + ",\"libDir\":" + Jsonl.quote(libDir)
                + "}";
    }

    public static String forecastAck(
            java.util.List<String> dirtyDirs, boolean lockStale, boolean empty, java.util.List<String> errors) {
        return "{\"type\":\""
                + FORECAST_ACK
                + "\",\"dirtyDirs\":"
                + quoteArray(dirtyDirs)
                + ",\"lockStale\":"
                + lockStale
                + ",\"empty\":"
                + empty
                + ",\"errors\":"
                + quoteArray(errors)
                + "}";
    }

    public static String explainDone(int maxReadyWidth, int moduleCount) {
        return "{\"type\":\""
                + EXPLAIN_DONE
                + "\",\"maxReadyWidth\":"
                + maxReadyWidth
                + ",\"moduleCount\":"
                + moduleCount
                + "}";
    }

    // ---- build events (server → client) ----------------------------------------------------------

    /**
     * Preflight progress: {@code stage} (checking|lock|graph|plan), stage-local {@code done}/{@code
     * total} ({@code total == 0} = indeterminate), optional {@code label}.
     */
    public static String preflight(String stage, int done, int total, String label) {
        return "{\"type\":\""
                + PREFLIGHT
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

    public static String planModule(String dir, String coord, String pipelineName, int weight, boolean fullyCached) {
        return "{\"type\":\""
                + PLAN_MODULE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"pipelineName\":"
                + Jsonl.quote(pipelineName)
                + ",\"weight\":"
                + weight
                + ",\"fullyCached\":"
                + fullyCached
                + "}";
    }

    public static String planStep(String dir, String name, String label, String phase) {
        return "{\"type\":\""
                + PLAN_STEP
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"label\":"
                + Jsonl.quote(label)
                + ",\"phase\":"
                + Jsonl.quote(phase)
                + "}";
    }

    public static String planDone(int count) {
        return "{\"type\":\"" + PLAN_DONE + "\",\"count\":" + count + "}";
    }

    public static String eta(long millis) {
        return "{\"type\":\"" + ETA + "\",\"millis\":" + millis + "}";
    }

    /**
     * Workspace aggregate progress (JK-1120). {@code progress} is 0–100 (one decimal) from the
     * engine tracker; {@code numerator}/{@code denominator} are the same abstract bar units.
     * {@code phase} is {@code preflight}, {@code execute}, or {@code done}.
     */
    public static String workspaceProgress(
            String dir,
            long numerator,
            long denominator,
            String phase,
            int modulesComplete,
            int modulesTotal) {
        return "{\"schema\":1,\"type\":\""
                + WORKSPACE_PROGRESS
                + "\",\"dir\":"
                + Jsonl.quote(dir == null ? "" : dir)
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + progressPercent(numerator, denominator)
                + ",\"phase\":"
                + Jsonl.quote(phase == null ? "" : phase)
                + ",\"modulesComplete\":"
                + modulesComplete
                + ",\"modulesTotal\":"
                + modulesTotal
                + "}";
    }

    public static String moduleStart(String dir) {
        return "{\"type\":\"" + MODULE_START + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }

    public static String pipelineStart(
            String dir,
            String pipelineName,
            long numerator,
            long denominator,
            int stepsTotal,
            int stepsComplete,
            boolean cancelled) {
        return "{\"schema\":1,\"type\":\""
                + PIPELINE_START
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"pipelineName\":"
                + Jsonl.quote(pipelineName)
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + progressPercent(numerator, denominator)
                + ",\"stepsTotal\":"
                + stepsTotal
                + ",\"stepsComplete\":"
                + stepsComplete
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    public static String stepStart(String dir, String step, String phase, int ticks) {
        return "{\"schema\":1,\"type\":\""
                + STEP_START
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"phase\":"
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
            int stepsTotal,
            int stepsComplete,
            boolean cancelled) {
        return "{\"schema\":1,\"type\":\""
                + type
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"delta\":"
                + delta
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"progress\":"
                + progressPercent(numerator, denominator)
                + ",\"stepsTotal\":"
                + stepsTotal
                + ",\"stepsComplete\":"
                + stepsComplete
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
            int stepsTotal,
            int stepsComplete,
            boolean cancelled) {
        return progressLike(PROGRESS, dir, step, delta, numerator, denominator, stepsTotal, stepsComplete, cancelled);
    }

    public static String tickUpdate(
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int stepsTotal,
            int stepsComplete,
            boolean cancelled) {
        return progressLike(
                TICK_UPDATE, dir, step, delta, numerator, denominator, stepsTotal, stepsComplete, cancelled);
    }

    public static String label(String dir, String step, String label) {
        return "{\"type\":\""
                + LABEL
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"label\":"
                + Jsonl.quote(label)
                + "}";
    }

    public static String output(String dir, String step, String line) {
        return "{\"type\":\""
                + OUTPUT
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"line\":"
                + Jsonl.quote(line)
                + "}";
    }

    private static String diagnosticLike(
            String type, String dir, String step, String code, String message, String test, String exceptionClass) {
        return "{\"type\":\""
                + type
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"code\":"
                + Jsonl.quote(code)
                + ",\"message\":"
                + Jsonl.quote(message)
                + ",\"test\":"
                + Jsonl.quote(test)
                + ",\"exceptionClass\":"
                + Jsonl.quote(exceptionClass)
                + "}";
    }

    public static String warn(String dir, String step, String code, String message) {
        return diagnosticLike(WARN, dir, step, code, message, "", "");
    }

    public static String errorLine(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(ERROR_LINE, dir, step, code, message, test, exceptionClass);
    }

    public static String pipelineDiagnostic(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(PIPELINE_DIAGNOSTIC, dir, step, code, message, test, exceptionClass);
    }

    public static String stepFinish(String dir, String step, String phase, String status) {
        return "{\"type\":\""
                + STEP_FINISH
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"step\":"
                + Jsonl.quote(step)
                + ",\"phase\":"
                + Jsonl.quote(phase)
                + ",\"status\":"
                + Jsonl.quote(status)
                + "}";
    }

    public static String pipelineFinish(String dir, boolean success) {
        return "{\"type\":\"" + PIPELINE_FINISH + "\",\"kind\":\"build\",\"dir\":" + Jsonl.quote(dir) + ",\"success\":"
                + success + "}";
    }

    /**
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk test} run's counts
     * (total/succeeded/failed/skipped) — absent (-1) for a plain {@code buildWorkspace} pipeline-finish.
     * Bundled into the same message rather than a separate terminal one because the client must know
     * these counts <em>before</em> dispatching this event to its console listener: the listener's own
     * {@code pipelineFinish} handler is what renders the "Passed N tests" summary line.
     */
    public static String pipelineFinish(
            String dir, boolean success, long total, long succeeded, long failed, long skipped) {
        return pipelineFinish(dir, success, null, total, succeeded, failed, skipped);
    }

    /**
     * As {@link #pipelineFinish(String, boolean, long, long, long, long)}, additionally carrying a {@code
     * jk build} run's outcome (see {@code BuildPipelines.BUILD_OUTCOME}, e.g. {@code "up-to-date"}/
     * {@code "no-sources"}) — {@code null} when not applicable (a workspace per-module pipeline, or a
     * test-only run). Like the test counts, this rides along on {@code pipeline-finish} because the
     * client's console listener renders its summary line from within its own {@code pipelineFinish}
     * handler, before any later message could arrive.
     */
    public static String pipelineFinish(
            String dir, boolean success, String buildOutcome, long total, long succeeded, long failed, long skipped) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk lock}/{@code jk
     * update} module's written-lockfile counts (packages / packages-with-sources / plugins) — the
     * structured ingredients of the client's summary lines, which its console listener renders from
     * within its own {@code pipelineFinish} handler.
     */
    public static String pipelineFinishLock(String dir, boolean success, long packages, long sources, long plugins) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk sync} run's
     * fetched/up-to-date counts for the client's summary line ({@code "N fetched, M up-to-date"}).
     */
    public static String pipelineFinishSync(String dir, boolean success, long fetched, long upToDate) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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

    /** Opens one module's event scope in a {@code jk lock}/{@code jk update} cascade (see {@link #LOCK_MODULE}). */
    public static String lockModule(String dir, String coord) {
        return "{\"type\":\"" + LOCK_MODULE + "\",\"dir\":" + Jsonl.quote(dir) + ",\"coord\":" + Jsonl.quote(coord) + "}";
    }

    /** One resolved package, streamed as it is recorded (see {@link #LOCK_PACKAGE}). */
    public static String lockPackage(String dir, String name, String version) {
        return "{\"type\":\""
                + LOCK_PACKAGE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"version\":"
                + Jsonl.quote(version)
                + "}";
    }

    /**
     * Terminal for a lock/update request. {@code exitCode} is computed engine-side (the engine saw
     * the step statuses: a failed {@code resolve} step exits 6, other failures exit 2/CONFIG);
     * {@code errors} carries pre-pipeline failures (manifest parse, workspace module load) as plain
     * uncolored text for the client to render. {@code refreshed} is {@code jk update --git}'s
     * refreshed-dependency count, {@code -1} for every other request.
     */
    public static String lockFinish(boolean success, int exitCode, List<String> errors, int refreshed) {
        return "{\"type\":\""
                + LOCK_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + quoteArray(errors)
                + ",\"refreshed\":"
                + refreshed
                + "}";
    }

    // ---- hosted worker-command events (server → client) --------------------------------------------

    /** One OSV finding (see {@link #AUDIT_FINDING}) — plain structured fields, no theming. */
    public static String auditFinding(
            String dir, String module, String version, String vulnId, String severity, String summary) {
        return "{\"type\":\""
                + AUDIT_FINDING
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
     * One file's format result (see {@link #FORMAT_FILE}). {@code index}/{@code total} drive the
     * client's per-file progress bar ({@code total} is known engine-side before the worker forks).
     */
    public static String formatFile(String dir, String path, String status, String message, int index, int total) {
        return "{\"type\":\""
                + FORMAT_FILE
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

    /** One import progress note (see {@link #IMPORT_NOTE}). */
    public static String importNote(String dir, String kind, String text) {
        return "{\"type\":\""
                + IMPORT_NOTE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"kind\":"
                + Jsonl.quote(kind)
                + ",\"text\":"
                + Jsonl.quote(text)
                + "}";
    }

    /**
     * Terminal for {@link #PROVISION_REQUEST}. {@code bin} is the provisioned tool's launcher path
     * ({@code null} on failure); {@code source}/{@code version} feed the client's one-line
     * "Maven X downloaded" note; {@code diag} is the worker's passthrough chatter, carried only when
     * {@code exit != 0}.
     */
    public static String provisionResult(
            String bin, String version, String source, String error, int exit, String diag) {
        return "{\"type\":\""
                + PROVISION_RESULT
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
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk format} run's
     * counts and the formatter worker's exit code ({@code jk format --check} exits non-zero when
     * files need formatting — a legitimate outcome, not a pipeline failure, so it rides here rather
     * than failing the pipeline). {@code total} of 0 means no sources were found.
     */
    public static String pipelineFinishFormat(
            String dir, boolean success, int changed, int clean, int errors, int total, int workerExit) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@link #GIT_FETCH_REQUEST}'s
     * materialized checkout path and resolved commit sha ({@code null} when the fetch failed).
     */
    public static String pipelineFinishGitFetch(String dir, boolean success, String checkout, String sha) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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

    /** As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk publish} run's uploaded-file count. */
    public static String pipelineFinishPublish(String dir, boolean success, int files) {
        return "{\"type\":\""
                + PIPELINE_FINISH
                + "\",\"kind\":\"publish\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"publishFiles\":"
                + files
                + "}";
    }

    /**
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@code jk import} run's
     * worker exit code, warning count, and error/diagnostic text (all plain — the client prefixes
     * and renders).
     */
    public static String pipelineFinishImport(
            String dir, boolean success, int exitCode, int warnings, String error, String diag) {
        return "{\"type\":\""
                + PIPELINE_FINISH
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
     * As {@link #pipelineFinish(String, boolean, long, long, long, long)} (the image pipeline runs the full
     * pipeline, so test counts ride along for the exit-code logic), additionally carrying the
     * structured ingredients of the Image chip's success tail: exactly one of {@code imageTarball}
     * (tarball mode), {@code imageDaemonExe} (local-daemon load), or neither (registry push, render
     * {@code imageRef}) is non-null; {@code imageName}/{@code imageVersion} name the image in
     * daemon mode.
     */
    public static String pipelineFinishImage(
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
                + PIPELINE_FINISH
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
        return "{\"type\":\""
                + MODULE_FINISH
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
                + "}";
    }

    public static String workspaceFinish(boolean success, int exitCode, List<String> errors) {
        return "{\"type\":\""
                + WORKSPACE_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + quoteArray(errors)
                + "}";
    }

    /** The one error envelope; see {@link #ERROR} for the code vocabulary. */
    public static String error(String code, String message) {
        return "{\"type\":\"" + ERROR + "\",\"code\":" + Jsonl.quote(code) + ",\"message\":" + Jsonl.quote(message) + "}";
    }

    /** {@code error} with {@link #ERR_REQUEST_FAILED} — the former build-error catch-all. */
    public static String requestFailed(String message) {
        return error(ERR_REQUEST_FAILED, message);
    }

    /** Keep-alive line during long jobs (ticket-1051). */
    public static String heartbeat(long elapsedMillis) {
        return "{\"type\":\"" + HEARTBEAT + "\",\"elapsedMillis\":" + elapsedMillis + "}";
    }

    // ---- hosted long-tail commands -----------------------------------------------------------------

    /**
     * Resolve a Maven-published CLI tool (see {@link #TOOL_RESOLVE_REQUEST}). {@code coord} is a
     * {@code ToolCoordSpec} string — pinned {@code g:a:v} or floating {@code g:a[@selector]},
     * pinned engine-side against maven-metadata. {@code with} carries {@code --with} extras (same
     * grammar, may be empty). {@code mainClass} is the {@code --main} override ({@code null} =
     * read the primary jar's manifest engine-side); {@code repoUrl} overrides Maven Central
     * ({@code null} = Central).
     */
    public static String toolResolveRequest(
            String coord, List<String> with, String bin, String mainClass, String repoUrl, String cache) {
        return "{\"type\":\""
                + TOOL_RESOLVE_REQUEST
                + "\",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"with\":"
                + quoteArray(with)
                + ",\"bin\":"
                + Jsonl.quote(bin)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + "}";
    }

    /**
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@link #TOOL_RESOLVE_REQUEST}
     * result: the pinned {@code g:a:v} the resolve landed on (a floating spec's concrete version is
     * decided engine-side against maven-metadata), the resolved {@code Main-Class}, and the
     * transitive classpath in resolution order (absolute CAS paths — a flat string array, per the
     * codec's no-nested-objects rule). All {@code null}/empty when the resolve failed.
     */
    public static String pipelineFinishTool(
            String dir, boolean success, String coord, String mainClass, List<String> classpath) {
        return "{\"type\":\""
                + PIPELINE_FINISH
                + "\",\"kind\":\"tool\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"toolCoord\":"
                + Jsonl.quote(coord)
                + ",\"toolMainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"toolClasspath\":"
                + quoteArray(classpath)
                + "}";
    }

    /**
     * Prepare a loose script/jar for execution (see {@link #SCRIPT_PREPARE_REQUEST}). {@code
     * stateDir}/{@code repoUrl} may be {@code null} (defaults).
     */
    public static String scriptPrepareRequest(
            String mode,
            String script,
            String cache,
            String stateDir,
            String repoUrl,
            boolean forceRecompile,
            List<String> with) {
        return "{\"type\":\""
                + SCRIPT_PREPARE_REQUEST
                + "\",\"mode\":"
                + Jsonl.quote(mode)
                + ",\"with\":"
                + quoteArray(with)
                + ",\"script\":"
                + Jsonl.quote(script)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"stateDir\":"
                + Jsonl.quote(stateDir)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"forceRecompile\":"
                + forceRecompile
                + "}";
    }

    /**
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@link
     * #SCRIPT_PREPARE_REQUEST} result: the exec ingredients the client-side launch needs. Fields
     * not applicable to the prepared mode (and everything on failure) are {@code null}/empty.
     */
    public static String pipelineFinishScript(
            String dir,
            boolean success,
            String mainClass,
            List<String> classpath,
            String classesDir,
            String kotlincBin,
            String stdlib) {
        return "{\"type\":\""
                + PIPELINE_FINISH
                + "\",\"kind\":\"script\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"scriptMainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"scriptClasspath\":"
                + quoteArray(classpath)
                + ",\"scriptClassesDir\":"
                + Jsonl.quote(classesDir)
                + ",\"scriptKotlincBin\":"
                + Jsonl.quote(kotlincBin)
                + ",\"scriptStdlib\":"
                + Jsonl.quote(stdlib)
                + "}";
    }

    /**
     * Run a cache maintenance operation (see {@link #CACHE_PRUNE_REQUEST}). {@code op} is {@code
     * prune}/{@code purge}/{@code gc}; {@code olderThanDays}/{@code sweep}/{@code maxSize} apply to
     * {@code prune} only ({@code maxSize} may be {@code null}); {@code includeJkTmp} asks the prune
     * to also sweep {@code ~/.jk/tmp} (only when the default cache dir is in use, mirroring the
     * in-process command's behavior).
     */
    public static String cachePruneRequest(
            String op,
            String cache,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            String maxSize,
            boolean includeJkTmp) {
        return "{\"type\":\""
                + CACHE_PRUNE_REQUEST
                + "\",\"op\":"
                + Jsonl.quote(op)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"olderThanDays\":"
                + olderThanDays
                + ",\"dryRun\":"
                + dryRun
                + ",\"sweep\":"
                + sweep
                + ",\"maxSize\":"
                + Jsonl.quote(maxSize)
                + ",\"includeJkTmp\":"
                + includeJkTmp
                + "}";
    }

    /**
     * A project-scoped cache-clear request (see {@link #CACHE_PRUNE_REQUEST}, {@code op="clear"}):
     * invalidate the action-cache entries for the project at {@code projectRoot} and its workspace.
     * Reuses the maintenance channel; {@code dir} is the project root (the one spelling every
     * request uses for its location), and {@code dryRun} reports what would be removed without
     * deleting.
     */
    public static String cacheClearRequest(String cache, String projectRoot, boolean dryRun) {
        return "{\"type\":\""
                + CACHE_PRUNE_REQUEST
                + "\",\"op\":\"clear\",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"dir\":"
                + Jsonl.quote(projectRoot)
                + ",\"dryRun\":"
                + dryRun
                + "}";
    }

    /** The maintenance job is waiting for the cache to quiesce (see {@link #PRUNE_WAIT}). */
    public static String pruneWait(int pipelines, boolean external) {
        return "{\"type\":\"" + PRUNE_WAIT + "\",\"pipelines\":" + pipelines + ",\"external\":" + external + "}";
    }

    /**
     * As {@link #pipelineFinish(String, boolean)}, additionally carrying a {@link #CACHE_PRUNE_REQUEST}
     * summary: files removed + bytes freed (what would be removed, on a dry run), the LRU evictor's
     * reachable-eviction count ({@code prune --max-size} only), and the repo-mirror links removed
     * ({@code gc} only). {@code -1} = not applicable to the op.
     */
    public static String pipelineFinishCache(
            String dir, boolean success, long files, long bytes, long reachableEvicted, long repoLinks) {
        return "{\"type\":\""
                + PIPELINE_FINISH
                + "\",\"kind\":\"cache\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"cacheFiles\":"
                + files
                + ",\"cacheBytes\":"
                + bytes
                + ",\"cacheReachableEvicted\":"
                + reachableEvicted
                + ",\"cacheRepoLinks\":"
                + repoLinks
                + "}";
    }

    /**
     * Append the client's flag/env JVM-tuning layer to an already-encoded request line (thin-client
     * contract: the {@code jk.toml [jvm]} table never resolves client-side — the engine overlays it
     * at worker-fork time; only {@code --max-ram-percent}/{@code --jvm-arg} and {@code JK_JVM_*}
     * cross the wire). A NONE tuning returns the line unchanged, so absent fields stay absent.
     */
    /**
     * Attach the session envelope — variant selection, client-resolved env values, and worker-JVM
     * tuning — to an encoded request line. The ONE attachment point for session state: every
     * hosted request rides it (so {@code --jvm-arg}/{@code JK_JVM_*} apply to every command that
     * forks workers, not an arbitrary subset), and an empty envelope leaves the line byte-
     * identical. The splice is validated: {@code request} must be a one-line encoded object.
     */
    public static String withSession(
            String request,
            String variant,
            java.util.Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t) {
        return withSession(request, variant, clientEnv, t, false, false);
    }

    /** As above, additionally carrying the session's {@code rebuild} distrust flag when set. */
    public static String withSession(
            String request,
            String variant,
            java.util.Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild) {
        return withSession(request, variant, clientEnv, t, rebuild, false);
    }

    /**
     * As above, with {@code noTimeline} (skip chrome profile write). {@code rebuild} distrusts action
     * cache; {@code noTimeline} is independent.
     */
    public static String withSession(
            String request,
            String variant,
            java.util.Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild,
            boolean noTimeline) {
        return withSession(request, variant, clientEnv, t, rebuild, noTimeline, null);
    }

    /**
     * Session envelope including optional {@code assemblyOverride} ({@code fat} / {@code shrink}) for
     * {@code jk assembly --shrink} one-offs.
     */
    public static String withSession(
            String request,
            String variant,
            java.util.Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild,
            boolean noTimeline,
            String assemblyOverride) {
        if (request == null
                || request.length() < 2
                || request.charAt(0) != '{'
                || request.charAt(request.length() - 1) != '}') {
            throw new IllegalArgumentException("withSession needs an encoded single-line request object");
        }
        boolean hasVariant = variant != null && !variant.isBlank();
        boolean hasEnv = clientEnv != null && !clientEnv.isEmpty();
        boolean hasJvm = t != null
                && (t.maxRamPercent() != null
                        || t.gc() != null
                        || t.stringDedup() != null
                        || !t.extraArgs().isEmpty());
        boolean hasAssembly = assemblyOverride != null && !assemblyOverride.isBlank();
        if (!hasVariant && !hasEnv && !hasJvm && !rebuild && !noTimeline && !hasAssembly) return request;
        StringBuilder b = new StringBuilder(request.substring(0, request.length() - 1));
        if (rebuild) b.append(",\"rebuild\":true");
        if (noTimeline) b.append(",\"noTimeline\":true");
        if (hasVariant) b.append(",\"variant\":").append(Jsonl.quote(variant));
        if (hasEnv) b.append(",\"env\":").append(Jsonl.map(clientEnv));
        if (hasAssembly) b.append(",\"assemblyOverride\":").append(Jsonl.quote(assemblyOverride));
        if (hasJvm) {
            if (t.maxRamPercent() != null)
                b.append(",\"jvmMaxRam\":\"").append(t.maxRamPercent()).append('\"');
            if (t.gc() != null) b.append(",\"jvmGc\":").append(Jsonl.quote(t.gc()));
            if (t.stringDedup() != null)
                b.append(",\"jvmStringDedup\":\"").append(t.stringDedup()).append('\"');
            if (!t.extraArgs().isEmpty()) b.append(",\"jvmArgs\":").append(quoteArray(t.extraArgs()));
        }
        return b.append('}').toString();
    }

    /** Decode {@code assemblyOverride} from a session envelope ({@code fat}/{@code shrink}/empty). */
    public static String assemblyOverrideOf(String request) {
        String v = Jsonl.str(request, "assemblyOverride");
        return v == null ? "" : v;
    }

    /**
     * Append a variant selection + client-resolved env values to an encoded build request (thin
     * client: the engine folds the selection into plugin configs at parse time; env values are the
     * user's shell environment, resolved client-side for env:-indirected plugin config — signing
     * credentials — because the engine's own environment belongs to whichever invocation spawned
     * it). Nothing selected and no env → the line rides unchanged.
     */

    /** Decode side of {@link #withSession}: the selection, or {@code ""}. */
    public static String variantOf(String request) {
        String v = Jsonl.str(request, "variant");
        return v == null ? "" : v;
    }

    /** Decode side of {@link #withSession}: the client-resolved env values, or empty. */
    public static java.util.Map<String, String> clientEnvOf(String request) {
        return Jsonl.strMap(request, "env");
    }

    /** Decode side of {@link #withSession}; NONE when the request carries no tuning fields. */
    public static cc.jumpkick.config.PluginTuning jvmTuning(String request) {
        String maxRam = Jsonl.str(request, "jvmMaxRam");
        String gc = Jsonl.str(request, "jvmGc");
        String dedup = Jsonl.str(request, "jvmStringDedup");
        List<String> args = Jsonl.strArray(request, "jvmArgs");
        if (maxRam == null && gc == null && dedup == null && args.isEmpty()) {
            return cc.jumpkick.config.PluginTuning.NONE;
        }
        Double ram = null;
        try {
            if (maxRam != null) ram = Double.valueOf(maxRam);
        } catch (NumberFormatException ignored) {
            // a malformed number degrades to absent, like every tolerant config read
        }
        return new cc.jumpkick.config.PluginTuning(ram, gc, dedup == null ? null : Boolean.valueOf(dedup), args);
    }

    /** {@code Jsonl} only reads string arrays; it has no writer half, so this is the encode side. */
    static String quoteArray(List<String> values) {
        return Jsonl.array(values);
    }

    // ---- build-history request builders (responses are built engine-side with JsonOut) ----------

    public static String historyListRequest(int limit) {
        return "{\"type\":\"" + HISTORY_LIST_REQUEST + "\",\"limit\":" + limit + "}";
    }

    public static String historyShowRequest(String id) {
        return "{\"type\":\"" + HISTORY_SHOW_REQUEST + "\",\"id\":" + Jsonl.quote(id) + "}";
    }

    public static String historyDeleteRequest(String id) {
        return "{\"type\":\"" + HISTORY_DELETE_REQUEST + "\",\"id\":" + Jsonl.quote(id) + "}";
    }

    /** A metrics stream request; null/blank {@code dir} asks for every row. */
    public static String metricsRequest(String dir) {
        return dir == null || dir.isBlank()
                ? "{\"type\":\"" + METRICS_REQUEST + "\"}"
                : "{\"type\":\"" + METRICS_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }
}
