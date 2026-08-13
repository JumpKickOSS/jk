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
import org.jspecify.annotations.Nullable;

/** Engine services a hosted verb may not own. */
public interface VerbHost {

    long eventRequestId();

    void putProgressRoot(long rid, String dir);

    WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir);

    BuildPlanListener planListener(String dir, BufferedWriter writer, BuildPlan plan);

    void releaseExclusiveSlot();

    boolean effectiveCancelled(long rid, boolean tokenCancelled);

    void accOutcome(long rid, boolean success, int exit);

    void accTests(long rid, @Nullable TestSummary tests);

    void finishProgress(long rid);

    void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force);

    void flushTimeline(long rid, BufferedWriter writer);

    void send(BufferedWriter writer, String line) throws IOException;

    void sendQuiet(BufferedWriter writer, String line);

    String redactEnv(@Nullable String dir, @Nullable String text);

    String requestFailedLine(@Nullable String dir, Throwable e);

    void publishRequestError(long rid, @Nullable String dir, String message);

    Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh);

    void maybeEnqueuePrune(Path cache);
}
