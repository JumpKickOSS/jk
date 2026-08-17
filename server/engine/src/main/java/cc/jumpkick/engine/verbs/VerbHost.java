// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
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
            String dir,
            @Nullable BufferedWriter writer,
            Function<cc.jumpkick.run.BuildPlanResult, String> finishEncoder);

    void releaseExclusiveSlot();

    boolean effectiveCancelled(long rid, boolean tokenCancelled);

    void accTests(long rid, @Nullable TestSummary tests);

    void finishProgress(long rid);

    void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force);

    void flushTimeline(long rid, @Nullable BufferedWriter writer);

    void send(@Nullable BufferedWriter writer, String line) throws IOException;

    void sendQuiet(@Nullable BufferedWriter writer, String line);

    String redactEnv(@Nullable String dir, @Nullable String text);

    String requestFailedLine(@Nullable String dir, Throwable e);

    default String requestFailedLine(@Nullable String dir, String message) {
        return cc.jumpkick.engine.protocol.ProtoLifecycle.requestFailed(redactEnv(dir, message));
    }

    void publishRequestError(long rid, @Nullable String dir, String message);

    Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh);

    void maybeEnqueuePrune(Path cache);

    default void streamSinglePlan(
            BuildPlan plan,
            Session session,
            @Nullable BufferedWriter writer,
            Function<cc.jumpkick.run.BuildPlanResult, String> finishEncoder)
            throws Exception {
        PlanBurst.stream(this, plan, session, writer, finishEncoder);
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

    default cc.jumpkick.engine.journal.BuildJournal journal() {
        throw new UnsupportedOperationException("journal");
    }

    default cc.jumpkick.config.JkHistoryConfig historyConfig() {
        throw new UnsupportedOperationException("historyConfig");
    }

    default Path metricsFile() {
        throw new UnsupportedOperationException("metricsFile");
    }

    default cc.jumpkick.engine.InFlightBuilds inFlightBuilds() {
        throw new UnsupportedOperationException("inFlightBuilds");
    }

    default @Nullable Double lastProgress(long requestId) {
        return null;
    }
}
