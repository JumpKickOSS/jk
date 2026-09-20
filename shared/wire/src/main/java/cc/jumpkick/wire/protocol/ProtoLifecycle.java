// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Lifecycle + job-admit JSONL: hello, status, shutdown, cancel, errors. Each line is a record in
 * this package with its own {@code encode}/{@code decode}; the factories here are the callers'
 * spelling of them.
 */
public final class ProtoLifecycle {

    private ProtoLifecycle() {}

    public static @Nullable String typeOf(String json) {
        return Jsonl.str(json, EngineProtocol.TYPE_FIELD);
    }

    public static String auth(String token) {
        return new AuthFrame(token).encode();
    }

    public static String hello(String version) {
        return hello(version, "connect");
    }

    /** {@code purpose} is {@code connect} (working channel) or {@code probe} (liveness/version). */
    public static String hello(String version, String purpose) {
        return new HelloFrame(version, purpose).encode();
    }

    /**
     * {@code buildId} is the engine's content identity ({@code BuildIdentity}): empty for
     * releases and identity-less contexts; a jar sha prefix for -SNAPSHOT dev builds, so a
     * REBUILT dev engine is distinguishable from a stale one under the same version string.
     */
    public static String helloAck(
            String version, long pid, long startedAtMillis, boolean draining, @Nullable String buildId) {
        return new HelloAckFrame(version, pid, startedAtMillis, draining, buildId).encode();
    }

    public static String ping() {
        return new PingFrame().encode();
    }

    public static String pong() {
        return new PongFrame().encode();
    }

    public static String statusRequest() {
        return new StatusRequestFrame().encode();
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
        return new CalibrateRequestFrame(force, engineColdStartMs, allowNetwork).encode();
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
        return new CalibrateAckFrame(
                        ok,
                        msPerWeight,
                        jvmForkMs,
                        javacMs,
                        diskIoMs,
                        hashCpuMs,
                        junitForkMs,
                        junitRunMs,
                        junitPlatformMs,
                        resolveMs,
                        engineColdStartMs,
                        measured,
                        junitPlatformUsed,
                        resolveUsed,
                        summary)
                .encode();
    }

    /**
     * Ack for {@link EngineProtocol#STATUS}: the engine's vitals — the same ordered map the REST,
     * SSE and MCP surfaces render, so a vital added to the snapshot reaches this socket the same
     * day — followed by the socket-only facts: {@code proto}, {@code draining}, the HTTP URL or the
     * bind error, and the MCP endpoint derived from the HTTP URL when MCP is enabled. Vitals are
     * carried as a map because this class cannot see the engine's snapshot type; the per-field
     * parameter list this replaced is what let six of them go missing here. Not a record: the
     * vitals' value types are the snapshot's, which no fixed field list here should re-declare.
     */
    public static String statusAck(
            Map<String, Object> vitals,
            boolean draining,
            @Nullable String httpUrl,
            @Nullable String httpError,
            boolean mcpEnabled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(EngineProtocol.TYPE_FIELD, EngineProtocol.STATUS_ACK);
        m.putAll(vitals);
        m.put("proto", EngineProtocol.PROTOCOL);
        m.put("draining", draining);
        m.put("httpUrl", httpUrl);
        m.put("httpError", httpError);
        m.put("mcpUrl", mcpEnabled ? mcpUrlFromHttp(httpUrl) : null);
        return MiniJson.write(m);
    }

    /**
     * Derive MCP endpoint URL from the HTTP base. Strips trailing slashes so {@code
     * http://host:port/} becomes {@code http://host:port/mcp}, never {@code //mcp}.
     */
    static @Nullable String mcpUrlFromHttp(@Nullable String httpUrl) {
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
        return new ShutdownFrame(force).encode();
    }

    public static String bye() {
        return bye(0, false);
    }

    /** Ack for {@link EngineProtocol#SHUTDOWN}: reports the in-flight job count and whether a drain is now underway. */
    public static String bye(int plans, boolean draining) {
        return new ByeFrame(plans, draining).encode();
    }

    /**
     * Predecessor → successor: in-flight job count after this engine yielded its listeners.
     * The successor is already bound; this is status, not a request for work.
     */
    public static String drainStatus(long pid, int plans, String version) {
        return new DrainStatusFrame(pid, plans, version).encode();
    }

    /** Predecessor → successor: no in-flight jobs remain; this process is exiting. */
    public static String drainDone(long pid) {
        return new DrainDoneFrame(pid).encode();
    }

    /** The one error envelope; see {@link EngineProtocol#ERROR} for the code vocabulary. */
    public static String error(String code, String message) {
        return new ErrorFrame(code, message).encode();
    }

    /**
     * {@link EngineProtocol#ERR_ALREADY_RUNNING}: same fingerprint already in flight. Includes {@code buildNumber}
     * and holder {@code jid} when known so clients can render {@code Build #N is already running}.
     */
    public static String alreadyRunning(long buildNumber, long holderRequestId, String message) {
        return new AlreadyRunningFrame(buildNumber, holderRequestId, message).encode();
    }

    /** {@link EngineProtocol#JOB_START}: job admitted — {@code jid} is the public cancel handle. */
    /**
     * A {@link EngineProtocol#JOB_QUEUED} line: the job waits for memory behind {@code ahead} jobs,
     * has waited {@code waitedMs} so far, and {@code live} holds the heap it waits for.
     */
    public static String jobQueued(long jid, int ahead, long waitedMs, List<JobQueuedFrame.Live> live) {
        return new JobQueuedFrame(jid, ahead, JobQueuedFrame.MEMORY, waitedMs, live).encode();
    }

    public static String jobStart(long jid, String kind, String dir, long buildNumber) {
        return jobStart(jid, kind, dir, buildNumber, null, -1);
    }

    /**
     * {@link EngineProtocol#JOB_START} with details binding for the CLI session transcript.
     *
     * @param detailsPath absolute path to {@code runs/<buildNumber>/details.jsonl} (may be null)
     * @param etaMs estimated wall ms at admit (-1 omit)
     */
    public static String jobStart(
            long jid,
            @Nullable String kind,
            @Nullable String dir,
            long buildNumber,
            @Nullable String detailsPath,
            long etaMs) {
        return new JobStartFrame(jid, kind, dir, buildNumber, detailsPath, etaMs).encode();
    }

    /**
     * {@link EngineProtocol#JOB_FINISH}: the job is over and the engine has written everything it
     * is going to write under the project's {@code target/}.
     */
    public static String jobFinish(long jid) {
        return new JobFinishFrame(jid).encode();
    }

    /** {@link EngineProtocol#CANCEL_REQUEST}: cancel by {@code jid} (optional {@code dir} to cancel all for a project). */
    public static String cancelRequest(long jid) {
        return new CancelRequestFrame(jid).encode();
    }

    /** {@link EngineProtocol#CANCEL_REQUEST} with no jid: cancel every live job under {@code dir}. */
    public static String cancelRequestForDir(String dir) {
        return new CancelDirRequestFrame(dir).encode();
    }

    /** {@link EngineProtocol#CANCEL_ACK}. */
    public static String cancelAck(long jid, boolean cancelled, @Nullable String note) {
        return new CancelAckFrame(jid, cancelled, note).encode();
    }

    /** {@code error} with {@link EngineProtocol#ERR_REQUEST_FAILED} — the former build-error catch-all. */
    public static String requestFailed(String message) {
        return error(EngineProtocol.ERR_REQUEST_FAILED, message);
    }

    /** Keep-alive line during long jobs. */
    public static String heartbeat(long elapsedMillis) {
        return new HeartbeatFrame(elapsedMillis).encode();
    }
}
