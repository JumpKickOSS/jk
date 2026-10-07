// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A POM's {@code .jk} digest is read once while unchanged, and again once the memo is rewritten. */
class RepoArtifactStoreSidecarMemoTest {

    private static final String REL = "org/example/lib/1.0/lib-1.0.pom";

    @Test
    void a_sidecar_is_read_once_until_it_is_rewritten(@TempDir Path tmp) throws Exception {
        RepoArtifactStore store = RepoArtifactStore.forStoreId(tmp, "central");
        Path first = Files.writeString(tmp.resolve("first.pom"), "<project/>");
        store.materialize(REL, first, Hashing.sha256Hex(first));

        long before = RepoArtifactStore.sidecarReads();
        assertThat(store.readSha256Sidecar(REL)).hasValue(Hashing.sha256Hex(first));
        assertThat(store.readSha256Sidecar(REL)).hasValue(Hashing.sha256Hex(first));
        assertThat(RepoArtifactStore.forStoreId(tmp, "central").readSha256Sidecar(REL))
                .as("another handle on the same store")
                .hasValue(Hashing.sha256Hex(first));
        assertThat(RepoArtifactStore.sidecarReads() - before).isEqualTo(1);

        Path second = Files.writeString(tmp.resolve("second.pom"), "<project><artifactId>x</artifactId></project>");
        store.materialize(REL, second, Hashing.sha256Hex(second));

        assertThat(store.readSha256Sidecar(REL)).hasValue(Hashing.sha256Hex(second));
        assertThat(RepoArtifactStore.sidecarReads() - before).isEqualTo(2);
    }
}
