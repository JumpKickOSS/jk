// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ShortTempDirsTest {

    @RegisterExtension
    final ShortTempDirs dirs = new ShortTempDirs("jk-st-");

    @Test
    void path_is_under_user_home_and_not_a_drive_root() {
        Path home = Path.of(System.getProperty("user.home"));
        Path p = ShortTempDirs.path();
        assertThat(p).isEqualTo(home.resolve(ShortTempDirs.DIR));
        assertThat(p.isAbsolute()).isTrue();
        assertThat(p.startsWith(home)).isTrue();
        assertThat(p.getNameCount())
                .as("C:\\ and / are drive/volume roots; fixtures must sit under the user home")
                .isGreaterThan(1);
    }

    @Test
    void create_lands_under_this_jvms_pid_dir() throws Exception {
        Path dir = dirs.create();
        assertThat(dir).isDirectory();
        Path root = ShortTempDirs.root();
        assertThat(dir.startsWith(root)).isTrue();
        assertThat(root.getFileName().toString())
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
        assertThat(root.getParent()).isEqualTo(ShortTempDirs.path());
    }

    @Test
    void sweep_drops_dead_pid_dirs_and_unrecognized_leftovers() throws Exception {
        Path ns = ShortTempDirs.path();
        Files.createDirectories(ns);
        Path leftover = ns.resolve("stale-junk");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("x"), "x");
        Path dead = ns.resolve("999999999");
        Files.createDirectories(dead);

        ShortTempDirs.sweepStale(ns);

        assertThat(leftover).doesNotExist();
        assertThat(dead).doesNotExist();
        assertThat(ShortTempDirs.root()).isDirectory();
    }
}
