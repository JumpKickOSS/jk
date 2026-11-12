// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WorkerScope;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build under a job's request with no worker scope open (an in-process caller, a harness) is still
 * one request: the forecast's compile scope narrows where forks register, not which request the work
 * serves, so the module list is built once for the whole build.
 */
class BareJobRequestScopeTest {

    private static final List<String> MODULES = List.of("a", "b", "c", "d");

    @TempDir
    Path tmp;

    @AfterEach
    void clear() {
        RequestScope.clearAll();
    }

    @Test
    void a_build_under_a_bare_job_request_loads_the_module_list_once() throws Exception {
        Path workspace = workspace();
        Path cache = tmp.resolve("cache");
        assertThat(LockPlans.lockBuildPlan(workspace, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                        .run()
                        .success())
                .as("workspace lock")
                .isTrue();
        WorkspaceRequest request =
                new WorkspaceRequest(workspace, cache, null, 0, null, false, false, 1, null, false, false);

        long before = WorkspaceLoader.loads();
        WorkspaceResult result = Request.of(4_242L, new IoLedger())
                .call(() -> WorkspaceExecute.buildWorkspace(request, new WorkspaceBuildListener() {}));

        assertThat(result.errors()).isEmpty();
        assertThat(WorkspaceLoader.loads() - before)
                .as("one request, one module list, through the forecast and the build")
                .isEqualTo(1);
    }

    /**
     * The compile scope a forecast opens charges its forks to an id of its own and ends them with
     * the call, while its work stays in the job's request scope.
     */
    @Test
    void a_compile_scope_opened_under_a_bare_job_request_stays_in_the_jobs_request_scope() {
        Request.of(4_243L, new IoLedger()).run(() -> {
            RequestScope job = RequestScope.current();
            JavaCompilerHost.scoped(() -> {
                WorkerScope compile = Objects.requireNonNull(WorkerScope.current(), "a compile scope");
                assertThat(compile.id()).isNegative();
                assertThat(JobWorkers.currentRequestId()).isEqualTo(compile.id());
                assertThat(RequestScope.current()).isSameAs(job);
                return null;
            });
        });
    }

    private Path workspace() throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        String members = MODULES.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        Files.writeString(workspace.resolve("jk.toml"), """
                group = "example"
                name = "root"
                version = "1"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(members));
        for (String name : MODULES) {
            Path module = Files.createDirectories(workspace.resolve(name));
            Files.createDirectories(module.resolve("src/main/java"));
            Files.writeString(module.resolve("jk.toml"), """
                    group = "example"
                    name = "%s"
                    version = "1"
                    java = 25
                    """.formatted(name));
        }
        return workspace;
    }
}
