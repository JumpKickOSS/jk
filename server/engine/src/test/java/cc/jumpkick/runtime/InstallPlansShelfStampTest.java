// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shelf whose bytes an install finds already in place is stamped with the verifying engine, so
 * the packager a memo names is the last engine an install ran under — not the first that ever
 * produced those bytes.
 */
class InstallPlansShelfStampTest {

    private static final Coordinate COORD = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
    private static final String FIRST = "a".repeat(64);
    private static final String LIVE = "b".repeat(64);

    @Test
    void an_already_shelved_artifact_takes_the_verifying_engine_as_its_packager(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            Path jar = tmp.resolve("jk-foo.jar");
            Files.writeString(jar, "jar-bytes");
            Path pom = tmp.resolve("jk-foo.pom");
            Files.writeString(pom, "<project/>");
            InstallPlans.writeToLocalStore(tmp.resolve("cache"), MavenLayout.artifactPath(COORD), jar);
            InstallPlans.writeToLocalStore(tmp.resolve("cache"), MavenLayout.pomPath(COORD), pom);
            Path shelf = store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
            Path jarMemo = ArtifactMemo.jkPath(shelf, MavenLayout.artifactPath(COORD));
            Path pomMemo = ArtifactMemo.jkPath(shelf, MavenLayout.pomPath(COORD));
            ArtifactMemo before = ArtifactMemo.read(jarMemo).orElseThrow();
            before.withPackagedBy(FIRST).write(jarMemo);

            InstallPlans.stampShelfPackager(COORD, LIVE);

            ArtifactMemo stamped = ArtifactMemo.read(jarMemo).orElseThrow();
            assertThat(stamped.packagedBy()).isEqualTo(LIVE);
            assertThat(stamped.sha256()).isEqualTo(before.sha256());
            assertThat(stamped.size()).isEqualTo(before.size());
            assertThat(ArtifactMemo.read(pomMemo).orElseThrow().packagedBy()).isEqualTo(LIVE);

            // An engine without an identity leaves the memo alone.
            InstallPlans.stampShelfPackager(COORD, "");
            assertThat(ArtifactMemo.read(jarMemo).orElseThrow().packagedBy()).isEqualTo(LIVE);
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }
}
