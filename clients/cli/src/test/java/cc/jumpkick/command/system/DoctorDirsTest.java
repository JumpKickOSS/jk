// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkCacheConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The directories row: a cache that does not exist yet is a fresh install, not a fault. */
class DoctorDirsTest {

    @Test
    void a_cache_dir_that_does_not_exist_yet_is_ok_and_says_so(@TempDir Path home) throws Exception {
        Path cache = home.resolve("cache");
        Files.createDirectories(home.resolve("state"));

        DoctorCommand.Check check =
                DoctorCommand.checkDirs(cache, home.resolve("store"), home.resolve("state"), JkCacheConfig.DEFAULTS);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(check.detail()).contains("not created yet").contains(cache.toString());
    }

    @Test
    void an_existing_cache_dir_is_ok_and_named(@TempDir Path home) throws Exception {
        Path cache = Files.createDirectories(home.resolve("cache"));

        DoctorCommand.Check check =
                DoctorCommand.checkDirs(cache, home.resolve("store"), home.resolve("state"), JkCacheConfig.DEFAULTS);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(check.detail()).contains("cache " + cache).doesNotContain("not created yet");
    }

    @Test
    void a_cache_path_that_is_a_file_is_a_finding(@TempDir Path home) throws Exception {
        Path cache = Files.writeString(home.resolve("cache"), "not a directory");

        DoctorCommand.Check check =
                DoctorCommand.checkDirs(cache, home.resolve("store"), home.resolve("state"), JkCacheConfig.DEFAULTS);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.detail()).contains("cache is not a directory: " + cache);
    }

    @Test
    void a_missing_store_parent_is_still_a_finding(@TempDir Path home) {
        Path elsewhere = home.resolve("gone");

        DoctorCommand.Check check = DoctorCommand.checkDirs(
                home.resolve("cache"), elsewhere.resolve("store"), home.resolve("state"), JkCacheConfig.DEFAULTS);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.detail()).contains("store parent missing");
    }
}
