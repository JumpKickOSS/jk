// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class LibrarySearchCommandTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void capture() {
        out = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
    }

    @Test
    void search_matches_substring_of_name_in_bundled_catalog(@TempDir Path tempHome) throws Exception {
        // Only the bundled layer may load: a downloaded store/libs.global.toml shadows the
        // curated rows as "global". user.home no longer isolates anything (JkDirs resolves via
        // JK_HOME env since the platform-native layout), so move the downloaded catalog aside.
        Path downloaded = cc.jumpkick.util.JkDirs.libraryRegistry();
        Path aside = downloaded.resolveSibling(downloaded.getFileName() + ".test-aside");
        boolean moved = false;
        try {
            if (Files.exists(downloaded)) {
                Files.move(downloaded, aside, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
            int exit = Jk.execute("library", "search", "junit", "--show-layer");
            assertThat(exit).isZero();
            String stdout = out.toString(StandardCharsets.UTF_8);
            assertThat(stdout).contains("junit-jupiter");
            assertThat(stdout).contains("junit-platform-launcher");
            // The layer is opt-in via --show-layer; with it, a Layer column tags bundled rows.
            assertThat(stdout).contains("Layer");
            assertThat(stdout).contains("bundled");
        } finally {
            if (moved) {
                Files.move(aside, downloaded, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    void search_matches_substring_of_group() {
        // "springframework" appears in groups (org.springframework.boot) but
        // not in names — exercises the group-field branch of the matcher.
        int exit = Jk.execute("library", "search", "springframework");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        assertThat(stdout).contains("org.springframework.boot");
    }

    @Test
    void search_AND_semantics_with_multiple_terms() {
        // Both "spring" and "starter" must appear somewhere.
        int exit = Jk.execute("library", "search", "spring", "starter");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("spring-boot-starter");
        // But NOT something that has spring but no starter — there is no
        // such bundled entry, so this is implicit.
    }

    @Test
    void search_is_case_insensitive() {
        int exit = Jk.execute("library", "search", "PICOCLI");
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("picocli");
    }

    @Test
    void search_with_no_matches_returns_nonzero_and_clear_message() {
        int exit = Jk.execute("library", "search", "definitely-not-in-registry-xyz");
        assertThat(exit).isOne();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No matches");
    }

    @Test
    void offline_restricts_to_cached_coords_and_annotates_versions(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // Cache one of the two "junit" curated coords.
        Coordinate coord = Coordinate.of("org.junit.jupiter", "junit-jupiter", "6.1.0");
        byte[] bytes = "junit-jar".getBytes(StandardCharsets.UTF_8);
        Path blob = new Cas(cache).put(bytes);
        String seededRel = MavenLayout.artifactPath(coord);
        RepoArtifactStore.forRepoName(cc.jumpkick.cache.JkStores.store(), "central")
                .materialize(seededRel, blob, Hashing.sha256Hex(bytes));

        try {
            int exit = Jk.execute("library", "search", "junit", "--offline", "--cache-dir", cache.toString());
            assertThat(exit).isZero();
            String stdout = out.toString(StandardCharsets.UTF_8);
            // The seeded coord is shown with its local version in the Cached column. The store is
            // the suite-shared JK_HOME store (repos live there, JK-2176), so other tests' real
            // syncs may legitimately add rows — assert on the seed, never on absence.
            assertThat(stdout).contains("junit-jupiter").contains("6.1.0");
        } finally {
            // A fake blob for a REAL coordinate poisons later offline locks that pin it (JK-2179).
            Path seeded =
                    cc.jumpkick.cache.JkStores.store().resolve("repos/central").resolve(seededRel);
            Files.deleteIfExists(Path.of(seeded + ".sha256"));
            Files.deleteIfExists(seeded);
        }
    }

    @Test
    void offline_with_nothing_cached_reports_no_local_matches(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // The store is suite-shared (JK-2176): scrub the searched family so this test is
        // order-independent — nothing else in the suite syncs commons-io.
        Path repos = cc.jumpkick.cache.JkStores.store().resolve("repos");
        if (Files.isDirectory(repos)) {
            try (var names = Files.list(repos)) {
                for (Path repo : names.toList()) {
                    cc.jumpkick.host.PathUtil.deleteRecursively(repo.resolve("commons-io"));
                }
            }
        }
        int exit = Jk.execute("library", "search", "commons-io", "--offline", "--cache-dir", cache.toString());
        assertThat(exit).isOne();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No matches (cached locally)");
    }

    @Test
    void search_limit_truncates_with_explanatory_footer() {
        int exit = Jk.execute("library", "search", "kotlin", "--limit", "1");
        assertThat(exit).isZero();
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("more");
    }
}
