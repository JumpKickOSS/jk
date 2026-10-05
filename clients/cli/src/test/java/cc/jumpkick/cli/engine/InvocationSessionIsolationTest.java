// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Two invocations in one JVM build their requests from their own flags, never each other's. */
class InvocationSessionIsolationTest {

    private static WorkspaceRequest request() {
        return new WorkspaceRequest(
                Path.of("/proj"), Path.of("/cache"), null, 0, null, false, false, 0, null, true, true);
    }

    @Test
    void concurrent_invocations_each_carry_their_own_flags() throws Exception {
        CyclicBarrier installed = new CyclicBarrier(2);
        CompletableFuture<BuildRequest> skipping = CompletableFuture.supplyAsync(() -> invoke(installed, true, null));
        CompletableFuture<BuildRequest> reporting =
                CompletableFuture.supplyAsync(() -> invoke(installed, false, TestFailureMode.REPORT));

        BuildRequest a = skipping.get(30, TimeUnit.SECONDS);
        BuildRequest b = reporting.get(30, TimeUnit.SECONDS);
        assertThat(a.skipNode()).isTrue();
        assertThat(a.testFailures()).isNull();
        assertThat(b.skipNode()).isFalse();
        assertThat(b.testFailures()).isEqualTo(TestFailureMode.REPORT.wireName());
        assertThat(SessionContext.installed().skipNode())
                .as("nothing reached the process fallback")
                .isFalse();
    }

    /** As BuildCommand does: install the flags, then build the request from the current session. */
    private static BuildRequest invoke(CyclicBarrier installed, boolean skipNode, @Nullable TestFailureMode failures) {
        try {
            return SessionContext.invocation(() -> {
                SessionContext.install(
                        SessionContext.current().withSkipNode(skipNode).withTestFailures(failures));
                installed.await(30, TimeUnit.SECONDS); // both have installed before either encodes
                return BuildRequest.decode(EngineJobs.encodeWorkspaceRequest(request(), SessionContext.current()));
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void outside_an_invocation_install_still_sets_the_process_fallback() {
        Session before = SessionContext.installed();
        try {
            SessionContext.install(Session.defaults().withSkipNode(true));
            assertThat(SessionContext.current().skipNode()).isTrue();
        } finally {
            SessionContext.install(before);
        }
    }
}
