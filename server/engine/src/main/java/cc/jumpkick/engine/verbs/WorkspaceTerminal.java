// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Redacted;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one place a {@code workspace-finish} leaves the engine.
 *
 * <p>Four verbs drive a workspace build — {@code build}, {@code native}, {@code image} and a
 * workspace-member {@code compile} — and each used to close it with its own copy of the same
 * epilogue. The copies drifted where it mattered: only {@code build} masked {@code .env} secrets
 * out of the worker error rows, and only {@code build} published them to the dashboard
 * (JK-2387). One emitter, one behaviour.
 */
final class WorkspaceTerminal {

    private WorkspaceTerminal() {}

    /** How many error rows a failed build publishes to the SSE hub before they are just noise. */
    private static final int PUBLISHED_ERROR_ROWS = 5;

    /**
     * Settle a workspace build: release the exclusive slot, decide cancelled-vs-failed, flush
     * progress and the timeline, emit the terminal, publish the error rows, and hand back the
     * verdict the build already produced.
     *
     * <p>The terminal goes out with {@link VerbHost#sendQuiet}, never the throwing
     * {@link VerbHost#send}. A client can hang up in the window between the last progress event
     * and this line — a closed terminal window, {@code jk build | head}, a CLI Ctrl-C'd after it
     * had already read the result — and a broken pipe there is not a build result (JK-1521). With
     * the throwing form the {@code IOException} unwound past the caller's {@code return} into its
     * catch, which fabricated {@code failed(1)}: a green build was journaled as a failure and
     * published a spurious {@code request-error}. Nothing that happens to the socket after the
     * build has ruled may change what is journaled.
     *
     * <p>The error rows are {@link Redacted} the whole way down, because
     * {@link ProtoEvents#workspaceFinish} and {@link VerbHost#publishRequestError} are the wire,
     * SSE and journal sinks for raw worker output (JK-2387).
     *
     * @param dir the progress root this request was registered under
     * @param tokenCancelled the request's cancel token, OR-ed with what the build itself observed
     */
    static JobOutcome finish(
            VerbHost host,
            @Nullable BufferedWriter writer,
            String dir,
            WorkspaceResult result,
            boolean tokenCancelled) {
        long rid = host.eventRequestId();
        host.releaseExclusiveSlot();
        boolean cancelled = result.cancelled() || host.effectiveCancelled(rid, tokenCancelled);
        JobOutcome outcome = JobOutcome.of(result.success() && !cancelled, result.exitCode());
        if (rid > 0) {
            if (outcome.success()) host.finishProgress(rid);
            host.emitWorkspaceProgress(rid, writer, true);
        }
        host.flushTimeline(rid, writer);

        List<Redacted> errors = host.redactErrors(dir, result.errors());
        host.sendQuiet(writer, ProtoEvents.workspaceFinish(outcome.success(), outcome.exitCode(), errors, cancelled));
        if (!outcome.success() && !cancelled) {
            for (Redacted error : errors.stream().limit(PUBLISHED_ERROR_ROWS).toList()) {
                host.publishRequestError(rid, dir, error.text());
            }
        }
        return outcome;
    }
}
