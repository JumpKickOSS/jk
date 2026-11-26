// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Redacted;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.BufferedWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one place a {@code workspace-finish} leaves the engine.
 *
 * <p>Four verbs drive a workspace build — {@code build}, {@code native}, {@code image} and a
 * workspace-member {@code compile} — and every one closes it here. Separate epilogues drift where
 * it matters: which verb masks {@code .env} secrets out of the worker error rows, which publishes
 * them to the dashboard. One emitter, one behaviour.
 */
final class WorkspaceTerminal {

    private WorkspaceTerminal() {}

    /** How many error rows a failed build publishes to the SSE hub before they are just noise. */
    private static final int PUBLISHED_ERROR_ROWS = 5;

    /**
     * Settle a workspace build: release the exclusive slot, decide cancelled-vs-failed, flush
     * progress and the timeline, journal and emit the terminal's error rows, and hand back the
     * verdict the build already produced.
     *
     * <p>The terminal goes out with {@link VerbHost#sendQuiet}, never the throwing
     * {@link VerbHost#send}. A client can hang up in the window between the last progress event
     * and this line — a closed terminal window, {@code jk build | head}, a CLI Ctrl-C'd after it
     * had already read the result — and a broken pipe there is not a build result. With
     * the throwing form the {@code IOException} unwound past the caller's {@code return} into its
     * catch, which fabricated {@code failed(1)}: a green build was journaled as a failure and
     * published a spurious {@code request-error}. Nothing that happens to the socket after the
     * build has ruled may change what is journaled.
     *
     * <p>The error rows and the lock's conflict are {@link Redacted} the whole way down, because
     * {@link ProtoEvents#workspaceFinish} and {@link VerbHost#publishRequestError} are the wire,
     * SSE and journal sinks for raw worker output.
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
        boolean succeeded = result.success() && !cancelled;
        // A cancelled build exits with the engine's cancel code, never with the exit of work that
        // never ran: a green-so-far run stopped halfway carries exitCode 0, and "did not succeed,
        // exit 0" is a row no reader can act on.
        int exitCode = succeeded ? Exit.SUCCESS : (cancelled ? Exit.FAILURE : result.exitCode());
        JobOutcome outcome =
                cancelled ? JobOutcome.cancelled() : (succeeded ? JobOutcome.ok() : JobOutcome.failed(exitCode));
        if (rid > 0) {
            if (succeeded) host.finishProgress(rid);
            host.emitWorkspaceProgress(rid, writer, true);
        }
        host.flushTimeline(rid, writer, !succeeded || result.modules().stream().anyMatch(ModuleOutcome::didWork));

        if (rid > 0) {
            for (ModuleOutcome module : result.modules()) {
                ModuleOutcome.Published p = module.published();
                if (p == null) continue;
                host.accPublish(
                        rid,
                        new BuildRecord.Publish(
                                List.of(new BuildRecord.Publish.Lane(
                                        PublishLane.LIBRARY.word(), p.destination(), p.files(), p.dryRun(), "")),
                                p.deploymentId(),
                                p.deploymentState(),
                                p.deploymentErrors(),
                                p.bundle(),
                                p.bundleBytes(),
                                List.of()));
            }
        }
        ReleaseOutcome release = result.release();
        if (rid > 0 && release != null) host.accPublish(rid, releaseRow(release));
        List<Redacted> errors = host.redactErrors(dir, result.errors());
        if (!succeeded && !cancelled && rid > 0) {
            host.accRunErrors(rid, errors.stream().map(Redacted::text).toList());
        }
        @Nullable
        Redacted conflict = result.conflict().isEmpty()
                ? null
                : host.redactErrors(dir, List.of(result.conflict())).getFirst();
        host.sendQuiet(
                writer,
                ProtoEvents.workspaceFinish(
                        succeeded,
                        exitCode,
                        errors,
                        cancelled,
                        result.sync(),
                        succeeded && result.upToDate(),
                        conflict,
                        result.release()));
        if (!succeeded && !cancelled) {
            for (Redacted error : errors.stream().limit(PUBLISHED_ERROR_ROWS).toList()) {
                host.publishRequestError(rid, dir, error.text());
            }
        }
        return outcome;
    }

    /** The release lane's row of the results file: where, how many files this run sent, and how it ended. */
    static BuildRecord.Publish releaseRow(ReleaseOutcome release) {
        int sent = (int) release.files().stream()
                .filter(f -> ReleaseOutcome.UPLOADED.equals(f.status()) || ReleaseOutcome.PLANNED.equals(f.status()))
                .count();
        String state =
                release.stage() ? "staged" : release.latest() != null ? "latest.json names " + release.latest() : "";
        return new BuildRecord.Publish(
                List.of(new BuildRecord.Publish.Lane(
                        PublishLane.RELEASE.word(), release.destination(), sent, release.dryRun(), state)),
                null,
                null,
                List.of(),
                List.of(),
                0L,
                release.notes());
    }
}
