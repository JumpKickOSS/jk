// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.ArrayList;
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
     * <p>Clients that only ever talk to an already-running engine (the web dashboard, MCP) can
     * always use {@code jdks} here too — that is how they install JDKs. {@code jk jdk
     * install}/{@code update} is the one exception with a bootstrap concern: the engine is a JVM
     * process that needs a JDK to run, so it cannot be the sole path to provisioning the first JDK
     * on a bare machine. That CLI path only sends this request when an engine already answers, and
     * fetches {@code jdks.json} directly itself otherwise.
     */
    public static final String FRESHEN_CATALOG_REQUEST = "freshen-catalog-request";

    /** Server → client, terminal for {@link #FRESHEN_CATALOG_REQUEST}. */
    public static final String FRESHEN_CATALOG_ACK = "freshen-catalog-ack";

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
     * run host hardware calibration. {@code engineColdStartMs} ≤0 means omit.
     * {@code allowNetwork} enables resolve HTTP probe + JUnit jar fetch when missing (default
     * true; false under global {@code --offline}).
     */
    public static String calibrateRequest(boolean force, long engineColdStartMs) {
        // Network on by default; pass allowNetwork=false under global --offline.
        return calibrateRequest(force, engineColdStartMs, true);
    }

    public static String calibrateRequest(boolean force, long engineColdStartMs, boolean allowNetwork) {
        StringBuilder b = new StringBuilder("{\"type\":\"")
                .append(CALIBRATE_REQUEST)
                .append("\",\"force\":")
                .append(force)
                .append(",\"allowNetwork\":")
                .append(allowNetwork);
        if (engineColdStartMs > 0) {
            b.append(",\"engineColdStartMs\":").append(engineColdStartMs);
        }
        return b.append('}').toString();
    }

    /** Idle-optimize request: train worker AOT caches (java-compiler; language workers on-demand). */
    public static String optimizeRequest(boolean force) {
        return "{\"type\":\"" + OPTIMIZE_REQUEST + "\",\"force\":" + force + "}";
    }

    /**
     * Optimize result. {@code summary} is multi-line human text; {@code trained}/{@code skipped}
     * are comma-separated tool tags for machine consumers.
     */
    public static String optimizeAck(boolean ok, String trained, String skipped, String summary) {
        return "{\"type\":\""
                + OPTIMIZE_ACK
                + "\",\"ok\":"
                + ok
                + ",\"trained\":"
                + Jsonl.quote(trained == null ? "" : trained)
                + ",\"skipped\":"
                + Jsonl.quote(skipped == null ? "" : skipped)
                + ",\"summary\":"
                + Jsonl.quote(summary == null ? "" : summary)
                + "}";
    }

    /**
     * calibration result. Component ms fields are 0 when not measured; {@code summary} is
     * human-readable multi-line text for the CLI.
     */
    public static String calibrateAck(
            boolean ok,
            double msPerWeight,
            long jvmForkMs,
            long javacMs,
            long diskIoMs,
            long hashCpuMs,
            long junitForkMs,
            long junitRunMs,
            long junitPlatformMs,
            long resolveMs,
            long engineColdStartMs,
            boolean measured,
            boolean junitPlatformUsed,
            boolean resolveUsed,
            String summary) {
        return "{\"type\":\""
                + CALIBRATE_ACK
                + "\",\"ok\":"
                + ok
                + ",\"msPerWeight\":"
                + msPerWeight
                + ",\"jvmForkMs\":"
                + jvmForkMs
                + ",\"javacMs\":"
                + javacMs
                + ",\"diskIoMs\":"
                + diskIoMs
                + ",\"hashCpuMs\":"
                + hashCpuMs
                + ",\"junitForkMs\":"
                + junitForkMs
                + ",\"junitRunMs\":"
                + junitRunMs
                + ",\"junitPlatformMs\":"
                + junitPlatformMs
                + ",\"resolveMs\":"
                + resolveMs
                + ",\"engineColdStartMs\":"
                + engineColdStartMs
                + ",\"measured\":"
                + measured
                + ",\"junitPlatformUsed\":"
                + junitPlatformUsed
                + ",\"resolveUsed\":"
                + resolveUsed
                + ",\"summary\":"
                + Jsonl.quote(summary == null ? "" : summary)
                + "}";
    }

    /**
     * The status snapshot. Memory fields are best-effort observations of the engine process itself:
     * heap from the runtime, {@code rssBytes} from the OS ({@code -1} where it exposes none). The
     * http fields describe the embedded HTTP server ({@code docs/http.md}): {@code httpUrl} is
     * non-null while it's serving, {@code httpError} when the {@code [http]} table is enabled but
     * the server failed to start; both null means disabled. {@code mcpUrl} is the HTTP base without a
     * trailing slash plus {@code /mcp} when HTTP is up, else null. (Keys are always
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
            int activeBuildPlans,
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
                activeBuildPlans,
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
                activeBuildPlans);
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
            int activeBuildPlans,
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
            int peakActiveBuildPlans) {
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
                + ",\"activeBuildPlans\":"
                + activeBuildPlans
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
                + ",\"peakActiveBuildPlans\":"
                + peakActiveBuildPlans
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
    public static String bye(int plans, boolean draining) {
        return "{\"type\":\"" + BYE + "\",\"plans\":" + plans + ",\"draining\":" + draining + "}";
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
        return buildRequest(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                false);
    }

    /**
     * As above with {@code ephemeralActions} ({@code jk verify} scratch rebuild: tasks must not
     * persist action-cache records or incremental state). Emitted only when true so older engines
     * see an unchanged request.
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
            boolean freshenLock,
            boolean ephemeralActions) {
        return buildRequest(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                ephemeralActions,
                false,
                null);
    }

    /**
     * As above with the workspace test/selection controls. {@code testOnly} makes every module
     * plan stop at {@code run-tests} (workspace {@code jk test}); {@code dirtyHint} is the
     * client's module selection ({@code -m} / {@code --affected-since}) — module dirs the engine
     * must schedule instead of forecasting dirtiness itself. Both are omitted from the wire when
     * unset (false / null / empty).
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
            boolean freshenLock,
            boolean ephemeralActions,
            boolean testOnly,
            List<String> dirtyHint) {
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
                + (ephemeralActions ? ",\"ephemeralActions\":true" : "")
                + (testOnly ? ",\"testOnly\":true" : "")
                + (dirtyHint != null && !dirtyHint.isEmpty() ? ",\"dirtyHint\":" + jsonStringArray(dirtyHint) : "")
                + triggerJsonSuffix()
                + progressModeJsonSuffix()
                + "}";
    }

    /**
     * The client's {@code JK_PROGRESS_MODE} rides each request so the resident engine paints the
     * requesting shell's mode, not whatever env the daemon happened to start with (JK-1816).
     * Emitted only when non-AUTO so older engines see an unchanged request.
     */
    static String progressModeJsonSuffix() {
        var mode = cc.jumpkick.runtime.progress.ProgressBarMode.fromEnvironment();
        if (mode == cc.jumpkick.runtime.progress.ProgressBarMode.AUTO) return "";
        return ",\"progressMode\":" + Jsonl.quote(mode.wireName());
    }

    /** Per-request progress mode; engine-env fallback when the client sent none. */
    public static cc.jumpkick.runtime.progress.ProgressBarMode progressModeOf(String json) {
        String raw = Jsonl.str(json, "progressMode");
        if (raw == null || raw.isBlank()) return cc.jumpkick.runtime.progress.ProgressBarMode.fromEnvironment();
        return cc.jumpkick.runtime.progress.ProgressBarMode.parse(raw);
    }

    /**
     * The build request's module-selection hint, or {@code null} when the client sent none (the
     * engine forecasts dirty modules itself). Never an empty list — an empty selection is not a
     * selection.
     */
    public static List<String> dirtyHintOf(String json) {
        List<String> dirs = stringArrayField(json, "dirtyHint");
        return dirs.isEmpty() ? null : dirs;
    }

    /**
     * Optional {@code trigger} for journal classification ({@code cli}/{@code web}/
     * {@code optimize}/{@code calibrate}). Taken from {@code -Djk.build.trigger} or env
     * {@code JK_BUILD_TRIGGER} so install optimize can mark synthetic runs without a new
     * overload on every call site.
     */
    static String triggerJsonSuffix() {
        String t = System.getProperty("jk.build.trigger");
        if (t == null || t.isBlank()) t = System.getenv("JK_BUILD_TRIGGER");
        if (t == null || t.isBlank()) return "";
        return ",\"trigger\":" + Jsonl.quote(t.trim());
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
        return testRequest(dir, cache, jdksDir, workers, profile, verbose, offline, force, parallelTests, null);
    }

    /**
     * Start a single-project test run with suite/tag selection1136). {@code selection}
     * may be {@code null} (default suite only).
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
            boolean parallelTests,
            cc.jumpkick.config.TestSelection selection) {
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
                + testSelectionFields(selection)
                + triggerJsonSuffix()
                + progressModeJsonSuffix()
                + "}";
    }

    /** Encode suite/tag fields for {@link #TEST_REQUEST} (and siblings that carry the same shape). */
    public static String testSelectionFields(cc.jumpkick.config.TestSelection selection) {
        cc.jumpkick.config.TestSelection s = selection == null ? cc.jumpkick.config.TestSelection.DEFAULT : selection;
        StringBuilder sb = new StringBuilder();
        sb.append(",\"allSuites\":").append(s.allSuites());
        sb.append(",\"suites\":").append(jsonStringArray(s.suites()));
        sb.append(",\"includeTags\":").append(jsonStringArray(s.includeTags()));
        sb.append(",\"excludeTags\":").append(jsonStringArray(s.excludeTags()));
        sb.append(",\"tagsResolved\":").append(s.tagsResolved());
        return sb.toString();
    }

    /** Parse suite/tag selection from a test/build request line. */
    public static cc.jumpkick.config.TestSelection testSelectionOf(String json) {
        boolean all = Jsonl.bool(json, "allSuites", false);
        List<String> suites = stringArrayField(json, "suites");
        List<String> include = stringArrayField(json, "includeTags");
        List<String> exclude = stringArrayField(json, "excludeTags");
        boolean tagsResolved = Jsonl.bool(json, "tagsResolved", false);
        return cc.jumpkick.config.TestSelection.of(suites, all, include, exclude, tagsResolved);
    }

    private static String jsonStringArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Jsonl.quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Best-effort parse of a JSON string array field (flat list of quoted strings). */
    private static List<String> stringArrayField(String json, String key) {
        if (json == null) return List.of();
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return List.of();
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length() || json.charAt(start) != '[') return List.of();
        int end = json.indexOf(']', start);
        if (end < 0) return List.of();
        String body = json.substring(start + 1, end).trim();
        if (body.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            while (i < body.length() && (body.charAt(i) == ' ' || body.charAt(i) == ',')) i++;
            if (i >= body.length()) break;
            if (body.charAt(i) != '"') break;
            int j = i + 1;
            StringBuilder s = new StringBuilder();
            while (j < body.length()) {
                char c = body.charAt(j);
                if (c == '\\' && j + 1 < body.length()) {
                    s.append(body.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (c == '"') break;
                s.append(c);
                j++;
            }
            out.add(s.toString());
            i = j + 1;
        }
        return List.copyOf(out);
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
     * Resolve + write {@code jk-lock.toml} (see {@link #LOCK_REQUEST}). {@code repoUrl} may be {@code
     * null}. {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side
     * (the same fields {@link #buildRequest} carries). {@code conservative} marks an invisible
     * freshen: existing lock pins are kept as solver preferences instead of floating to latest.
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
            boolean verbose,
            boolean conservative) {
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
                + ",\"conservative\":"
                + conservative
                + "}";
    }

    /**
     * Re-resolve fresh and overwrite {@code jk-lock.toml} (see {@link #UPDATE_REQUEST}). {@code gitTarget}
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
        return updateRequest(
                dir, cache, features, noDefaultFeatures, repoUrl, gitOnly, gitTarget, offline, force, verbose, null);
    }

    /**
     * As {@link #updateRequest(String, String, List, boolean, String, boolean, String, boolean, boolean, boolean)}
     * with optional {@code platform} ({@code enforced}|{@code floor},. Null/blank =
     * project default.
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
            boolean verbose,
            String platform) {
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
                + ",\"platform\":"
                + Jsonl.quote(platform == null ? "" : platform)
                + "}";
    }

    /**
     * Sync the CAS + toolchain with {@code jk-lock.toml} (see {@link #SYNC_REQUEST}). {@code jdksDir}/
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
     * Format sources (see {@link #FORMAT_REQUEST}). Style names and hygiene toggles arrive already
     * resolved (flags + env + the {@code [format]} block are client-side concerns); {@code
     * rewriteConfig} may be {@code null}.
     */
    public static String formatRequest(
            String dir,
            String cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
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
                + ",\"importOrder\":"
                + importOrder
                + ",\"removeUnusedImports\":"
                + removeUnusedImports
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
     * Observe dynamic surface / optional AOT cache (see {@link #TRAIN_REQUEST}). {@code profile}
     * selects one {@code [[train.profile]]} or null for all; {@code graalHome} is the client-resolved
     * GraalVM home that provides the tracing agent (may be null — engine tries JAVA_HOME).
     */
    public static String trainRequest(
            String dir,
            String cache,
            String jdksDir,
            String graalHome,
            String profile,
            boolean force,
            boolean skipTests,
            boolean offline,
            boolean verbose) {
        return "{\"type\":\""
                + TRAIN_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"graalHome\":"
                + Jsonl.quote(graalHome)
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"force\":"
                + force
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
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
        return nativeRequest(
                dir, cache, jdksDir, mainClass, skipTests, offline, force, verbose, extraArgs, graalHomes, List.of());
    }

    /**
     * As {@link #nativeRequest(String, String, String, String, boolean, boolean, boolean, boolean,
     * List, Map)} with optional {@code moduleDirs}: when non-empty, the engine only cascades those
     * modules plus their build prereqs ({@code -m}/{@code --modules} selection).
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
            java.util.Map<String, String> graalHomes,
            List<String> moduleDirs) {
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
                + ",\"moduleDirs\":"
                + quoteArray(moduleDirs == null ? List.of() : moduleDirs)
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
        return explainRequest(dir, cache, workers, skipTests, profile, jdksDir, serial, parallelTests, verbose, false);
    }

    /**
     * As {@link #explainRequest(String, String, int, boolean, String, String, boolean, boolean, boolean)}
     * with {@code rebuild} — when true, forecast/ETA match {@code jk build --redo}.
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
            boolean verbose,
            boolean rebuild) {
        return explainRequest(
                dir,
                cache,
                workers,
                skipTests,
                profile,
                jdksDir,
                serial,
                parallelTests,
                verbose,
                rebuild,
                serial ? 1 : 0);
    }

    /**
     * As above with {@code maxModuleConcurrency} ({@code -j} / jobs) so explain and build clamp
     * schedule concurrency the same way.
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
            boolean verbose,
            boolean rebuild,
            int maxModuleConcurrency) {
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
                + ",\"rebuild\":"
                + rebuild
                + ",\"maxModuleConcurrency\":"
                + maxModuleConcurrency
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
                + EXPLAIN_TASK
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
        return "{\"type\":\"" + WHY_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + ",\"query\":" + Jsonl.quote(query)
                + "}";
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

    public static String freshenCatalogRequest(String catalog, boolean offline, String url, String cacheFile) {
        return "{\"type\":\"" + FRESHEN_CATALOG_REQUEST + "\",\"catalog\":" + Jsonl.quote(catalog)
                + ",\"offline\":" + offline
                + ",\"url\":" + Jsonl.quote(url)
                + ",\"cacheFile\":" + Jsonl.quote(cacheFile)
                + "}";
    }

    public static String freshenCatalogAck(boolean ok, String error) {
        return "{\"type\":\"" + FRESHEN_CATALOG_ACK + "\",\"ok\":" + ok + ",\"error\":" + Jsonl.quote(error) + "}";
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

    /** Outer invocation phase: {@code phase} wire name + {@code status} ({@code start}|{@code finish}). */
    public static String invocationPhase(String phase, String status) {
        return "{\""
                + TYPE_FIELD
                + "\":\""
                + INVOCATION_PHASE
                + "\",\"phase\":"
                + Jsonl.quote(phase == null ? "" : phase)
                + ",\"status\":"
                + Jsonl.quote(status == null ? "" : status)
                + "}";
    }

    public static String planModule(String dir, String coord, String planName, int weight, boolean fullyCached) {
        return "{\"type\":\""
                + PLAN_MODULE
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
                + PLAN_TASK
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
        return "{\"type\":\"" + PLAN_DONE + "\",\"count\":" + count + "}";
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
     * As {@link #eta(long)} with optional {@code fullMillis}: schedule-aware ETA for a full
     * rebuild of the same plan ({@code jk build --redo}). Used by {@code jk explain} as the
     * denominator for rebuild effort ({@code remaining / full}). Negative {@code fullMillis}
     * omits the field (non-explain ETA emitters).
     */
    public static String eta(long remainingMs, long fullMillis) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"type\":\"")
                .append(ETA)
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
     * {@code remainingMs}/{@code R0} mirror the ETA remaining-work oracle when seeded.
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
                + WORKSPACE_PROGRESS
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
        return "{\"type\":\"" + MODULE_START + "\",\"dir\":" + Jsonl.quote(dir) + "}";
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
                + BUILDPLAN_START
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
                + TASK_START
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
        return progressLike(PROGRESS, dir, step, delta, numerator, denominator, tasksTotal, tasksComplete, cancelled);
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
                TICK_UPDATE, dir, step, delta, numerator, denominator, tasksTotal, tasksComplete, cancelled);
    }

    public static String label(String dir, String step, String label) {
        return "{\"type\":\""
                + LABEL
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
                + OUTPUT
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
        if (stack != null && !stack.isEmpty()) {
            b.append(",\"stack\":").append(Jsonl.quote(stack));
            b.append(",\"throwable\":{")
                    .append("\"class\":")
                    .append(Jsonl.quote(exceptionClass == null ? "" : exceptionClass))
                    .append(",\"message\":")
                    .append(Jsonl.quote(message == null ? "" : message))
                    .append(",\"stack\":")
                    .append(Jsonl.quote(stack))
                    .append('}');
        } else if (exceptionClass != null && !exceptionClass.isEmpty()) {
            b.append(",\"throwable\":{")
                    .append("\"class\":")
                    .append(Jsonl.quote(exceptionClass))
                    .append(",\"message\":")
                    .append(Jsonl.quote(message == null ? "" : message))
                    .append(",\"stack\":\"\"}");
        }
        return b.append('}').toString();
    }

    public static String warn(String dir, String step, String code, String message) {
        return diagnosticLike(WARN, dir, step, code, message, "", "");
    }

    public static String errorLine(
            String dir, String step, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(ERROR_LINE, dir, step, code, message, test, exceptionClass);
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
                ERROR_LINE, dir, step, code, message, "", exceptionClass, module, engine, className, method, stack);
    }

    /** Full test-failure error line including optional source snippet. */
    public static String errorLine(
            String dir, String step, String code, String message, cc.jumpkick.run.TestFailureInfo failure) {
        if (failure == null) return errorLine(dir, step, code, message, "", "");
        return diagnosticLike(
                ERROR_LINE,
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
        return diagnosticLike(BUILDPLAN_DIAGNOSTIC, dir, step, code, message, test, exceptionClass);
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
                BUILDPLAN_DIAGNOSTIC,
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
                BUILDPLAN_DIAGNOSTIC,
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
                + TASK_FINISH
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
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
        return "{\"type\":\"" + LOCK_MODULE + "\",\"dir\":" + Jsonl.quote(dir) + ",\"coord\":" + Jsonl.quote(coord)
                + "}";
    }

    /** One resolved package, streamed as it is recorded (see {@link #LOCK_PACKAGE}). */
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
                .append(LOCK_PACKAGE)
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
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@code jk format} run's
     * counts and the formatter worker's exit code ({@code jk format --check} exits non-zero when
     * files need formatting — a legitimate outcome, not a plan failure, so it rides here rather
     * than failing the plan). {@code total} of 0 means no sources were found.
     */
    public static String planFinishFormat(
            String dir, boolean success, int changed, int clean, int errors, int total, int workerExit) {
        return "{\"type\":\""
                + BUILDPLAN_FINISH
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
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link #GIT_FETCH_REQUEST}'s
     * materialized checkout path and resolved commit sha ({@code null} when the fetch failed).
     */
    public static String planFinishGitFetch(String dir, boolean success, String checkout, String sha) {
        return "{\"type\":\""
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
                + BUILDPLAN_FINISH
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
                + WORKSPACE_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + quoteArray(errors)
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

    /** The one error envelope; see {@link #ERROR} for the code vocabulary. */
    public static String error(String code, String message) {
        return "{\"type\":\"" + ERROR + "\",\"code\":" + Jsonl.quote(code) + ",\"message\":" + Jsonl.quote(message)
                + "}";
    }

    /**
     * {@link #ERR_ALREADY_RUNNING}: same fingerprint already in flight. Includes {@code buildNumber}
     * and holder {@code requestId} when known so clients can render {@code Build #N is already running}.
     */
    public static String alreadyRunning(long buildNumber, long holderRequestId, String message) {
        return "{\"type\":\""
                + ERROR
                + "\",\"code\":"
                + Jsonl.quote(ERR_ALREADY_RUNNING)
                + ",\"message\":"
                + Jsonl.quote(message)
                + ",\"buildNumber\":"
                + buildNumber
                + ",\"requestId\":"
                + holderRequestId
                + ",\"jid\":"
                + holderRequestId
                + "}";
    }

    /** {@link #JOB_START}: job admitted — {@code jid} is the public cancel handle. */
    public static String jobStart(long jid, String kind, String dir, long buildNumber) {
        return jobStart(jid, kind, dir, buildNumber, null, -1);
    }

    /**
     * {@link #JOB_START} with details binding for the CLI session transcript.
     *
     * @param detailsPath absolute path to {@code runs/<buildNumber>/details.jsonl} (may be null)
     * @param etaMs estimated wall ms at admit (-1 omit)
     */
    public static String jobStart(long jid, String kind, String dir, long buildNumber, String detailsPath, long etaMs) {
        StringBuilder b = new StringBuilder("{\"type\":\"")
                .append(JOB_START)
                .append("\",\"jid\":")
                .append(jid)
                .append(",\"requestId\":")
                .append(jid)
                .append(",\"kind\":")
                .append(Jsonl.quote(kind == null ? "" : kind))
                .append(",\"dir\":")
                .append(Jsonl.quote(dir == null ? "" : dir));
        if (buildNumber > 0) b.append(",\"buildNumber\":").append(buildNumber);
        if (detailsPath != null && !detailsPath.isBlank()) {
            b.append(",\"detailsPath\":").append(Jsonl.quote(detailsPath));
        }
        if (etaMs >= 0) b.append(",\"etaMs\":").append(etaMs);
        return b.append('}').toString();
    }

    /** {@link #CANCEL_REQUEST}: cancel by {@code jid} (optional {@code dir} to cancel all for a project). */
    public static String cancelRequest(long jid) {
        return "{\"type\":\"" + CANCEL_REQUEST + "\",\"jid\":" + jid + ",\"requestId\":" + jid + "}";
    }

    /** {@link #CANCEL_REQUEST} with no jid: cancel every live job under {@code dir}. */
    public static String cancelRequestForDir(String dir) {
        return "{\"type\":\"" + CANCEL_REQUEST + "\",\"dir\":" + Jsonl.quote(dir == null ? "" : dir) + "}";
    }

    /** {@link #CANCEL_ACK}. */
    public static String cancelAck(long jid, boolean cancelled, String note) {
        StringBuilder b = new StringBuilder("{\"type\":\"")
                .append(CANCEL_ACK)
                .append("\",\"jid\":")
                .append(jid)
                .append(",\"requestId\":")
                .append(jid)
                .append(",\"cancelled\":")
                .append(cancelled);
        if (note != null && !note.isBlank()) b.append(",\"note\":").append(Jsonl.quote(note));
        return b.append('}').toString();
    }

    /** {@code error} with {@link #ERR_REQUEST_FAILED} — the former build-error catch-all. */
    public static String requestFailed(String message) {
        return error(ERR_REQUEST_FAILED, message);
    }

    /** Keep-alive line during long jobs. */
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
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link #TOOL_RESOLVE_REQUEST}
     * result: the pinned {@code g:a:v} the resolve landed on (a floating spec's concrete version is
     * decided engine-side against maven-metadata), the resolved {@code Main-Class}, and the
     * transitive classpath in resolution order (absolute CAS paths — a flat string array, per the
     * codec's no-nested-objects rule). All {@code null}/empty when the resolve failed.
     */
    public static String planFinishTool(
            String dir, boolean success, String coord, String mainClass, List<String> classpath) {
        return "{\"type\":\""
                + BUILDPLAN_FINISH
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
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link
     * #SCRIPT_PREPARE_REQUEST} result: the exec ingredients the client-side launch needs. Fields
     * not applicable to the prepared mode (and everything on failure) are {@code null}/empty.
     */
    public static String planFinishScript(
            String dir,
            boolean success,
            String mainClass,
            List<String> classpath,
            String classesDir,
            String kotlincBin,
            String stdlib) {
        return "{\"type\":\""
                + BUILDPLAN_FINISH
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
     * prune}/{@code purge}/{@code gc}/{@code sweep}; {@code olderThanDays}/{@code sweep}/
     * {@code dropAllClassC} apply to {@code prune} only; {@code includeJkTmp} asks the prune to also
     * sweep {@code state/tmp} (only when the default cache dir is in use).
     */
    public static String cachePruneRequest(
            String op, String cache, int olderThanDays, boolean dryRun, boolean sweep, boolean includeJkTmp) {
        return cachePruneRequest(op, cache, olderThanDays, dryRun, sweep, includeJkTmp, false);
    }

    /** @param dropAllClassC when true with {@code op=prune}, delete every Class-C action key */
    public static String cachePruneRequest(
            String op,
            String cache,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            boolean includeJkTmp,
            boolean dropAllClassC) {
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
                + ",\"includeJkTmp\":"
                + includeJkTmp
                + ",\"dropAllClassC\":"
                + dropAllClassC
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
    public static String pruneWait(int plans, boolean external) {
        return "{\"type\":\"" + PRUNE_WAIT + "\",\"plans\":" + plans + ",\"external\":" + external + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link #CACHE_PRUNE_REQUEST}
     * summary: files removed + bytes freed (what would be removed, on a dry run), the LRU evictor's
     * reachable-eviction count ({@code prune --max-size} only), and the repo-mirror links removed
     * ({@code gc} only). {@code -1} = not applicable to the op.
     */
    public static String planFinishCache(
            String dir, boolean success, long files, long bytes, long reachableEvicted, long repoLinks) {
        return "{\"type\":\""
                + BUILDPLAN_FINISH
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
     * at worker-fork time; only {@code --ram-percent}/{@code --jvm-arg} and {@code JK_JVM_*}
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
     * Session envelope including optional {@code assemblyOverride} ({@code fat} / {@code minified}) for
     * {@code jk assemble --minified} one-offs.
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

    /** Decode {@code assemblyOverride} from a session envelope ({@code fat}/{@code minified}/empty). */
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
