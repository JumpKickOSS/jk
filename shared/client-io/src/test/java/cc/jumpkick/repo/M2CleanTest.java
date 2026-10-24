// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.AccessLedger;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M2CleanTest {

    private static final URI CENTRAL = RepositorySpec.MAVEN_CENTRAL.url();
    private static final Duration SIXTY_DAYS = Duration.ofDays(60);

    @TempDir
    Path tmp;

    private Path store;
    private Path m2;
    private RepoArtifactStore central;

    @BeforeEach
    void setUp() {
        store = tmp.resolve("store");
        m2 = tmp.resolve("m2");
        System.setProperty("jk.m2.local", m2.toString());
        central = RepoArtifactStore.forRepository(store, "central", CENTRAL);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jk.m2.local");
    }

    /** A file jk downloaded and last used {@code daysAgo} days ago: in the local repository, with a memo that says so. */
    private String download(String rel, String content, long daysAgo) throws IOException {
        Path source = Files.writeString(tmp.resolve("dl-" + Math.abs(rel.hashCode())), content);
        String sha = Hashing.sha256Hex(source);
        central.materialize(rel, source, sha, true);
        Path placed = m2.resolve(rel);
        // The file's own mtime is when it was downloaded; the memo's is when it was last used.
        central.writeMemo(rel, placed, sha, true);
        Files.setLastModifiedTime(
                ArtifactMemo.jkPath(store.resolve("repos/central"), rel),
                FileTime.fromMillis(System.currentTimeMillis() - daysAgo * 86_400_000L));
        return sha;
    }

    private M2Clean.Report run(Set<String> pinned, boolean dryRun) throws IOException {
        return M2Clean.run(store, m2, SIXTY_DAYS, pinned, System.currentTimeMillis(), dryRun);
    }

    @Test
    void a_file_jk_downloaded_long_ago_and_nothing_pins_goes_with_its_memo_and_checksum_files() throws Exception {
        String rel = "com/example/lib/1.0/lib-1.0.jar";
        download(rel, "old-bytes", 90);
        Path jar = m2.resolve(rel);
        Files.writeString(jar.resolveSibling("lib-1.0.jar.sha1"), "x");
        Files.writeString(jar.resolveSibling("_remote.repositories"), "lib-1.0.jar>central=\n");

        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).extracting(M2Clean.Removed::relativePath).containsExactly(rel);
        assertThat(jar).doesNotExist();
        assertThat(jar.resolveSibling("lib-1.0.jar.sha1")).doesNotExist();
        assertThat(ArtifactMemo.jkPath(store.resolve("repos/central"), rel)).doesNotExist();
        assertThat(m2.resolve("com"))
                .as("empty directories go too, the repository root stays")
                .doesNotExist();
        assertThat(m2).exists();
    }

    @Test
    void a_file_jk_only_verified_is_not_jks_to_remove() throws Exception {
        String rel = "com/example/theirs/1.0/theirs-1.0.jar";
        Path theirs = m2.resolve(rel);
        Files.createDirectories(theirs.getParent());
        Files.writeString(theirs, "maven-wrote-this");
        Files.setLastModifiedTime(theirs, FileTime.fromMillis(0));
        central.writeMemo(rel, theirs, Hashing.sha256Hex(theirs)); // verified, not downloaded

        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).isEmpty();
        assertThat(theirs).exists();
    }

    @Test
    void a_file_changed_since_jk_downloaded_it_is_left_and_counted() throws Exception {
        String rel = "com/example/lib/1.0/lib-1.0.jar";
        download(rel, "original", 90);
        Path jar = m2.resolve(rel);
        // Same size, new bytes and an mtime the memo does not know: the user patched it.
        Files.writeString(jar, "patched!");
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis()));

        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).isEmpty();
        assertThat(report.foreign()).isEqualTo(1);
        assertThat(jar).hasContent("patched!");
    }

    @Test
    void the_same_size_and_mtime_with_other_bytes_is_still_not_proof() throws Exception {
        String rel = "com/example/lib/1.0/lib-1.0.jar";
        download(rel, "original", 90);
        Path jar = m2.resolve(rel);
        FileTime mtime = Files.getLastModifiedTime(jar);
        Files.writeString(jar, "0riginal"); // eight bytes either way
        Files.setLastModifiedTime(jar, mtime);

        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).isEmpty();
        assertThat(report.foreign()).isEqualTo(1);
        assertThat(jar).exists();
    }

    @Test
    void a_pinned_or_young_download_stays() throws Exception {
        String pinnedRel = "com/example/pinned/1.0/pinned-1.0.jar";
        String youngRel = "com/example/young/1.0/young-1.0.jar";
        String sha = download(pinnedRel, "locked-bytes", 200);
        download(youngRel, "fresh-bytes", 5);

        M2Clean.Report report = run(Set.of(sha), false);

        assertThat(report.removed()).isEmpty();
        assertThat(report.pinned()).isEqualTo(1);
        assertThat(report.young()).isEqualTo(1);
        assertThat(m2.resolve(pinnedRel)).exists();
        assertThat(m2.resolve(youngRel)).exists();
    }

    @Test
    void a_download_used_since_is_not_old_however_long_ago_it_was_fetched() throws Exception {
        String rel = "com/example/lib/1.0/lib-1.0.jar";
        String sha = download(rel, "hot-bytes", 90);

        // A build verifies it against its lock row today: that is a use.
        assertThat(central.verify(rel, sha)).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).isEmpty();
        assertThat(report.young()).isEqualTo(1);
        assertThat(m2.resolve(rel)).exists();
    }

    /** Twelve different days of use earn nearly three times the window, so a dependency in steady use is not churned. */
    @Test
    void a_download_used_on_many_days_keeps_a_longer_window() throws Exception {
        String rel = "com/example/steady/1.0/steady-1.0.jar";
        String plain = "com/example/plain/1.0/plain-1.0.jar";
        download(rel, "steady", 90);
        download(plain, "plain", 90);
        AccessLedger ledger = AccessLedger.at(store.resolve("repos/central").resolve(RepoArtifactStore.ACCESS_DIR));
        long now = System.currentTimeMillis();
        for (int day = 91; day >= 80; day--) {
            ledger.touch(
                    RepoArtifactStore.useKey(
                            store.resolve("repos/central"), ArtifactMemo.jkPath(store.resolve("repos/central"), rel)),
                    now - Duration.ofDays(day).toMillis() + 10 * day);
        }

        M2Clean.Report report = run(Set.of(), false);

        assertThat(report.removed()).extracting(M2Clean.Removed::relativePath).containsExactly(plain);
        assertThat(m2.resolve(rel)).exists();
    }

    @Test
    void a_dry_run_names_what_would_go_and_removes_nothing() throws Exception {
        String rel = "com/example/lib/1.0/lib-1.0.jar";
        download(rel, "old-bytes", 90);

        M2Clean.Report report = run(Set.of(), true);

        assertThat(report.removed()).hasSize(1);
        assertThat(report.freedBytes()).isEqualTo("old-bytes".length());
        assertThat(m2.resolve(rel)).exists();
    }

    @Test
    void a_removed_files_line_leaves_its_siblings_in_remote_repositories() throws Exception {
        String jarRel = "com/example/lib/1.0/lib-1.0.jar";
        String pomRel = "com/example/lib/1.0/lib-1.0.pom";
        download(jarRel, "jar-bytes", 90);
        String pomSha = download(pomRel, "<project/>", 90);
        Path hint = m2.resolve("com/example/lib/1.0/_remote.repositories");
        Files.writeString(hint, "lib-1.0.jar>central=\nlib-1.0.pom>central=\n");

        M2Clean.run(store, m2, SIXTY_DAYS, Set.of(pomSha), System.currentTimeMillis(), false);

        assertThat(m2.resolve(jarRel)).doesNotExist();
        assertThat(m2.resolve(pomRel)).exists();
        assertThat(hint).hasContent("lib-1.0.pom>central=\n");
    }
}
