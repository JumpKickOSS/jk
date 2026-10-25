// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class VerbRegistryTest {

    @Test
    void standard_serves_every_build_like_goal_through_one_verb() {
        VerbRegistry reg = VerbRegistry.standard(new FakeHost());
        assertThat(reg.all()).hasSize(41);
        HostedVerb build = verb(reg, EngineProtocol.BUILD_REQUEST);
        assertThat(build).isInstanceOf(WorkspaceBuildVerb.class);
        assertThat(build.jobKinds())
                .containsExactlyInAnyOrder(
                        "build",
                        "assemble",
                        "test",
                        "guard",
                        "compile",
                        "image",
                        "install",
                        "snapshot",
                        "native",
                        "publish");
        assertThat(verb(reg, EngineProtocol.LOCK_REQUEST)).isInstanceOf(LockVerb.class);
        assertThat(verb(reg, EngineProtocol.UPDATE_REQUEST)).isInstanceOf(UpdateVerb.class);
        assertThat(verb(reg, EngineProtocol.SYNC_REQUEST)).isInstanceOf(SyncVerb.class);
        assertThat(build.shape()).isInstanceOf(VerbShape.AsyncPlan.class);
        assertThat(build.toJobRequest("{\"type\":\"build-request\"}").verb()).isEqualTo("build");
        assertThat(build.toJobRequest("{\"type\":\"build-request\",\"workspaceTarget\":\"native\"}")
                        .verb())
                .isEqualTo("native");
        assertThat(build.toJobRequest("{\"type\":\"build-request\",\"guard\":true}")
                        .verb())
                .isEqualTo("guard");
        assertThat(verb(reg, EngineProtocol.AUDIT_REQUEST)).isInstanceOf(AuditVerb.class);
        assertThat(verb(reg, EngineProtocol.CACHE_PRUNE_REQUEST).shape()).isInstanceOf(VerbShape.CacheMaint.class);
        assertThat(verb(reg, EngineProtocol.EXPLAIN_REQUEST).shape()).isInstanceOf(VerbShape.SyncRead.class);
        assertThat(verb(reg, EngineProtocol.AFFECTED_TESTS_REQUEST)).isInstanceOf(AffectedTestsVerb.class);
        assertThat(verb(reg, EngineProtocol.AFFECTED_TESTS_REQUEST).shape()).isInstanceOf(VerbShape.SyncRead.class);
        assertThat(reg.find("not-a-verb")).isNull();
    }

    private static final class FakeHost implements VerbHost {
        @Override
        public long eventRequestId() {
            return -1;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter writer, String dir) {
            return new WorkspaceBuildListener() {};
        }

        @Override
        public BuildPlanListener planListener(String dir, @Nullable BufferedWriter writer, BuildPlan plan) {
            return new BuildPlanListener() {};
        }

        @Override
        public BuildPlanListener planListener(
                String dir,
                @Nullable BufferedWriter writer,
                @Nullable Function<BuildPlanResult, String> finishEncoder) {
            return new BuildPlanListener() {};
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return tokenCancelled;
        }

        @Override
        public void accTests(long rid, @Nullable TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force) {}

        @Override
        public void flushTimeline(long rid, @Nullable BufferedWriter writer) {}

        @Override
        public void send(@Nullable BufferedWriter writer, String line) {}

        @Override
        public void sendQuiet(@Nullable BufferedWriter writer, String line) {}

        @Override
        public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
            return text;
        }

        @Override
        public String requestFailedLine(@Nullable String dir, Throwable e) {
            return "{\"type\":\"request-failed\"}";
        }

        @Override
        public void publishRequestError(long rid, @Nullable String dir, String message) {}

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }

    /** The verb registered for {@code wireType}; that each one is there is the test. */
    private static HostedVerb verb(VerbRegistry reg, String wireType) {
        return requireNonNull(reg.find(wireType), wireType);
    }
}
