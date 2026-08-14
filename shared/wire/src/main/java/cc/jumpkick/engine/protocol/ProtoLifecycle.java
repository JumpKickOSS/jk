// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;

/** Lifecycle + job-admit JSONL: hello, status, shutdown, cancel, errors. */
public final class ProtoLifecycle {

    private ProtoLifecycle() {}

    public static String typeOf(String json) {
        return Jsonl.str(json, EngineProtocol.TYPE_FIELD);
    }

    public static String auth(String token) {
        return "{\"type\":\"" + EngineProtocol.AUTH + "\",\"token\":" + Jsonl.quote(token) + "}";
    }

    public static String hello(String version) {
        return hello(version, "connect");
    }

    /** {@code purpose} is {@code connect} (working channel) or {@code probe} (liveness/version). */
    public static String hello(String version, String purpose) {
        return "{\"type\":\"" + EngineProtocol.HELLO + "\",\"version\":" + Jsonl.quote(version)
                + ",\"proto\":" + EngineProtocol.PROTOCOL
                + ",\"purpose\":" + Jsonl.quote(purpose) + "}";
    }

    /**
     * {@code buildId} is the engine's content identity ({@code BuildIdentity}): empty for
     * releases and identity-less contexts; a jar sha prefix for -SNAPSHOT dev builds, so a
     * REBUILT dev engine is distinguishable from a stale one under the same version string.
     */
    public static String helloAck(String version, long pid, long startedAtMillis, boolean draining, String buildId) {
        return "{\"type\":\""
                + EngineProtocol.HELLO_ACK
                + "\",\"version\":"
                + Jsonl.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + ",\"proto\":"
                + EngineProtocol.PROTOCOL
                + ",\"draining\":"
                + draining
                + ",\"buildId\":"
                + Jsonl.quote(buildId == null ? "" : buildId)
                + "}";
    }

    public static String ping() {
        return "{\"type\":\"" + EngineProtocol.PING + "\"}";
    }

    public static String pong() {
        return "{\"type\":\"" + EngineProtocol.PONG + "\"}";
    }

    public static String statusRequest() {
        return "{\"type\":\"" + EngineProtocol.STATUS + "\"}";
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
                .append(EngineProtocol.CALIBRATE_REQUEST)
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
        return "{\"type\":\"" + EngineProtocol.OPTIMIZE_REQUEST + "\",\"force\":" + force + "}";
    }

    /**
     * Optimize result. {@code summary} is multi-line human text; {@code trained}/{@code skipped}
     * are comma-separated tool tags for machine consumers.
     */
    public static String optimizeAck(boolean ok, String trained, String skipped, String summary) {
        return "{\"type\":\""
                + EngineProtocol.OPTIMIZE_ACK
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
                + EngineProtocol.CALIBRATE_ACK
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
                + EngineProtocol.STATUS_ACK
                + "\",\"version\":"
                + Jsonl.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + ",\"proto\":"
                + EngineProtocol.PROTOCOL
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
        return "{\"type\":\"" + EngineProtocol.SHUTDOWN + "\",\"force\":" + force + "}";
    }

    public static String bye() {
        return bye(0, false);
    }

    /** Ack for {@link EngineProtocol#SHUTDOWN}: reports the in-flight job count and whether a drain is now underway. */
    public static String bye(int plans, boolean draining) {
        return "{\"type\":\"" + EngineProtocol.BYE + "\",\"plans\":" + plans + ",\"draining\":" + draining + "}";
    }

    // ---- build-request (client → server) -------------------------------------------------------

    /**
     * Start a workspace build. {@code force} implies refresh; {@code rerun} bypasses action cache
     * only. {@code freshenLock} auto-refreshes a stale workspace lock ({@code jk verify} sends
     * false). Engine forecasts dirty modules itself — no client {@code dirtyHint}.
     *
     * /** The one error envelope; see {@link EngineProtocol#ERROR} for the code vocabulary. */
    public static String error(String code, String message) {
        return "{\"type\":\"" + EngineProtocol.ERROR + "\",\"code\":" + Jsonl.quote(code) + ",\"message\":"
                + Jsonl.quote(message) + "}";
    }

    /**
     * {@link EngineProtocol#ERR_ALREADY_RUNNING}: same fingerprint already in flight. Includes {@code buildNumber}
     * and holder {@code requestId} when known so clients can render {@code Build #N is already running}.
     */
    public static String alreadyRunning(long buildNumber, long holderRequestId, String message) {
        return "{\"type\":\""
                + EngineProtocol.ERROR
                + "\",\"code\":"
                + Jsonl.quote(EngineProtocol.ERR_ALREADY_RUNNING)
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

    /** {@link EngineProtocol#JOB_START}: job admitted — {@code jid} is the public cancel handle. */
    public static String jobStart(long jid, String kind, String dir, long buildNumber) {
        return jobStart(jid, kind, dir, buildNumber, null, -1);
    }

    /**
     * {@link EngineProtocol#JOB_START} with details binding for the CLI session transcript.
     *
     * @param detailsPath absolute path to {@code runs/<buildNumber>/details.jsonl} (may be null)
     * @param etaMs estimated wall ms at admit (-1 omit)
     */
    public static String jobStart(long jid, String kind, String dir, long buildNumber, String detailsPath, long etaMs) {
        StringBuilder b = new StringBuilder("{\"type\":\"")
                .append(EngineProtocol.JOB_START)
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

    /** {@link EngineProtocol#CANCEL_REQUEST}: cancel by {@code jid} (optional {@code dir} to cancel all for a project). */
    public static String cancelRequest(long jid) {
        return "{\"type\":\"" + EngineProtocol.CANCEL_REQUEST + "\",\"jid\":" + jid + ",\"requestId\":" + jid + "}";
    }

    /** {@link EngineProtocol#CANCEL_REQUEST} with no jid: cancel every live job under {@code dir}. */
    public static String cancelRequestForDir(String dir) {
        return "{\"type\":\"" + EngineProtocol.CANCEL_REQUEST + "\",\"dir\":" + Jsonl.quote(dir == null ? "" : dir)
                + "}";
    }

    /** {@link EngineProtocol#CANCEL_ACK}. */
    public static String cancelAck(long jid, boolean cancelled, String note) {
        StringBuilder b = new StringBuilder("{\"type\":\"")
                .append(EngineProtocol.CANCEL_ACK)
                .append("\",\"jid\":")
                .append(jid)
                .append(",\"requestId\":")
                .append(jid)
                .append(",\"cancelled\":")
                .append(cancelled);
        if (note != null && !note.isBlank()) b.append(",\"note\":").append(Jsonl.quote(note));
        return b.append('}').toString();
    }

    /** {@code error} with {@link EngineProtocol#ERR_REQUEST_FAILED} — the former build-error catch-all. */
    public static String requestFailed(String message) {
        return error(EngineProtocol.ERR_REQUEST_FAILED, message);
    }

    /** Keep-alive line during long jobs. */
    public static String heartbeat(long elapsedMillis) {
        return "{\"type\":\"" + EngineProtocol.HEARTBEAT + "\",\"elapsedMillis\":" + elapsedMillis + "}";
    }
}
