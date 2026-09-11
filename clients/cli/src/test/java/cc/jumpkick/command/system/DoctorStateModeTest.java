// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The state-directory mode check: the engine socket is only as private as the directories above it. */
@DisabledOnOs(OS.WINDOWS)
class DoctorStateModeTest {

    @Test
    void a_state_dir_others_can_enter_is_a_finding_with_the_chmod_that_closes_it(@TempDir Path tmp) throws Exception {
        Path state = Files.createDirectories(tmp.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));

        DoctorCommand.Check check = DoctorCommand.checkStateMode(state);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.detail()).contains(state + " is rwxr-xr-x");
        assertThat(check.detail()).contains("chmod 700 " + state + " " + state.resolve("engine"));
    }

    @Test
    void a_loose_engine_dir_under_a_tight_state_dir_is_still_a_finding(@TempDir Path tmp) throws Exception {
        Path state = Files.createDirectories(tmp.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Path engine = Files.createDirectories(state.resolve("engine"));
        Files.setPosixFilePermissions(engine, PosixFilePermissions.fromString("rwxrwxr-x"));

        DoctorCommand.Check check = DoctorCommand.checkStateMode(state);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.detail()).contains(engine + " is rwxrwxr-x").doesNotContain(state + " is");
    }

    @Test
    void owner_only_state_and_engine_dirs_are_ok(@TempDir Path tmp) throws Exception {
        Path state = Files.createDirectories(tmp.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(state.resolve("engine"));
        Files.setPosixFilePermissions(state.resolve("engine"), PosixFilePermissions.fromString("rwx------"));

        assertThat(DoctorCommand.checkStateMode(state).status()).isEqualTo(DoctorCommand.Status.OK);
    }

    /** No state dir yet means nothing is exposed; the first `jk` run creates it owner-only. */
    @Test
    void a_missing_state_dir_is_ok(@TempDir Path tmp) {
        assertThat(DoctorCommand.checkStateMode(tmp.resolve("state")).status()).isEqualTo(DoctorCommand.Status.OK);
    }
}
