// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Taking a release's installed jars back out of the shelf and the Maven local repository. */
class InstalledReleaseTest {

    private static final Coordinate RELEASE = Coordinate.of("com.example", "lib", "1.4.7");
    private static final Coordinate SNAPSHOT = Coordinate.of("com.example", "lib", "1.4.7-SNAPSHOT");

    private static Path put(Path root, Coordinate c, String jar) throws Exception {
        Path j = root.resolve(MavenLayout.artifactPath(c));
        Files.createDirectories(j.getParent());
        Files.writeString(j, jar);
        Files.writeString(root.resolve(MavenLayout.pomPath(c)), "<project/>");
        return j;
    }

    @Test
    void the_shelf_slot_goes_whole_and_other_versions_stay(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path shelf = store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        Path jar = put(shelf, RELEASE, "release");
        Files.writeString(jar.resolveSibling("lib-1.4.7.jk"), "memo");
        Path snapshot = put(shelf, SNAPSHOT, "snapshot");

        InstalledRelease.Withdrawn w = InstalledRelease.withdraw(store, tmp.resolve("m2"), List.of(RELEASE));

        assertThat(jar.getParent()).doesNotExist();
        assertThat(snapshot).exists();
        assertThat(w.removed()).containsExactly(jar.getParent());
    }

    @Test
    void a_maven_local_slot_goes_only_when_jk_wrote_what_is_there(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path m2 = tmp.resolve("m2");
        Path jar = put(m2, RELEASE, "release");
        Path pom = m2.resolve(MavenLayout.pomPath(RELEASE));
        Files.writeString(jar.resolveSibling(jar.getFileName() + ".sha1"), "x");
        Path memo = M2InstallMemo.file(store, m2, RELEASE);

        InstalledRelease.Withdrawn foreign = InstalledRelease.withdraw(store, m2, List.of(RELEASE));
        assertThat(jar).as("no memo: not jk's").exists();
        assertThat(foreign.foreign()).containsExactly(jar.getParent());

        new M2InstallMemo(RELEASE.toGav(), Hashing.sha256Hex(jar), Hashing.sha256Hex(pom)).write(memo);
        InstalledRelease.Withdrawn ours = InstalledRelease.withdraw(store, m2, List.of(RELEASE));

        assertThat(jar.getParent()).doesNotExist();
        assertThat(memo).doesNotExist();
        assertThat(ours.foreign()).isEmpty();
    }
}
