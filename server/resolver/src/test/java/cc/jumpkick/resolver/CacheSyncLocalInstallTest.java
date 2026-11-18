// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.LocalInstall;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock row this machine's installs served ({@code source = "local-install"}) is read where an
 * install keeps it, never fetched: either install that holds the pinned bytes answers, and a
 * machine whose installs do not hold them fails the sync naming the row and the way out — which is
 * also what makes the lock stale ({@link LocalInstall#moved}).
 */
class CacheSyncLocalInstallTest {

    private static final String JAR = "zz/local/liba/0.1.0-SNAPSHOT/liba-0.1.0-SNAPSHOT.jar";

    /** A Maven local repository of each test's own. */
    @BeforeEach
    void isolateM2(@TempDir Path m2) {
        System.setProperty(M2Dirs.LOCAL_PROPERTY, m2.toString());
    }

    private static Path write(Path repo, String body) throws IOException {
        Path jar = repo.resolve(JAR);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, body, StandardCharsets.UTF_8);
        return jar;
    }

    private static Lockfile lockOf(String body) {
        String sha = Hashing.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "zz.local:liba", "0.1.0-SNAPSHOT", LocalInstall.SOURCE, "sha256:" + sha, null, List.of())));
    }

    private static CacheSync.Report sync(Path store, Lockfile lock) throws Exception {
        return new CacheSync(new Cas(store), new Http(), true).sync(lock);
    }

    @Test
    void a_row_the_maven_local_repository_holds_is_up_to_date(@TempDir Path dir) throws Exception {
        write(M2Dirs.localRepository(), "installed");
        Lockfile lock = lockOf("installed");

        CacheSync.Report report = sync(dir.resolve("store"), lock);

        assertThat(report.errors()).isEmpty();
        assertThat(report.upToDate()).isEqualTo(1);
        assertThat(report.fetched()).isZero();
        assertThat(LocalInstall.moved(lock, dir.resolve("store"))).isNull();
    }

    @Test
    void a_row_the_shelf_holds_is_up_to_date(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        write(store.resolve("repos").resolve(RepositorySpec.JK_LOCAL), "shelved");

        CacheSync.Report report = sync(store, lockOf("shelved"));

        assertThat(report.errors()).isEmpty();
        assertThat(report.upToDate()).isEqualTo(1);
    }

    @Test
    void a_row_no_install_here_holds_fails_naming_it_and_the_way_out(@TempDir Path dir) throws Exception {
        Lockfile lock = lockOf("built elsewhere");

        CacheSync.Report report = sync(dir.resolve("store"), lock);

        assertThat(report.errors())
                .singleElement()
                .asString()
                .startsWith("zz.local:liba:0.1.0-SNAPSHOT: the lock took it from a jk install")
                .contains("run jk install in its project on this machine")
                .contains("add a repository that serves it to [repositories]");
        assertThat(LocalInstall.moved(lock, dir.resolve("store"))).isNotNull();
    }

    @Test
    void a_row_whose_install_was_rebuilt_is_moved(@TempDir Path dir) throws Exception {
        Path jar = write(M2Dirs.localRepository(), "first build");
        Lockfile lock = lockOf("first build");
        assertThat(LocalInstall.moved(lock, dir.resolve("store"))).isNull();

        Files.writeString(jar, "second build, installed again", StandardCharsets.UTF_8);

        assertThat(LocalInstall.moved(lock, dir.resolve("store")))
                .extracting(Lockfile.Artifact::name)
                .isEqualTo("zz.local:liba");
        assertThat(sync(dir.resolve("store"), lock).errors()).hasSize(1);
        assertThat(jar).as("a moved install is never deleted").exists();
    }

    @Test
    void the_refusal_names_the_installs_asked_and_both_ways_out(@TempDir Path dir) {
        Cas cas = new Cas(dir.resolve("store"));
        List<MavenRepo> installs = List.of(
                new MavenRepo(RepositorySpec.LOCAL_INSTALL, dir.resolve("m2").toUri(), new Http(), cas),
                new MavenRepo(RepositorySpec.LOCAL_INSTALL, dir.resolve("shelf").toUri(), new Http(), cas));

        String note = LocalInstallNotes.snapshot(
                "0.1.0-SNAPSHOT", "zz.local:liba", List.of(), List.of("central (releases only)"), installs);

        assertThat(note)
                .startsWith("0.1.0-SNAPSHOT is a snapshot, not installed on this machine (")
                .contains(dir.resolve("m2").toString())
                .contains("no repository for zz.local:liba serves snapshots: central (releases only)")
                .contains("run jk install in its project on this machine, or add a repository that serves it")
                .endsWith(LocalInstallNotes.SEE);
        assertThat(LocalInstallNotes.snapshot(
                        "0.1.0-SNAPSHOT", "zz.local:liba", List.of(), List.of("corp (releases only)"), List.of()))
                .as("a group a repository claims never reached the installs, and the note says nothing of them")
                .isEqualTo("0.1.0-SNAPSHOT is a snapshot and no repository for zz.local:liba serves snapshots:"
                        + " corp (releases only); add one to [repositories]");
    }
}
