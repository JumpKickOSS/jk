// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A sync's "fetched" counts the lock's own files, never a toolchain's the same run staged. */
class SyncRunCountsTest {

    private static Lockfile.Artifact row(String name, String version) {
        return new Lockfile.Artifact(
                name,
                version,
                "central",
                "sha256:00",
                null,
                List.of(Scope.MAIN),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static void land(String label) {
        try (var t = SessionContext.current().io().transfers().openArtifact(label)) {
            t.started(1);
            t.advanced(1);
            t.finished();
        }
    }

    @Test
    void a_toolchain_jar_the_run_staged_is_not_one_of_the_locks_fetches() throws Exception {
        Lockfile lock = Lockfile.empty("test")
                .withArtifacts(List.of(row("com.example:a:jar:", "1.0"), row("com.example:b:jar:", "1.0")));
        WorkspaceResult.SyncCounts counts = SessionContext.where(Session.defaults(), () -> {
            Set<String> before = SessionContext.current().io().transfers().artifactLabelsLanded();
            land(lock.artifacts().get(0).displayCoord()); // a first lock downloaded a
            land("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10"); // the forks step staged this
            // The sync found both of the lock's rows on disk: the lock had just written a.
            return WorkspaceSyncPhase.runCounts(0, 2, lock, before);
        });

        assertThat(counts).isEqualTo(new WorkspaceResult.SyncCounts(1, 1));
    }

    @Test
    void the_syncs_own_fetches_count_when_no_lock_downloaded_anything() throws Exception {
        Lockfile lock = Lockfile.empty("test").withArtifacts(List.of(row("com.example:a:jar:", "1.0")));
        WorkspaceResult.SyncCounts counts =
                SessionContext.where(Session.defaults(), () -> WorkspaceSyncPhase.runCounts(1, 0, lock, Set.of()));

        assertThat(counts).isEqualTo(new WorkspaceResult.SyncCounts(1, 0));
    }
}
