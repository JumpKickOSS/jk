// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.protocol.PluginInstallLocalAck;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Ops/decode failures must ride the ack's error channel like every sibling read verb —
 * a bare error line is skipped by the CLI's request() ack-matcher and used to surface as
 * a generic "disconnected before answering" (JK-2158).
 */
class PluginInstallLocalVerbTest {

    @Test
    void request_without_dir_answers_an_error_ack_not_an_error_line() {
        List<String> sent = new ArrayList<>();
        var verb = new PluginInstallLocalVerb(new RecordingHost(sent));

        verb.run("{\"type\":\"plugin-install-local-request\"}", null, null);

        assertThat(sent).hasSize(1);
        PluginInstallLocalAck ack = PluginInstallLocalAck.decode(sent.get(0));
        assertThat(ack.error()).contains("dir");
    }

    /** sendQuiet records; everything else is unreachable from the error path under test. */
    private record RecordingHost(List<String> sent) implements VerbHost {
        @Override
        public void sendQuiet(@Nullable BufferedWriter writer, String line) {
            sent.add(line);
        }

        @Override
        public long eventRequestId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putProgressRoot(long rid, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter writer, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(String dir, @Nullable BufferedWriter writer, BuildPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(
                String dir, @Nullable BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseExclusiveSlot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void accTests(long rid, @Nullable TestSummary tests) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finishProgress(long rid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flushTimeline(long rid, @Nullable BufferedWriter writer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(@Nullable BufferedWriter writer, String line) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String redactEnv(@Nullable String dir, @Nullable String text) {
            return String.valueOf(text);
        }

        @Override
        public String requestFailedLine(@Nullable String dir, Throwable e) {
            return cc.jumpkick.engine.protocol.ProtoLifecycle.requestFailed(String.valueOf(e.getMessage()));
        }

        @Override
        public void publishRequestError(long rid, @Nullable String dir, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {
            throw new UnsupportedOperationException();
        }
    }
}
