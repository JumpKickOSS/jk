// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.SafePoint;
import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    @Test
    void an_artifact_of_a_file_origin_that_no_longer_holds_it_stays() throws Exception {
        Path origin = Files.createDirectories(store.resolve("built/repo"));
        String gone = "t/lib/1.0/lib-1.0.jar";
        String held = "t/lib/2.0/lib-2.0.jar";
        RepoArtifactStore repo = RepoArtifactStore.forRepository(store, "git:t:lib:1.0", origin.toUri());
        Path tree = Objects.requireNonNull(repo.root());
        for (String rel : new String[] {gone, held}) {
            Path built = origin.resolve(rel);
            Files.createDirectories(built.getParent());
            Files.writeString(built, rel);
            repo.materialize(rel, built, Hashing.sha256Hex(built));
            Files.setLastModifiedTime(
                    ArtifactMemo.jkPath(tree, rel), FileTime.fromMillis(System.currentTimeMillis() - 400 * DAY));
        }
        Files.delete(origin.resolve(gone));

        StoreRepoGc.Report report = StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), false);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(tree.resolve(gone)).as("the store holds its only copy").exists();
        assertThat(tree.resolve(held)).as("its origin can serve it again").doesNotExist();
    }

    @Test
    void a_stop_lands_between_artifacts_each_gone_with_its_memo_or_kept_with_it() throws Exception {
        List<String> rels = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String rel = "com/v/a" + i + "/1.0/a" + i + "-1.0.jar";
            artifact("vendor", rel, ("bytes-" + i).getBytes(), 60);
            rels.add(rel);
        }
        Path tree = store.resolve("repos/vendor");
        SafePoint afterFirstRemoval = () -> {
            if (rels.stream().anyMatch(rel -> Files.notExists(tree.resolve(rel))))
                throw new IllegalStateException("stop");
        };

        assertThatThrownBy(() -> StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), false, afterFirstRemoval))
                .hasMessage("stop");

        assertThat(rels.stream().filter(rel -> Files.notExists(tree.resolve(rel))))
                .hasSize(1);
        for (String rel : rels) {
            assertThat(Files.exists(ArtifactMemo.jkPath(tree, rel)))
                    .as("%s and its memo go together", rel)
                    .isEqualTo(Files.exists(tree.resolve(rel)));
        }

        StoreRepoGc.run(store, 1.0, System.currentTimeMillis(), false, SafePoint.NONE);
        assertThat(rels).noneMatch(rel -> Files.exists(tree.resolve(rel)));
    }
}
