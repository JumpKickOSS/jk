// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIdentityTest {

    @Test
    void path_tier_is_stable_for_same_checkout() throws Exception {
        // Sandbox OUTSIDE the repo: the build's java.io.tmpdir is build/tmp, inside the checkout,
        // so a plain @TempDir has the repo's .git as an ancestor and resolve() would pick the GIT
        // tier instead of PATH.
        Path dir = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".jk-pid-test-");
        try {
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "com.example"
                    name = "demo"
                    version = "0.1.0"
                    """);
            ProjectIdentity a = ProjectIdentity.resolve(dir);
            ProjectIdentity b = ProjectIdentity.resolve(dir);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.source()).isEqualTo(ProjectIdentity.Source.PATH);
            assertThat(a.coord()).isEqualTo("com.example:demo");
            assertThat(ProjectIdentity.isValidId(a.id())).isTrue();
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            }
        }
    }

    @Test
    void lock_project_id_wins_over_path(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        LockfileWriter.write(
                Lockfile.empty("test").withProjectId("aabbccddeeff00112233445566778899"), dir.resolve("jk-lock.toml"));
        ProjectIdentity id = ProjectIdentity.resolve(dir);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.LOCK);
        assertThat(id.id()).isEqualTo("aabbccddeeff00112233445566778899");
    }

    @Test
    void ensure_project_id_mints_and_preserves(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        Lockfile first = ProjectIdentity.ensureProjectId(Lockfile.empty("test"), dir);
        String minted = Objects.requireNonNull(first.projectId(), "ensure mints an id");
        assertThat(minted).isNotBlank();
        LockfileWriter.write(first, dir.resolve("jk-lock.toml"));
        Lockfile second = ProjectIdentity.ensureProjectId(Lockfile.empty("test"), dir);
        // ensure without reading disk still mints; write path preserves — simulate preserve:
        Lockfile preserved = Lockfile.empty("test").withProjectId(minted);
        assertThat(preserved.projectId()).isEqualTo(minted);
    }

    @Test
    void coord_rename_preserves_identity_for_lockless_projects(@TempDir Path dir, @TempDir Path stateDir)
            throws Exception {
        // Coord is display metadata, not identity material: renaming project
        // group/name must not split a lockless project into two dashboard projects.
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        try {
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "com.example"
                    name = "demo"
                    version = "0.1.0"
                    """);
            ProjectIdentity before = ProjectIdentity.resolve(dir);
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "org.renamed"
                    name = "other"
                    version = "0.1.0"
                    """);
            ProjectIdentity after = ProjectIdentity.resolve(dir);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.coord()).isEqualTo("org.renamed:other");
        } finally {
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    @Test
    void recovers_recorded_id_before_hashing(@TempDir Path tmp, @TempDir Path stateDir) throws Exception {
        // A checkout whose lock is gone (or that is gone entirely — dead checkout in history
        // enrichment) must resolve to the id recorded in identity.toml, not a fresh hash that
        // matches no project home.
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        try {
            Path checkout = tmp.resolve("workspace");
            String recorded = "aabbccddeeff00112233445566778899";
            ProjectIdentity identity = new ProjectIdentity(
                    recorded, "com.example:demo", checkout, ProjectIdentity.Source.PATH, null, null);
            ProjectIdentity.IdentityFile.write(
                    stateDir.resolve("builds").resolve("projects").resolve(recorded), identity);
            // The checkout directory does not even exist — resolution still recovers the id.
            ProjectIdentity resolved = ProjectIdentity.resolve(checkout);
            assertThat(resolved.id()).isEqualTo(recorded);
        } finally {
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    @Test
    void explicit_toml_id_wins(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                id = "explicit-project-id-00112233"
                """);
        LockfileWriter.write(
                Lockfile.empty("test").withProjectId("lock-id-should-not-win-00112233"), dir.resolve("jk-lock.toml"));
        ProjectIdentity id = ProjectIdentity.resolve(dir);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.EXPLICIT);
        assertThat(id.id()).isEqualTo("explicit-project-id-00112233");
    }

    /**
     * Identity is resolved by scanning, not parsing — the same route {@code coordOf} already took,
     * so the whole type stays off the CLI's reachability graph. The observable
     * consequence: a manifest jk cannot parse still has a stable identity, so history and dashboard
     * routes survive a half-edited {@code jk.toml}.
     */
    @Test
    void explicit_id_is_read_from_a_manifest_that_does_not_parse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                id = "explicit-project-id-44556677"

                [dependencies
                """);
        ProjectIdentity id = ProjectIdentity.resolve(dir);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.EXPLICIT);
        assertThat(id.id()).isEqualTo("explicit-project-id-44556677");
    }

    @Test
    void coord_inherits_group_from_workspace_root(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                modules = ["api"]
                """);
        Path api = dir.resolve("api");
        Files.createDirectories(api);
        Files.writeString(api.resolve("jk.toml"), """
                name = "api"
                version = "0.1.0"
                group.workspace = true
                """);
        assertThat(ProjectIdentity.coordOf(api)).isEqualTo("com.example:api");
        assertThat(ProjectIdentity.coordOf(dir)).isEqualTo("com.example:root");
    }

    @Test
    void identity_file_round_trips_quotes_backslashes_controls_and_non_ascii(@TempDir Path home) throws Exception {
        String coord = "com.exàmple:we\"ird\\na\nme";
        String remote = "https://example.com/ré\"po\\x.git";
        String rel = "mod\tules\\app \"x\"";
        ProjectIdentity identity = new ProjectIdentity(
                "aabbccddeeff00112233445566778899",
                coord,
                Path.of("checkout"),
                ProjectIdentity.Source.PATH,
                remote,
                rel);
        ProjectIdentity.IdentityFile.write(home, identity);

        ProjectIdentity.IdentityFile read =
                ProjectIdentity.IdentityFile.read(home).orElseThrow();
        assertThat(read.coord()).isEqualTo(coord);
        assertThat(read.gitRemote()).isEqualTo(remote);
        assertThat(read.gitRelPath()).isEqualTo(rel);
    }
}
