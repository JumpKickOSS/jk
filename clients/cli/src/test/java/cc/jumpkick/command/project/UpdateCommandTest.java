// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.BlankProject;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.cli.testing.TranscriptOf;
import cc.jumpkick.command.DefaultTestDepsFixture;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.SysProps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class UpdateCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @BeforeEach
    void seedRepo() {
        DefaultTestDepsFixture.seed(maven.served());
    }

    @AfterEach
    void reset() {
        LockfileReader.clearCache();
    }

    @Test
    void update_offline_resolves_from_journal(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        // Warm cache + journal online.
        BlankProject.write(tempDir.toString());
        run("add", "--offline", "com.foo.update:leaf:1.0", "-C", tempDir.toString());
        assertThat(run(
                        "update",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        maven.base().toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        Files.delete(tempDir.resolve("jk-lock.toml"));

        // Offline re-solve must come entirely from the journal. The store is keyed by origin, so
        // the offline solve names the same repository the warm-up fetched from.
        maven.stop();
        int exit = run(
                "update",
                "--offline",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo.update:leaf");
    }

    @Test
    void update_rewrites_lockfile_after_dep_added(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        // Initial state: no deps.
        BlankProject.write(tempDir.toString());
        run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        Lockfile initial = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(initial)).isEmpty();

        // Add a dep, then update.
        run("add", "--offline", "com.foo.update:leaf:1.0", "-C", tempDir.toString());
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile updated = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(updated)).containsExactly("com.foo.update:leaf");
    }

    /** The run record's transcript line names a file the command wrote, as a lock's does. */
    @Test
    void update_writes_the_transcript_its_run_record_names(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        BlankProject.write(tempDir.toString());
        run("add", "--offline", "com.foo.update:leaf:1.0", "-C", tempDir.toString());

        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(TranscriptOf.results(tempDir)).exists();
    }

    /**
     * Without a terminal and without {@code -y}, moves across a minor or a major are held and named,
     * the rest apply, and the run exits 0; with every move held nothing changes; {@code -y} takes
     * them all.
     */
    @Test
    void update_without_a_terminal_holds_moves_across_a_minor_and_yes_takes_them(@TempDir Path tempDir)
            throws Exception {
        for (String artifact : new String[] {"patchy", "minory", "bothy"}) {
            for (String v : new String[] {"1.0.0", "1.0.1", "1.1.0"}) {
                maven.registerPom("com.foo.update", artifact, v, pom("com.foo.update", artifact, v, ""));
                maven.registerJar("com.foo.update", artifact, v, artifact.getBytes(StandardCharsets.UTF_8));
            }
        }
        maven.registerMetadata("com.foo.update", "patchy", "1.0.0", "1.0.1");
        maven.registerMetadata("com.foo.update", "minory", "1.0.1", "1.1.0");
        maven.registerMetadata("com.foo.update", "bothy", "1.0.0", "1.0.1", "1.1.0");
        BlankProject.write(tempDir.toString());
        Path manifest = tempDir.resolve("jk.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest)
                        + "\n[dependencies]\npatchy = \"com.foo.update:patchy:1.0.0\"\n"
                        + "minory = \"com.foo.update:minory:1.0.1\"\n"
                        + "bothy = \"com.foo.update:bothy:1.0.0\"\n");
        String[] update = {
            "update",
            "-C",
            tempDir.toString(),
            "--repo-url",
            maven.base().toString(),
            "--cache-dir",
            tempDir.resolve("cache").toString()
        };

        int[] exit = {-1};
        String mixed = TestAnsi.strip(Capture.stdout(() -> exit[0] = run(update)));
        assertThat(exit[0]).isZero();
        assertThat(Files.readString(manifest))
                .contains("com.foo.update:patchy:1.0.1")
                .contains("com.foo.update:minory:1.0.1")
                .contains("com.foo.update:bothy:1.0.1");
        assertThat(mixed)
                .contains("held 2 pins across a minor or a major; jk update -y takes them")
                .contains("minory  1.0.1 → 1.1.0")
                .contains("bothy  1.0.0 → 1.0.1")
                .as("a pin that takes its patch is held from the patch, and named once as held")
                .contains("bothy  1.0.1 → 1.1.0")
                .doesNotContain("bothy  1.0.0 → 1.1.0")
                .doesNotContain("nothing moved");

        String lock = Files.readString(tempDir.resolve("jk-lock.toml"));
        String held = TestAnsi.strip(Capture.stdout(() -> exit[0] = run(update)));
        assertThat(exit[0]).isZero();
        assertThat(held).contains("nothing moved; held 2 pins across a minor or a major; jk update -y takes them");
        assertThat(Files.readString(tempDir.resolve("jk-lock.toml"))).isEqualTo(lock);

        String[] yes = Arrays.copyOf(update, update.length + 1);
        yes[update.length] = "-y";
        String taken = TestAnsi.strip(Capture.stdout(() -> exit[0] = run(yes)));
        assertThat(exit[0]).isZero();
        assertThat(Files.readString(manifest))
                .contains("com.foo.update:minory:1.1.0")
                .contains("com.foo.update:bothy:1.1.0");
        assertThat(taken).doesNotContain("held");
    }

    /**
     * Without a terminal and without {@code -y}, a move whose relock adds packages to the lock is held:
     * its pin and the lock stay as if it were not taken, the verdict and {@code jk-results.md} name
     * the packages and {@code jk update -y}, the other moves apply, and the run exits 0. {@code -y}
     * takes it.
     */
    @Test
    void update_without_a_terminal_holds_a_move_that_adds_packages_and_yes_takes_it(@TempDir Path tempDir)
            throws Exception {
        String extra = "<dependency><groupId>com.foo.update</groupId><artifactId>extra</artifactId>"
                + "<version>1.0</version></dependency>";
        maven.registerPom("com.foo.update", "extra", "1.0", pom("com.foo.update", "extra", "1.0", ""));
        maven.registerJar("com.foo.update", "extra", "1.0", "extra".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo.update", "extra", "1.0");
        for (String artifact : new String[] {"growy", "plainy"}) {
            for (String v : new String[] {"1.0.0", "1.0.1"}) {
                String deps = artifact.equals("growy") && v.equals("1.0.1") ? extra : "";
                maven.registerPom("com.foo.update", artifact, v, pom("com.foo.update", artifact, v, deps));
                maven.registerJar("com.foo.update", artifact, v, artifact.getBytes(StandardCharsets.UTF_8));
            }
            maven.registerMetadata("com.foo.update", artifact, "1.0.0", "1.0.1");
        }
        BlankProject.write(tempDir.toString());
        Path manifest = tempDir.resolve("jk.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest)
                        + "\n[dependencies]\ngrowy = \"com.foo.update:growy:1.0.0\"\n"
                        + "plainy = \"com.foo.update:plainy:1.0.0\"\n");
        String[] tail = {
            "-C",
            tempDir.toString(),
            "--repo-url",
            maven.base().toString(),
            "--cache-dir",
            tempDir.resolve("cache").toString()
        };
        String[] lock = new String[tail.length + 1];
        lock[0] = "lock";
        System.arraycopy(tail, 0, lock, 1, tail.length);
        assertThat(run(lock)).isZero();
        Path lockFile = tempDir.resolve("jk-lock.toml");
        assertThat(Files.readString(lockFile)).doesNotContain("com.foo.update:extra");
        String[] update = lock.clone();
        update[0] = "update";

        int[] exit = {-1};
        String held = TestAnsi.strip(Capture.stdout(() -> exit[0] = run(update)));
        assertThat(exit[0]).isZero();
        assertThat(Files.readString(manifest))
                .contains("com.foo.update:growy:1.0.0")
                .contains("com.foo.update:plainy:1.0.1");
        assertThat(Files.readString(lockFile)).doesNotContain("com.foo.update:extra");
        assertThat(held)
                .contains("plainy  1.0.0 → 1.0.1")
                .contains("held 1 pin whose relock adds packages; jk update -y takes them")
                .contains("growy  1.0.0 → 1.0.1  (adds com.foo.update:extra 1.0)");
        assertThat(Files.readString(tempDir.resolve("target/jk-results.md")))
                .contains("held 1 pin whose relock adds packages")
                .contains("com.foo.update:extra 1.0");

        String[] yes = Arrays.copyOf(update, update.length + 1);
        yes[update.length] = "-y";
        String taken = TestAnsi.strip(Capture.stdout(() -> exit[0] = run(yes)));
        assertThat(exit[0]).isZero();
        assertThat(Files.readString(manifest)).contains("com.foo.update:growy:1.0.1");
        assertThat(Files.readString(lockFile)).contains("com.foo.update:extra");
        assertThat(taken).doesNotContain("held");
    }

    @Test
    void update_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void update_from_module_dir_locks_module_only(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.update", "leaf", "1.0");
        maven.registerPom("com.foo.update", "leaf", "1.0", pom("com.foo.update", "leaf", "1.0", ""));
        maven.registerJar("com.foo.update", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                leaf = { group = "com.foo.update",  name = "leaf", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from module — still writes the workspace root lock (full union).
        int exit = run(
                "update",
                "-C",
                app.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        assertThat(Files.exists(tempDir.resolve("jk-lock.toml"))).isTrue();
        assertThat(Files.exists(app.resolve("jk-lock.toml"))).isFalse();
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk-lock.toml"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo.update:leaf");
    }
}
