// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The Windows {@code jkx.cmd} shim, run by {@code cmd} itself. */
@EnabledOnOs(OS.WINDOWS)
class JkxCmdShimTest {

    @TempDir
    Path tmp;

    /**
     * A {@code %} in the home's path stays literal: unescaped, {@code cmd} would expand the unset
     * variable between the two to nothing and drop the lone one, and launch a path that does not exist.
     */
    @Test
    void the_shim_runs_a_jk_whose_path_carries_percent_signs() throws Exception {
        Path dir = tmp.resolve("home%JK_SHIM_TEST_UNSET%50%off");
        Files.createDirectories(dir);
        Path jk = dir.resolve("jk.cmd");
        Files.writeString(jk, "@echo off\r\necho ran %*\r\n");
        Path shim = tmp.resolve("jkx.cmd");
        Files.writeString(shim, JkxLink.cmdShim(jk));

        Processes.Captured run =
                Processes.capture(List.of("cmd.exe", "/d", "/c", shim.toString(), "a", "b"), Duration.ofSeconds(30));
        assertThat(run.exit()).as(run.error()).isZero();
        assertThat(run.output().strip()).isEqualTo("ran tool run a b");
    }
}
