// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoIdentity;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A tree whose artifacts live in the Maven local repository holds memos only; what an earlier layout left beside them goes. */
class CachePlansShadowedArtifactsTest {

    private static final String REL = "com/example/lib/1.0/lib-1.0.jar";

    @TempDir
    Path tmp;

    private Path store;

    @BeforeEach
    void setUp() {
        store = tmp.resolve("store");
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        System.setProperty("jk.m2.local", tmp.resolve("m2").toString());
    }

    private Path leave(String origin, String treeId) throws Exception {
        Path tree = store.resolve("repos").resolve(RepoIdentity.storeId(URI.create(origin)));
        Path jar = tree.resolve(REL);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "stale-copy");
        ArtifactMemo.ofBlob(jar, "com.example:lib:1.0", Hashing.sha256Hex(jar)).write(ArtifactMemo.jkPath(tree, REL));
        Files.writeString(
                tree.resolve(RepoArtifactStore.ORIGIN_FILE),
                "origin = \"" + origin + "\"\nname = \"" + treeId + "\"\n");
        return jar;
    }

    @AfterEach
    void forgetTheMode() {
        System.clearProperty("jk.env.JK_M2_INTEGRATION");
    }

    /** The local repository's own copy of {@link #REL}, holding {@code content}. */
    private void twin(String content) throws Exception {
        Path twin = tmp.resolve("m2").resolve(REL);
        Files.createDirectories(twin.getParent());
        Files.writeString(twin, content);
    }

    @Test
    void by_default_the_store_keeps_its_own_downloads() throws Exception {
        Path jar = leave("https://repo.maven.apache.org/maven2", "central");
        twin("stale-copy");

        assertThat(CachePlans.reclaimShadowedArtifacts(false).files()).isZero();
        assertThat(jar).exists();
    }

    @Test
    void sharing_a_store_copy_with_no_twin_or_another_twin_is_the_only_one_and_stays() throws Exception {
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        Path jar = leave("https://repo.maven.apache.org/maven2", "central");

        assertThat(CachePlans.reclaimShadowedArtifacts(false).files()).isZero();
        twin("other-copy");
        assertThat(CachePlans.reclaimShadowedArtifacts(false).files()).isZero();
        assertThat(jar).exists();
    }

    @Test
    void sharing_the_jars_the_local_repository_also_holds_go_and_the_memos_stay() throws Exception {
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        Path jar = leave("https://repo.maven.apache.org/maven2", "central");
        twin("stale-copy");

        CachePlans.SweepReport dry = CachePlans.reclaimShadowedArtifacts(true);
        assertThat(dry.files()).isEqualTo(1);
        assertThat(jar).exists();

        CachePlans.SweepReport done = CachePlans.reclaimShadowedArtifacts(false);

        assertThat(done.bytes()).isEqualTo("stale-copy".length());
        assertThat(jar).doesNotExist();
        assertThat(ArtifactMemo.jkPath(store.resolve("repos/central"), REL)).exists();
        assertThat(store.resolve("repos/central").resolve(RepoArtifactStore.ORIGIN_FILE))
                .exists();
    }

    @Test
    void the_use_ledger_beside_the_memos_is_not_a_stray_artifact() throws Exception {
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        leave("https://repo.maven.apache.org/maven2", "central");
        twin("stale-copy");
        Path journal = store.resolve("repos/central").resolve(RepoArtifactStore.ACCESS_DIR + "/journal");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "1\tm2:x\n");

        CachePlans.reclaimShadowedArtifacts(false);

        assertThat(journal).exists();
    }

    @Test
    void a_tree_that_keeps_its_own_artifacts_is_left_alone() throws Exception {
        System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
        Path jar = leave("https://private.example/maven/", "private");
        twin("stale-copy");

        assertThat(CachePlans.reclaimShadowedArtifacts(false).files()).isZero();
        assertThat(jar).exists();
    }
}
