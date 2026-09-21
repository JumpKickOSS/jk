// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

    /**
     * Every worktree of one repository writes the same identity file. The set keeps each live
     * checkout with its own stamp, drops a checkout whose directory is gone on the next write,
     * and {@code checkoutsForId} answers only the live ones.
     */
    @Test
    void identity_file_keeps_every_live_checkout_and_prunes_dead_ones(@TempDir Path tmp, @TempDir Path stateDir)
            throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        try {
            String id = "aabbccddeeff00112233445566778899";
            Path a = Files.createDirectories(tmp.resolve("wt-a"));
            Path b = Files.createDirectories(tmp.resolve("wt-b"));
            Path home = ProjectBuilds.projectHome(id);
            ProjectIdentity.IdentityFile.write(home, identityAt(id, a));
            ProjectIdentity.IdentityFile.write(home, identityAt(id, b));

            ProjectIdentity.IdentityFile both =
                    ProjectIdentity.IdentityFile.read(home).orElseThrow();
            assertThat(both.checkouts()).hasSize(2);
            assertThat(both.checkouts().stream().map(ProjectIdentity.Checkout::path))
                    .containsExactly(
                            a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
            assertThat(both.checkouts())
                    .allSatisfy(c -> assertThat(c.lastBuilt()).isNotNull());
            String text = Files.readString(home.resolve(ProjectBuilds.IDENTITY));
            assertThat(text).contains("[[checkout]]").contains("last-built = ");
            assertThat(text.substring(0, text.indexOf("[[checkout]]")))
                    .as("no path scalar beside the set")
                    .doesNotContain("path =");
            assertThat(ProjectIdentity.checkoutsForId(id)).hasSize(2);

            // A build in b after a's worktree was deleted drops a from the set.
            Files.delete(a);
            ProjectIdentity.IdentityFile.write(home, identityAt(id, b));
            ProjectIdentity.IdentityFile pruned =
                    ProjectIdentity.IdentityFile.read(home).orElseThrow();
            assertThat(pruned.checkouts().stream().map(ProjectIdentity.Checkout::path))
                    .containsExactly(b.toAbsolutePath().normalize());

            // A recorded checkout that vanished without a later write is still not live.
            Files.delete(b);
            assertThat(ProjectIdentity.checkoutsForId(id)).isEmpty();
            assertThat(ProjectIdentity.IdentityFile.read(home).orElseThrow().checkouts())
                    .hasSize(1);
        } finally {
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    @Test
    void selectCheckout_matches_by_real_path(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("wt-a"));
        Path b = Files.createDirectories(tmp.resolve("wt-b"));
        List<ProjectIdentity.Checkout> checkouts =
                List.of(new ProjectIdentity.Checkout(a, null), new ProjectIdentity.Checkout(b, null));
        assertThat(ProjectIdentity.selectCheckout(
                        checkouts, tmp.resolve("wt-b/../wt-b").toString()))
                .map(ProjectIdentity.Checkout::path)
                .contains(b.toAbsolutePath().normalize());
        assertThat(ProjectIdentity.selectCheckout(
                        checkouts, tmp.resolve("elsewhere").toString()))
                .isEmpty();
        assertThat(ProjectIdentity.selectCheckout(checkouts, "\0not a path")).isEmpty();
    }

    private static ProjectIdentity identityAt(String id, Path checkout) {
        return new ProjectIdentity(id, "com.example:demo", checkout, ProjectIdentity.Source.LOCK, null, null);
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

    /**
     * A git that neither exits nor closes its pipe (a credential prompt, a hung filesystem) must
     * fall to the probe's bound rather than hang the lock write that asked for the identity.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_stuck_probe_falls_to_its_timeout(@TempDir Path dir) throws Exception {
        Path stuck = script(dir, "stuck", "#!/bin/sh\necho partial\nsleep 30\n");
        long t0 = System.nanoTime();

        String out = ProjectIdentity.run(dir, Duration.ofMillis(300), List.of(stuck.toString()));

        assertThat(out).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_finished_probe_returns_its_output_and_a_failed_one_nothing(@TempDir Path dir) throws Exception {
        Path ok = script(dir, "ok", "#!/bin/sh\nprintf 'top\\n'\n");
        Path failing = script(dir, "failing", "#!/bin/sh\necho nope\nexit 1\n");

        assertThat(ProjectIdentity.run(dir, Duration.ofSeconds(5), List.of(ok.toString())))
                .isEqualTo("top\n");
        assertThat(ProjectIdentity.run(dir, Duration.ofSeconds(5), List.of(failing.toString())))
                .isNull();
        assertThat(ProjectIdentity.run(
                        dir,
                        Duration.ofSeconds(5),
                        List.of(dir.resolve("absent").toString())))
                .isNull();
    }

    private static Path script(Path dir, String name, String body) throws IOException {
        Path file = Files.writeString(dir.resolve(name), body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
    /**
     * A lock write parses no lock text: the identity it materializes comes from the rows in
     * memory, and the id an earlier lock recorded is scanned from the file's head. Reading the
     * lock back through the TOML parser costs hundreds of megabytes of heap on a megabyte lock.
     */
    @Test
    void writing_a_lock_parses_no_lock_text(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        Path lock = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test").withProjectId("aabbccddeeff00112233445566778899"), lock);
        LockfileReader.clearCache();
        long before = LockfileReader.parses();

        LockfileWriter.write(Lockfile.empty("test"), lock);
        LockfileWriter.write(Lockfile.empty("test").withProjectId("aabbccddeeff00112233445566778899"), lock);
        ProjectIdentity id = ProjectIdentity.resolve(dir);

        assertThat(LockfileReader.parses())
                .as("lock texts parsed by two writes and a resolve")
                .isEqualTo(before);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.LOCK);
        assertThat(id.id()).isEqualTo("aabbccddeeff00112233445566778899");
        assertThat(LockfileReader.read(lock).projectId())
                .as("a write without an id keeps the one on disk")
                .isEqualTo("aabbccddeeff00112233445566778899");
    }

    @Test
    void a_lock_held_in_memory_names_the_identity_without_a_file(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        Lockfile lock = Lockfile.empty("test").withProjectId("00112233445566778899aabbccddeeff");
        ProjectIdentity id = ProjectIdentity.resolve(dir, lock);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.LOCK);
        assertThat(id.id()).isEqualTo("00112233445566778899aabbccddeeff");
        assertThat(id.coord()).isEqualTo("com.example:demo");
    }

    /**
     * The id is scanned from the lock's head, so a lock this jk cannot read as a whole — one a
     * newer jk wrote in a schema this one refuses — still routes its history to the same project.
     */
    @Test
    void lock_id_is_read_from_a_lock_this_jk_cannot_parse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "demo"
                version = "0.1.0"
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 99
                generated-by = "jk 9.0.0"
                resolution-algorithm = "pubgrub-v1"
                project-id = "ffeeddccbbaa99887766554433221100"

                [[artifact]]
                name = "g:a:jar:"
                version = "1.0"
                source = "central"
                """);
        ProjectIdentity id = ProjectIdentity.resolve(dir);
        assertThat(id.source()).isEqualTo(ProjectIdentity.Source.LOCK);
        assertThat(id.id()).isEqualTo("ffeeddccbbaa99887766554433221100");
    }
}
