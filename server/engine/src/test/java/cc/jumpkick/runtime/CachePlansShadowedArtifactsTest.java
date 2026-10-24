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
    private String priorStore;

    @BeforeEach
    void setUp() {
        store = tmp.resolve("store");
        priorStore = System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        System.setProperty("jk.m2.local", tmp.resolve("m2").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jk.m2.local");
        if (priorStore == null) System.clearProperty("jk.env.JK_STORE_DIR");
        else System.setProperty("jk.env.JK_STORE_DIR", priorStore);
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

    @Test
    void the_jars_beside_a_central_trees_memos_go_and_the_memos_stay() throws Exception {
        Path jar = leave("https://repo.maven.apache.org/maven2", "central");

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
    void a_tree_that_keeps_its_own_artifacts_is_left_alone() throws Exception {
        Path jar = leave("https://private.example/maven/", "private");

        assertThat(CachePlans.reclaimShadowedArtifacts(false).files()).isZero();
        assertThat(jar).exists();
    }
}
