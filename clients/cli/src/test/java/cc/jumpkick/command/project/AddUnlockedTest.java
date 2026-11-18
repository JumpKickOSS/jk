// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.BlankProject;
import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk add} whose relock does not resolve: the edit stands and the warning says why, and the
 * settle line under it never says the version was locked.
 */
@Tag("integration")
class AddUnlockedTest {

    @Test
    void an_add_whose_lock_did_not_resolve_never_says_locked(@TempDir Path tmp) throws Exception {
        assertThat(BlankProject.write(tmp.toString())).isZero();
        int[] exit = {0};

        // Offline, and no store holds it: the relock cannot resolve it.
        Capture.Streams out = Capture.both(
                () -> exit[0] = Jk.execute("add", "--offline", "zz.nowhere:absent:1.0.0", "-C", tmp.toString()));

        String all = out.out() + out.err();
        assertThat(exit[0]).as(all).isZero();
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("zz.nowhere")
                .contains("1.0.0");
        assertThat(all).contains("the lock did not resolve");
        assertThat(all).doesNotContain("and locked").contains("Written as 1.0.0; not locked");
    }

    @Test
    void the_unlocked_line_names_what_locks_it() {
        assertThat(AddCommand.unlockedLine("3.1.0"))
                .contains("3.1.0")
                .contains("not locked")
                .contains("jk lock");
        assertThat(AddCommand.unlockedLine("managed"))
                .contains("managed by the platform BOM")
                .contains("not locked");
    }
}
