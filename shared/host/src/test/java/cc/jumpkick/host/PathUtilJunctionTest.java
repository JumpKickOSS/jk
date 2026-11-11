// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Windows junction inside a tree is a leaf of the delete, never a door: Java reports it as a
 * directory, and a walk that entered it would empty whatever it points at.
 */
@EnabledOnOs(OS.WINDOWS)
class PathUtilJunctionTest {

    private static void junction(Path link, Path target) throws Exception {
        Processes.Captured run = Processes.capture(
                List.of("cmd", "/c", "mklink", "/J", link.toString(), target.toString()), Duration.ofSeconds(30));
        if (!run.ok()) throw new IOException("mklink /J failed: " + run.output().strip());
    }

    @Test
    void a_junction_is_removed_without_touching_its_target(@TempDir Path tmp) throws Exception {
        Path jdk = Files.createDirectories(tmp.resolve("jdk"));
        Files.createDirectories(jdk.resolve("lib"));
        Files.writeString(jdk.resolve("lib").resolve("jvm.cfg"), "-server KNOWN");
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=25");

        Path sandbox = Files.createDirectories(tmp.resolve("sandbox"));
        Files.writeString(sandbox.resolve("own.txt"), "own");
        Path jdks = Files.createDirectories(sandbox.resolve("jdks"));
        junction(jdks.resolve("temurin-25"), jdk);

        PathUtil.Removed counted = PathUtil.measureTrees(List.of(sandbox));
        assertThat(counted.files()).as("the junction's target is not counted").isEqualTo(1);

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(List.of(sandbox), tally);

        assertThat(sandbox).doesNotExist();
        assertThat(jdk.resolve("lib").resolve("jvm.cfg")).exists();
        assertThat(jdk.resolve("release")).exists();
        assertThat(tally.files()).isEqualTo(1);
    }

    @Test
    void a_junction_as_the_root_is_one_delete(@TempDir Path tmp) throws Exception {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.writeString(target.resolve("keep.txt"), "keep");
        Path link = tmp.resolve("link");
        junction(link, target);

        PathUtil.deleteRecursivelyOrThrow(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(target.resolve("keep.txt")).exists();
    }
}
