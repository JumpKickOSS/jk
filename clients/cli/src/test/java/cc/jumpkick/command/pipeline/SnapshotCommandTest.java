// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk snapshot}: the inner-loop install at the declared version plus {@code -SNAPSHOT}. */
@Tag("integration")
class SnapshotCommandTest {

    private static final String MANIFEST = """
            group     = "com.example"
            name      = "%s"
            version   = "1.0.0"
            jdk       = 25
            java      = 25
            """;

    private static void library(Path dir, String name, String body) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("jk.toml"), MANIFEST.formatted(name));
        Files.writeString(dir.resolve("src/main/java/com/example/Lib.java"), "package com.example; " + body + "\n");
    }

    private static int jk(Path dir, Path m2, String... verbAndFlags) {
        String[] common = {
            "-C",
            dir.toString(),
            "--cache-dir",
            dir.resolve("cache").toString(),
            "--state-dir",
            dir.resolve("state").toString(),
            "--bin-dir",
            dir.resolve("bin").toString(),
            "--lib-dir",
            dir.resolve("lib").toString(),
            "--m2-dir",
            m2.toString()
        };
        String[] args = new String[verbAndFlags.length + common.length];
        System.arraycopy(verbAndFlags, 0, args, 0, verbAndFlags.length);
        System.arraycopy(common, 0, args, verbAndFlags.length, common.length);
        return Jk.execute(args);
    }

    @Test
    void a_snapshot_is_installed_replaced_and_kept_beside_the_release(@TempDir Path tmp) throws Exception {
        library(tmp, "snap-lib", "public final class Lib { }");
        Path m2 = tmp.resolve("m2");
        Path snapshots = m2.resolve("repository/com/example/snap-lib/1.0.0-SNAPSHOT");
        Path release = m2.resolve("repository/com/example/snap-lib/1.0.0");
        String declared = Files.readString(tmp.resolve("jk.toml"));

        assertThat(jk(tmp, m2, "snapshot")).isEqualTo(0);
        assertThat(snapshots.resolve("snap-lib-1.0.0-SNAPSHOT.jar")).exists();
        assertThat(Files.readString(snapshots.resolve("snap-lib-1.0.0-SNAPSHOT.pom")))
                .contains("<version>1.0.0-SNAPSHOT</version>");
        assertThat(release).as("a snapshot is not a release").doesNotExist();
        byte[] first = Files.readAllBytes(snapshots.resolve("snap-lib-1.0.0-SNAPSHOT.jar"));

        // The inner loop: change the source, snapshot again, and the slot holds the new jar.
        library(tmp, "snap-lib", "public final class Lib { public static int two() { return 2; } }");
        assertThat(jk(tmp, m2, "snapshot")).isEqualTo(0);
        assertThat(Files.readAllBytes(snapshots.resolve("snap-lib-1.0.0-SNAPSHOT.jar")))
                .isNotEqualTo(first);

        // The release is installed once, beside the snapshot, from the same unedited manifest.
        assertThat(jk(tmp, m2, "install")).isEqualTo(0);
        assertThat(Files.readString(release.resolve("snap-lib-1.0.0.pom"))).contains("<version>1.0.0</version>");
        assertThat(snapshots.resolve("snap-lib-1.0.0-SNAPSHOT.jar")).exists();
        assertThat(Files.readString(tmp.resolve("jk.toml"))).isEqualTo(declared);
        assertThat(tmp.resolve("jk-lock.toml"))
                .as("a snapshot never writes the project's version into the lock")
                .satisfies(lock -> {
                    if (Files.exists(lock)) assertThat(Files.readString(lock)).doesNotContain("SNAPSHOT");
                });

        // Editing the source and installing the release again is refused, with the way out.
        library(tmp, "snap-lib", "public final class Lib { public static int three() { return 3; } }");
        assertThat(jk(tmp, m2, "install")).isNotEqualTo(0);
        assertThat(Files.readString(release.resolve("snap-lib-1.0.0.pom"))).contains("<version>1.0.0</version>");
    }

    @Test
    void a_workspace_snapshot_points_each_member_at_its_siblings_snapshot(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "snap-ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core/src/main/java/com/example"));
        Files.writeString(tmp.resolve("core/jk.toml"), "name = \"snap-core\"\n");
        Files.writeString(
                tmp.resolve("core/src/main/java/com/example/Core.java"),
                "package com.example; public final class Core { }\n");
        Files.createDirectories(tmp.resolve("app/src/main/java/com/example"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                name = "snap-app"

                [dependencies]
                core = { group = "com.example", name = "snap-core", version = "1.0.0" }
                """);
        Files.writeString(
                tmp.resolve("app/src/main/java/com/example/App.java"),
                "package com.example; public final class App { Core c; }\n");
        Path m2 = tmp.resolve("m2");

        assertThat(jk(tmp, m2, "snapshot")).isEqualTo(0);

        Path repo = m2.resolve("repository/com/example");
        assertThat(repo.resolve("snap-core/1.0.0-SNAPSHOT/snap-core-1.0.0-SNAPSHOT.jar"))
                .exists();
        String appPom = Files.readString(repo.resolve("snap-app/1.0.0-SNAPSHOT/snap-app-1.0.0-SNAPSHOT.pom"));
        assertThat(appPom).contains("<artifactId>snap-core</artifactId>").contains("<version>1.0.0-SNAPSHOT</version>");
        assertThat(repo.resolve("snap-core/1.0.0")).doesNotExist();
    }

    @Test
    void install_with_the_snapshot_flag_is_the_same_verb(@TempDir Path tmp) throws Exception {
        library(tmp, "snap-flag", "public final class Lib { }");
        Path m2 = tmp.resolve("m2");

        assertThat(jk(tmp, m2, "install", "--snapshot")).isEqualTo(0);

        Path repo = m2.resolve("repository/com/example/snap-flag");
        assertThat(repo.resolve("1.0.0-SNAPSHOT/snap-flag-1.0.0-SNAPSHOT.jar")).exists();
        assertThat(repo.resolve("1.0.0")).doesNotExist();
    }
}
