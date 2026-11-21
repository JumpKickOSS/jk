// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphLibraryRowsTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    @TempDir
    Path dir;

    @BeforeEach
    void scratchStore() {
        System.setProperty("jk.env.JK_STORE_DIR", dir.resolve("store").toString());
        System.setProperty("jk.env.HOME", dir.resolve("home").toString());
    }

    @AfterEach
    void realStore() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty("jk.env.HOME");
    }

    @Test
    void aLockedJarsRowNamesItsSourcesJarOnlyWhenOneIsOnDisk() throws Exception {
        Path repo = Objects.requireNonNull(RepoArtifactStore.forSource(dir.resolve("store"), CENTRAL, false)
                .root());
        Path jar = repo.resolve("org/example/graph/lib/1.0/lib-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "classes");
        Lockfile.Artifact lib = new Lockfile.Artifact(
                "org.example.graph:lib",
                "1.0",
                CENTRAL,
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                List.of(Scope.MAIN),
                List.of());
        Path root = Files.createDirectories(dir.resolve("app"));
        LockfileWriter.write(Lockfile.empty("test").withArtifacts(List.of(lib)), root.resolve(ManifestNames.LOCK));

        assertThat(GraphRunner.libraryRows(root)).containsExactly("org.example.graph:lib:1.0\t" + jar + "\t");

        Path sources = repo.resolve("org/example/graph/lib/1.0/lib-1.0-sources.jar");
        Files.writeString(sources, "sources");
        assertThat(GraphRunner.libraryRows(root)).containsExactly("org.example.graph:lib:1.0\t" + jar + "\t" + sources);
    }
}
