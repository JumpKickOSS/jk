// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Settled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The git jk shells out to: a found one is remembered while its file is unchanged, an absent one never. */
@DisabledOnOs(OS.WINDOWS)
class GitCliDetectTest {

    @Test
    void a_git_installed_after_a_miss_is_found_and_a_replaced_one_is_probed_again(@TempDir Path dir) throws Exception {
        Path git = dir.resolve("git");
        Map<String, String> shell = Map.of("PATH", "/usr/bin:/bin", "JK_GIT", git.toString());
        // Judged as settled, so a found git is remembered rather than probed again for a fresh write.
        Settled.aheadForTests(Duration.ofMinutes(1));
        try {
            assertThat(RequestShell.where(shell, GitCliExtension::detect))
                    .as("JK_GIT names nothing yet")
                    .isEmpty();

            fakeGit(git, "git version 2.47.1");
            assertThat(RequestShell.where(shell, GitCliExtension::detect))
                    .as("the miss was not remembered")
                    .hasValueSatisfying(g -> assertThat(g.minor()).isEqualTo(47));

            Files.delete(git);
            fakeGit(git, "git version 2.48.0");
            assertThat(RequestShell.where(shell, GitCliExtension::detect))
                    .as("a replaced git is probed again")
                    .hasValueSatisfying(g -> assertThat(g.minor()).isEqualTo(48));
        } finally {
            Settled.aheadForTests(null);
        }
    }

    private static void fakeGit(Path file, String versionLine) throws Exception {
        Files.writeString(file, "#!/bin/sh\nprintf '%s\\n' '" + versionLine + "'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
