// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreRepoGcTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path store;

    /** An artifact the store holds in repository {@code id}, last used {@code daysAgo} days ago. */
    private Path artifact(String id, String rel, byte[] bytes, long daysAgo) throws Exception {
        RepoArtifactStore repo = RepoArtifactStore.forStoreId(store, id);
        Path source = Files.write(store.resolve("src-" + Math.abs(rel.hashCode())), bytes);
        repo.materialize(rel, source, Hashing.sha256Hex(bytes));
        Files.setLastModifiedTime(
                ArtifactMemo.jkPath(store.resolve("repos").resolve(id), rel),
                FileTime.fromMillis(System.currentTimeMillis() - daysAgo * DAY));
        return store.resolve("repos").resolve(id).resolve(rel);
    }

    @Test
    void a_store_resident_artifact_past_its_window_goes_with_its_memo_and_a_fresh_one_stays() throws Exception {
        Path old = artifact("vendor", "com/v/old/1.0/old-1.0.jar", "old".getBytes(), 60);
        Path fresh = artifact("vendor", "com/v/new/1.0/new-1.0.jar", "new".getBytes(), 2);

        StoreRepoGc.Report report = StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), false);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(ArtifactMemo.jkPath(store.resolve("repos/vendor"), "com/v/old/1.0/old-1.0.jar"))
                .doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void a_large_file_gets_a_week_not_six() throws Exception {
        byte[] big = new byte[(int) StoreRepoGc.LARGE_BYTES];
        big[0] = 'P';
        Path distribution = artifact("vendor", "com/v/ide/1.0/ide-1.0.zip", big, 10);

        StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), false);

        assertThat(distribution).doesNotExist();
    }

    @Test
    void first_party_trees_and_dry_runs_are_left_alone() throws Exception {
        Path shelf = artifact("jk-local", "dev/jk/x/1/x-1.jar", "x".getBytes(), 400);
        Path vendor = artifact("vendor", "com/v/old/1.0/old-1.0.jar", "old".getBytes(), 400);

        StoreRepoGc.Report report = StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), true);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(shelf).exists();
        assertThat(vendor).exists();
    }
}
