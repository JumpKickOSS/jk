// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VerbRegistryTest {

    @Test
    void standard_lists_workspace_test_and_single_build() {
        VerbRegistry reg = VerbRegistry.standard(new FakeHost());
        assertThat(reg.all()).hasSize(41);
        HostedVerb build = reg.find(EngineProtocol.BUILD_REQUEST);
        HostedVerb test = reg.find(EngineProtocol.TEST_REQUEST);
        HostedVerb single = reg.find(EngineProtocol.SINGLE_BUILD_REQUEST);
        assertThat(build).isInstanceOf(WorkspaceBuildVerb.class);
        assertThat(test).isInstanceOf(TestVerb.class);
        assertThat(single).isInstanceOf(SingleBuildVerb.class);
        assertThat(reg.find(EngineProtocol.LOCK_REQUEST)).isInstanceOf(LockVerb.class);
        assertThat(reg.find(EngineProtocol.UPDATE_REQUEST)).isInstanceOf(UpdateVerb.class);
        assertThat(reg.find(EngineProtocol.SYNC_REQUEST)).isInstanceOf(SyncVerb.class);
        assertThat(build.shape()).isInstanceOf(VerbShape.AsyncPlan.class);
        assertThat(test.jobKind().verb()).isEqualTo("test");
        assertThat(single.toJobRequest("{\"type\":\"single-build-request\"}").verb())
                .isEqualTo("build");
        assertThat(reg.find(EngineProtocol.AUDIT_REQUEST)).isInstanceOf(AuditVerb.class);
        assertThat(reg.find(EngineProtocol.CACHE_PRUNE_REQUEST).shape()).isInstanceOf(VerbShape.CacheMaint.class);
        assertThat(reg.find(EngineProtocol.EXPLAIN_REQUEST).shape()).isInstanceOf(VerbShape.SyncRead.class);
        assertThat(reg.find("not-a-verb")).isNull();
    }

    @Test
    void decode_carries_the_wire_line() {
        HostedVerb v = new TestVerb(new FakeHost());
        VerbRequest req = v.decode(new VerbInput("{\"type\":\"test-request\",\"dir\":\"/p\"}"));
        assertThat(req.wireType()).isEqualTo(EngineProtocol.TEST_REQUEST);
        assertThat(req.kind()).isEqualTo("test");
        assertThat(req.requestLine()).contains("test-request");
    }

    private static final class FakeHost implements VerbHost {
        @Override
        public long eventRequestId() {
            return -1;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir) {
            return new WorkspaceBuildListener() {};
        }

        @Override
        public BuildPlanListener planListener(String dir, BufferedWriter writer, BuildPlan plan) {
            return new BuildPlanListener() {};
        }

        @Override
        public BuildPlanListener planListener(
                String dir, BufferedWriter writer, Function<cc.jumpkick.run.BuildPlanResult, String> finishEncoder) {
            return new BuildPlanListener() {};
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return tokenCancelled;
        }

        @Override
        public void accTests(long rid, TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {}

        @Override
        public void flushTimeline(long rid, BufferedWriter writer) {}

        @Override
        public void send(BufferedWriter writer, String line) {}

        @Override
        public void sendQuiet(BufferedWriter writer, String line) {}

        @Override
        public String redactEnv(String dir, String text) {
            return text;
        }

        @Override
        public String requestFailedLine(String dir, Throwable e) {
            return "{\"type\":\"request-failed\"}";
        }

        @Override
        public void publishRequestError(long rid, String dir, String message) {}

        @Override
        public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
            return Session.defaults().withCancel(cancel);
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }
}
