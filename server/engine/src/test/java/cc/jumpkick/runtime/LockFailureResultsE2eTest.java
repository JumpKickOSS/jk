// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.SourceLocation;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a workspace whose lock freshen the resolver refuses ends as a failed run in the
 * journal — a root {@code lock} step that failed, the solver's reason and conflict as its error,
 * {@link Exit#RESOLVE} — and stays one when the client hangs up the instant it reads the terminal.
 * The reason travels the real path: the preflight phase, the listener, the accumulator; only the
 * write to disk is left out.
 */
@Tag("integration")
class LockFailureResultsE2eTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void start() {
        // The product store is ambient; a test that resolves keeps its metadata out of the real one.
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.leaf("com.foo", "lib", "1.0");
    }

    @AfterEach
    void forgetStore() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void a_refused_lock_is_a_failed_step_with_the_solvers_reason_not_a_cancelled_run(@TempDir Path tmp)
            throws Exception {
        Path ws = workspace(tmp, http.base());
        BuildAccumulator acc = new BuildAccumulator("build", ws.toString(), "com.example:ws", "cli");
        List<String> failed = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public void onPreflightFailed(String stage, long millis, String reason, @Nullable SourceLocation at) {
                throw new AssertionError("the preflight failure lost its conflict");
            }

            @Override
            public void onPreflightFailed(
                    String stage, long millis, String reason, @Nullable SourceLocation at, String conflict) {
                failed.add(stage + ": " + reason);
                conflicts.add(conflict);
                acc.addPreflightFailure(stage, millis, reason, at, conflict);
            }
        };

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, tmp.resolve("cache"), null, 0, null, true, false, 1, null, false, true),
                listener);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).as("the lock's exit").isEqualTo(Exit.RESOLVE);
        assertThat(result.modules()).as("no module ran").isEmpty();
        assertThat(result.errors()).hasSize(1);
        String reason = result.errors().getFirst();
        assertThat(failed).containsExactly("lock: " + reason);
        assertThat(reason).as("the solver's reason").contains("com.foo:lib");
        assertThat(result.conflict()).as("the solver refused, not the network").isNotEmpty();
        assertThat(conflicts).containsExactly(result.conflict());

        // The client reads the terminal, prints the explanation, exits 6 and closes the socket
        // before the envelope stamps the verdict: the EOF must not relabel the failure.
        acc.markUserCancelled(false, "the client disconnected before the job finished");
        acc.stamp(new JobOutcome.Failed(result.exitCode()));
        BuildRecord record = acc.toRecord(2_000, true, 1_000, "test", null);

        assertThat(record.success()).isFalse();
        assertThat(record.cancelled())
                .as("a failure the run recorded is never a cancel")
                .isFalse();
        assertThat(record.exitCode()).isEqualTo(Exit.RESOLVE);
        assertThat(record.steps()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("lock");
            assertThat(t.status()).isEqualTo("FAIL");
        });
        assertThat(record.diagnostics())
                .filteredOn(d -> "error".equals(d.severity()))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.step()).isEqualTo("lock");
                    assertThat(d.message()).isEqualTo(reason);
                    assertThat(d.conflict()).isEqualTo(result.conflict());
                });

        String md = JkResultsMarkdown.render(record);
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("**exit 6**");
        assertThat(md).contains("- `lock`: " + reason.lines().findFirst().orElseThrow());
        assertThat(md).contains("## Failed steps").contains("| `lock` | FAIL |");
        assertThat(md).doesNotContain("CANCELLED").doesNotContain("client disconnected");
    }

    /** One member that pins a version of {@code com.foo:lib} the only repository does not have. */
    private static Path workspace(Path tmp, URI repo) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]

                [repositories.stub]
                url = "%s"
                groups = ["com.foo", "org.junit.jupiter", "org.junit.platform"]
                """.formatted(repo));
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "=9.9" }
                """);
        Path src = Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                public final class Main {}
                """);
        return ws;
    }
}
