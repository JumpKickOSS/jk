// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk sync} downloads what {@code jk build}'s sync downloads, through the same board, and a
 * build downloads nothing after its sync is done. Each run gets dependencies no store has seen (a
 * fresh version from a loopback repository), so both start cold for them; the two download sets
 * are compared with the version taken out.
 */
@Tag("integration")
class SyncBuildDownloadParityTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @Test
    void a_sync_downloads_what_the_builds_sync_downloads_and_the_build_nothing_after(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("sync-build-download-parity");
        RepoGroup.clearProcessFetchCache();
        String syncVersion = "1." + System.nanoTime();
        String buildVersion = "2." + System.nanoTime();
        publish(syncVersion);
        publish(buildVersion);

        Downloads sync = run(project(tmp.resolve("sync"), syncVersion), cache, WorkspaceSpec.sync(false, null));
        Downloads build = run(project(tmp.resolve("build"), buildVersion), cache, WorkspaceSpec.DEFAULT);

        assertThat(sync.result().success())
                .as("sync: %s", sync.result().errors())
                .isTrue();
        assertThat(build.result().success())
                .as("build: %s", build.result().errors())
                .isTrue();
        assertThat(normalized(sync.all(), syncVersion))
                .as("what the sync downloaded")
                .contains("com.example.parity:lib-a:V", "com.example.parity:lib-b:V");
        assertThat(normalized(build.atSyncDone(), buildVersion))
                .as("the build's sync downloads what the sync does")
                .isEqualTo(normalized(sync.all(), syncVersion));
        assertThat(build.all()).as("the build downloads nothing after its sync").isEqualTo(build.atSyncDone());
    }

    /** What a run landed on the request's board: all of it, and what had landed when its sync finished. */
    private record Downloads(WorkspaceResult result, Set<String> all, Set<String> atSyncDone) {}

    private static Downloads run(Path dir, Path cache, WorkspaceSpec spec) {
        TransferBoard board = SessionContext.current().io().transfers();
        Set<String> before = new HashSet<>(board.artifactLabelsLanded());
        AtomicReference<Set<String>> atSync = new AtomicReference<>(Set.of());
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 1, null, true, false, 1, null, false, true).withSpec(spec),
                new WorkspaceBuildListener() {
                    @Override
                    public void onPreflight(String stage, int done, int total, String label) {
                        if ("sync".equals(stage) && done == total) atSync.set(since(board, before));
                    }
                });
        return new Downloads(result, since(board, before), atSync.get());
    }

    private static Set<String> since(TransferBoard board, Set<String> before) {
        Set<String> out = new TreeSet<>(board.artifactLabelsLanded());
        out.removeAll(before);
        return out;
    }

    /** The labels with {@code version} written as {@code V}, so two runs' downloads compare. */
    private static Set<String> normalized(Set<String> labels, String version) {
        Set<String> out = new TreeSet<>();
        for (String label : labels) out.add(label.replace(version, "V"));
        return out;
    }

    /** lib-a depends on lib-b, both at {@code version}. */
    private void publish(String version) {
        new MavenStub(http)
                .pom("com.example.parity", "lib-a", version, pom("lib-a", version, """
                        <dependencies><dependency>
                          <groupId>com.example.parity</groupId><artifactId>lib-b</artifactId>
                          <version>%s</version>
                        </dependency></dependencies>""".formatted(version)))
                .jar("com.example.parity", "lib-a", version)
                .pom("com.example.parity", "lib-b", version, pom("lib-b", version, ""))
                .jar("com.example.parity", "lib-b", version);
    }

    private static String pom(String artifact, String version, String dependencies) {
        return """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.parity</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>""".formatted(artifact, version, dependencies);
    }

    private Path project(Path dir, String version) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "parity"
                group = "com.example"
                version = "1.0.0"
                java = 25
                layout = "traditional"

                [dependencies]
                lib-a = { group = "com.example.parity", name = "lib-a", version = "%s" }

                [repositories]
                stub = "%s"
                """.formatted(version, http.baseUrl()));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                public final class App {
                    private App() {}
                }
                """);
        return dir;
    }
}
