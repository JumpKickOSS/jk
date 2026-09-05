// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.Redacted;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Engine services a hosted verb may not own. Every {@code writer} parameter is {@code null} for a
 * detached (HTTP/MCP) job: send/sendQuiet become no-ops and the listener factories return the
 * hub-only (SSE + journal) listeners.
 */
public interface VerbHost {

    long eventRequestId();

    void putProgressRoot(long rid, String dir);

    WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter writer, String dir);

    BuildPlanListener planListener(String dir, @Nullable BufferedWriter writer, BuildPlan plan);

    BuildPlanListener planListener(
            String dir, @Nullable BufferedWriter writer, @Nullable Function<BuildPlanResult, String> finishEncoder);

    void releaseExclusiveSlot();

    boolean effectiveCancelled(long rid, boolean tokenCancelled);

    void accTests(long rid, @Nullable TestSummary tests);

    /** One plan's {@code --affected} ranking slice; merged per request, written at request-finish. */
    default void accAffected(long rid, cc.jumpkick.test.@Nullable AffectedTests affected) {}

    void finishProgress(long rid);

    void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force);

    void flushTimeline(long rid, @Nullable BufferedWriter writer);

    /**
     * Write one line and let a dead client's {@code IOException} out.
     *
     * <p>Only a verb that always declines to rule may use this — a read-only query
     * ({@code jk history}, {@code jk metrics}) whose stream is the whole product, where an aborted
     * write costs nothing. A verb that returns a verdict must use {@link #sendQuiet} for every
     * line it writes after computing that verdict: the envelope stamps it on the build journal,
     * and a throw out of the terminal write unwinds past the {@code return} into the verb's own
     * catch, where the real verdict is replaced by a fabricated failure. A client that hung up is
     * not a build result.
     */
    void send(@Nullable BufferedWriter writer, String line) throws IOException;

    /**
     * As {@link #send}, but a dead client is dropped rather than thrown: the cancel-watching read
     * loop sees the same disconnect. This is the form a journaled verb uses.
     */
    void sendQuiet(@Nullable BufferedWriter writer, String line);

    @Nullable
    String redactEnv(@Nullable String dir, @Nullable String text);

    /**
     * The workspace terminal's error rows, masked, in the only shape
     * {@link cc.jumpkick.wire.protocol.ProtoEvents#workspaceFinish} accepts.
     *
     * <p>The second rule a journaled verb lives by, next to {@link #sendQuiet}: worker error text
     * is raw process output, {@code .env} values are secret by source, and the terminal reaches
     * the user's terminal, the dashboard and the journal. Three of the four emitters used to skip
     * the masking call because nothing in {@code List<String>} said they had to. The
     * event now takes {@link Redacted}, whose only mint is
     * {@link cc.jumpkick.config.SecretRedactor#redactAll} — so a fifth verb that forgets does not
     * compile. Redaction is {@code .env}-scoped; forge and repository tokens stay out.
     *
     * <p>One redactor is built for the whole list: constructing one walks for a workspace root and
     * parses {@code .env}.
     */
    default List<Redacted> redactErrors(@Nullable String dir, List<String> errors) {
        return EventRedaction.redactErrors(dir, errors);
    }

    String requestFailedLine(@Nullable String dir, Throwable e);

    default String requestFailedLine(@Nullable String dir, String message) {
        return ProtoLifecycle.requestFailed(EventRedaction.redactText(dir, message));
    }

    void publishRequestError(long rid, @Nullable String dir, String message);

    Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh);

    void maybeEnqueuePrune(Path cache);

    /**
     * Announce a single plan's steps, run it, and hand back the verdict it produced — the shape
     * every single-plan verb settles with, so none of them has to re-derive success from a
     * {@link BuildPlanResult} it just discarded.
     */
    default JobOutcome streamSinglePlan(
            BuildPlan plan,
            Session session,
            @Nullable BufferedWriter writer,
            Function<BuildPlanResult, String> finishEncoder)
            throws Exception {
        return PlanBurst.stream(this, plan, session, writer, finishEncoder);
    }

    default ReentrantReadWriteLock cacheGate() {
        throw new UnsupportedOperationException("cacheGate");
    }

    default int activePlanCount() {
        return 0;
    }

    default long nowMillis() {
        return System.currentTimeMillis();
    }

    default boolean scheduleHostWarmup(boolean force) {
        return false;
    }

    default BuildJournal journal() {
        throw new UnsupportedOperationException("journal");
    }

    default JkHistoryConfig historyConfig() {
        throw new UnsupportedOperationException("historyConfig");
    }

    default Path metricsFile() {
        throw new UnsupportedOperationException("metricsFile");
    }

    default InFlightBuilds inFlightBuilds() {
        throw new UnsupportedOperationException("inFlightBuilds");
    }

    default @Nullable Double lastProgress(long requestId) {
        return null;
    }
}
