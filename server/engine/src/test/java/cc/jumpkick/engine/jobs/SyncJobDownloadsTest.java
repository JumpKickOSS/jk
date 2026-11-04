// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real verb, end to end: {@code jk sync} of a project with no lock, run as a job against a slow
 * repository, streams its downloads to the client as they land — the lock's POMs, then the sync's
 * jars — and its last frame counts every file.
 */
@Tag("integration")
class SyncJobDownloadsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent().beforeServe(path -> {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });

    @Test
    void a_cold_sync_streams_its_downloads_as_they_land(@TempDir Path tmp) throws Exception {
        RepoGroup.clearProcessFetchCache();
        String version = "1." + System.nanoTime();
        new MavenStub(http)
                .pom("com.example.slow", "lib-a", version, pom("lib-a", version, """
                        <dependencies><dependency>
                          <groupId>com.example.slow</groupId><artifactId>lib-b</artifactId>
                          <version>%s</version>
                        </dependency></dependencies>""".formatted(version)))
                .pom("com.example.slow", "lib-b", version, pom("lib-b", version, ""));
        Path dir = project(tmp, version);
        Path cache = TestCaches.dir("sync-job-downloads");
        AtomicReference<WorkspaceResult> result = new AtomicReference<>();
        StringWriter out = new StringWriter();

        ConnectedClient.submit(
                new JobEnvelope(new FakeEnvelopeHost(), JobLimits.DEFAULTS),
                "{\"type\":\"build-request\",\"dir\":\"" + dir + "\"}",
                JobRequest.plan("sync", "jk-test-", (line, tok, w) -> {
                    try {
                        result.set(SessionContext.where(
                                Session.defaults(),
                                () -> WorkspaceExecute.buildWorkspace(
                                        new WorkspaceRequest(
                                                        dir, cache, null, 1, null, true, false, 1, null, false, true)
                                                .withSpec(WorkspaceSpec.sync(false, null)),
                                        new WorkspaceBuildListener() {})));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return JobOutcome.ok();
                }),
                out);

        assertThat(result.get().success()).as("sync: %s", result.get().errors()).isTrue();
        List<String> frames = out.toString()
                .lines()
                .filter(l -> EngineProtocol.DOWNLOADS.equals(EngineProtocol.typeOf(l)))
                .toList();
        assertThat(frames).as("frames sent as files land, not one at the end").hasSizeGreaterThanOrEqualTo(3);
        List<Integer> files =
                frames.stream().map(f -> Jsonl.intValue(f, "files", 0)).toList();
        assertThat(files).as("the count rises as files land: %s", files).isSorted();
        assertThat(files.getFirst()).isLessThan(files.getLast());
        assertThat(files.getLast()).as("both jars and both POMs").isGreaterThanOrEqualTo(4);
    }

    private static String pom(String artifact, String version, String dependencies) {
        return """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.slow</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>""".formatted(artifact, version, dependencies);
    }

    private Path project(Path tmp, String version) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("slow"));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "slow"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib-a = { group = "com.example.slow", name = "lib-a", version = "%s" }

                [repositories]
                stub = "%s"
                """.formatted(version, http.baseUrl()));
        return dir;
    }
}
