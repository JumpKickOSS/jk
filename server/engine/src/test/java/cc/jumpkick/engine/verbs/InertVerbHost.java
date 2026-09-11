// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A {@link VerbHost} for tests that only decode: registry construction and a verb's constructor
 * need a host reference, and nothing on this one is ever called.
 */
final class InertVerbHost implements VerbHost {
    @Override
    public long eventRequestId() {
        return -1;
    }

    @Override
    public void putProgressRoot(long rid, String dir) {}

    @Override
    public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter w, String dir) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BuildPlanListener planListener(String dir, @Nullable BufferedWriter w, BuildPlan plan) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BuildPlanListener planListener(
            String dir, @Nullable BufferedWriter w, @Nullable Function<BuildPlanResult, String> enc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void releaseExclusiveSlot() {}

    @Override
    public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
        return false;
    }

    @Override
    public void accTests(long rid, @Nullable TestSummary tests) {}

    @Override
    public void finishProgress(long rid) {}

    @Override
    public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter w, boolean force) {}

    @Override
    public void flushTimeline(long rid, @Nullable BufferedWriter w) {}

    @Override
    public void send(@Nullable BufferedWriter w, String line) {}

    @Override
    public void sendQuiet(@Nullable BufferedWriter w, String line) {}

    @Override
    public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
        return text;
    }

    @Override
    public String requestFailedLine(@Nullable String dir, Throwable e) {
        return "";
    }

    @Override
    public void publishRequestError(long rid, @Nullable String dir, String message) {}

    @Override
    public void maybeEnqueuePrune(Path cache) {}
}
