// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Maven local repository's copy is hashed once while unchanged: the probe records its digest
 * in the store's {@code .m2.jk} memo, and the next probe of the same file reads the memo instead.
 */
class M2AdoptionMemoTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.jar";
    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");

    @RegisterExtension
    final LoopbackHttp repo = new LoopbackHttp().withoutChecksums();

    @AfterEach
    void reset() {
        System.clearProperty("jk.m2.local");
        SessionContext.reset();
    }

    @Test
    void an_unchanged_local_file_is_hashed_once(@TempDir Path tmp) throws Exception {
        byte[] bytes = "the genuine artifact bytes".getBytes(StandardCharsets.UTF_8);
        Path m2File = seedM2(tmp.resolve("m2"), bytes);
        publish(bytes);
        Cas cas = new Cas(tmp.resolve("store"));
        RepoArtifactStore store = RepoArtifactStore.forRepository(cas.root(), "test", repo.base());

        long before = M2Adoption.READS.sum();
        assertThat(new MavenRepo("test", repo.base(), new Http(), cas)
                        .fetchArtifact(WIDGET)
                        .sha256())
                .isEqualTo(Hashing.sha256Hex(bytes));
        assertThat(M2Adoption.READS.sum() - before).isEqualTo(1);
        assertThat(store.m2MemoPath(REL)).exists();

        // The store loses its copy (a clean, a fresh store under the same root); the probe runs again.
        dropStoreCopy(store);
        assertThat(new MavenRepo("test", repo.base(), new Http(), cas)
                        .fetchArtifact(WIDGET)
                        .sha256())
                .isEqualTo(Hashing.sha256Hex(bytes));
        assertThat(M2Adoption.READS.sum() - before)
                .as("the memo answered for the unchanged file")
                .isEqualTo(1);
        assertThat(repo.requested()).doesNotContain("/" + REL);

        // A changed local file is hashed again.
        byte[] changed = "republished artifact bytes, longer".getBytes(StandardCharsets.UTF_8);
        Files.write(m2File, changed);
        publish(changed);
        dropStoreCopy(store);
        assertThat(new MavenRepo("test", repo.base(), new Http(), cas)
                        .fetchArtifact(WIDGET)
                        .sha256())
                .isEqualTo(Hashing.sha256Hex(changed));
        assertThat(M2Adoption.READS.sum() - before).isEqualTo(2);
    }

    private void publish(byte[] bytes) {
        repo.served().put("/" + REL + ".sha256", Hashing.sha256Hex(bytes).getBytes(StandardCharsets.UTF_8));
    }

    private static void dropStoreCopy(RepoArtifactStore store) throws Exception {
        Path jar = store.locate(REL).orElseThrow();
        Files.delete(jar);
        Files.deleteIfExists(jar.resolveSibling("widget-1.0.jk"));
    }

    private static Path seedM2(Path m2, byte[] bytes) throws Exception {
        System.setProperty("jk.m2.local", m2.toString());
        Path p = m2.resolve(REL);
        Files.createDirectories(p.getParent());
        return Files.write(p, bytes);
    }
}
