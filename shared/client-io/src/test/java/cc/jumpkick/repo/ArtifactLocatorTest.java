// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactLocatorTest {

    @Test
    void prefers_digest_matching_m2_file(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.google.guava:guava",
                "33.4.8",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:pending",
                null,
                List.of(Scope.MAIN),
                List.of());
        String rel = MavenLayout.artifactPath(pkg.coordinate());
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "guava-bytes");
        String hex = Hashing.sha256Hex(m2Jar);
        pkg = new Lockfile.Artifact(
                pkg.name(), pkg.version(), pkg.source(), "sha256:" + hex, pkg.path(), pkg.scopes(), pkg.deps());

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        assertThat(locator.locate(pkg)).contains(m2Jar.toAbsolutePath().normalize());

        // JK-2307: the m2 probe records its own `.m2.jk` memo, distinct from the store's `.jk`,
        // so the two blobs don't invalidate each other's fast path.
        Path storeSidecar = ArtifactMemo.jkPath(store.resolve("repos/central"), rel);
        Path m2Sidecar = storeSidecar.resolveSibling(
                storeSidecar.getFileName().toString().replace(".jk", ".m2.jk"));
        assertThat(m2Sidecar).exists();
        assertThat(storeSidecar).doesNotExist();
        // The fast path now holds: a second locate rehashes nothing but still resolves.
        assertThat(locator.locate(pkg)).contains(m2Jar.toAbsolutePath().normalize());
    }

    @Test
    void mismatching_m2_falls_back_to_store_and_leaves_m2_untouched(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        String rel = "com/foo/a/1.0/a-1.0.jar";
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "poison");

        Path src = dir.resolve("good.jar");
        Files.writeString(src, "genuine");
        String hex = Hashing.sha256Hex(src);
        RepoArtifactStore.forRepoName(store, "central").materialize(rel, src, hex);

        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a",
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + hex,
                null,
                List.of(Scope.MAIN),
                List.of());

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        Path found = locator.locate(pkg).orElseThrow();
        assertThat(found)
                .isEqualTo(store.resolve("repos/central")
                        .resolve(rel)
                        .toAbsolutePath()
                        .normalize());
        assertThat(Files.readString(m2Jar)).isEqualTo("poison");
    }
}
