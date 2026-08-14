// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1355 failure policy: freshen failure with an existing readable lock is soft (warn, exit 0) so
 * read-only commands keep answering from the stale lock; no lock at all or a genuinely
 * unsatisfiable manifest stays a hard failure.
 */
class EnsureFreshLockTest {

    @Test
    void soft_failure_with_existing_lock_proceeds(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");
        Files.writeString(tmp.resolve("jk-lock.toml"), "version = 1\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Explain", "connect timed out", 6, null);

        assertThat(code).isZero();
    }

    @Test
    void unsatisfiable_resolution_hard_fails_even_with_a_lock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");
        Files.writeString(tmp.resolve("jk-lock.toml"), "version = 1\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Explain", "‼ Cannot resolve dependencies:\n  │ …", 6, null);

        assertThat(code).isEqualTo(6);
    }

    @Test
    void missing_lock_hard_fails(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Tree", "connect timed out", 6, null);

        assertThat(code).isEqualTo(6);
    }

    @Test
    void zero_exit_code_maps_to_config_on_hard_failure(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Tree", "boom", 0, null);

        assertThat(code).isEqualTo(cc.jumpkick.model.command.Exit.CONFIG);
    }
}
