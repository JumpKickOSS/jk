// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk sync} is the first phase of {@code jk build}, not a second implementation of it: from
 * the same state, a sync tells its client exactly what a build tells its own up to the moment the
 * build's sync is done, stage for stage and word for word, and then stops.
 */
@Tag("integration")
class SyncBuildParityTest {

    @Test
    void a_sync_is_the_builds_lock_and_sync_phase_and_nothing_after(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        Path cache = TestCaches.dir("sync-build-parity");
        // Warm: the lock written and every pinned file on disk, so both runs below start alike.
        WorkspaceResult warm = run(dir, cache, WorkspaceSpec.sync(false, null), new Recorder());
        assertThat(warm.errors()).as("warm-up sync").isEmpty();

        // Downloads before this run are not this run's: an artifact that landed earlier on the same
        // ledger (another request, or another test in this JVM) does not count as fetched below.
        try (var earlier = SessionContext.current().io().transfers().openArtifact("g:earlier:1")) {
            earlier.started(1);
            earlier.advanced(1);
            earlier.finished();
        }
        Recorder sync = new Recorder();
        WorkspaceResult synced = run(dir, cache, WorkspaceSpec.sync(false, null), sync);
        Recorder build = new Recorder();
        WorkspaceResult built = run(dir, cache, WorkspaceSpec.DEFAULT, build);

        assertThat(synced.success()).as("sync: %s", synced.errors()).isTrue();
        assertThat(built.success()).as("build: %s", built.errors()).isTrue();
        int syncDone = build.events.indexOf(sync.events.getLast());
        assertThat(syncDone)
                .as("the build's sync stage finished the way the sync did")
                .isNotNegative();
        assertThat(sync.events).isEqualTo(build.events.subList(0, syncDone + 1));
        assertThat(sync.events.getLast()).startsWith("preflight sync 1/1 0 fetched, ");
        assertThat(synced.sync()).as("the sync's counts").isNotNull();
        assertThat(synced.modules()).as("a sync builds no module").isEmpty();
        assertThat(build.events.subList(syncDone + 1, build.events.size()))
                .as("the build goes on past the sync")
                .isNotEmpty();
    }

    private static WorkspaceResult run(Path dir, Path cache, WorkspaceSpec spec, WorkspaceBuildListener listener) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 1, null, true, false, 1, null, false, true).withSpec(spec),
                listener);
    }

    /** What a client is told before any module plans: each preflight stage, failure and note. */
    private static final class Recorder implements WorkspaceBuildListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onPreflight(String stage, int done, int total, String label) {
            events.add("preflight " + stage + " " + done + "/" + total + " " + label);
        }

        @Override
        public void onPreflightFailed(String stage, long millis, String reason) {
            events.add("failed " + stage + " " + reason);
        }

        @Override
        public void onNote(String text) {
            events.add("note " + text);
        }
    }

    private static Path project(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("parity"));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "parity"
                group = "com.example"
                version = "1.0.0"
                java = 25
                layout = "traditional"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                public final class App {
                    private App() {}
                }
                """);
        return dir;
    }
}
