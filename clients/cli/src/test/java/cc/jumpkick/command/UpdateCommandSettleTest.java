// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.CommandWedge;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Settle chrome for {@code jk update} — wedge shape, not the old {@code Updated: path › N} line. */
class UpdateCommandSettleTest {

    @BeforeEach
    void resetEnvelope() {
        CommandWedge.resetEnvelope();
    }

    @Test
    void printUpdatedLine_uses_update_wedge_with_package_count_in_lockfile() {
        String out = Capture.stdout(
                () -> UpdateCommand.printUpdatedLine(Path.of("/tmp/proj/jk-lock.toml"), 233, Path.of("/tmp/proj")));
        String plain = TestAnsi.strip(out);
        assertThat(plain).contains("Update");
        assertThat(plain).contains("Updated 233 packages in jk-lock.toml");
        assertThat(plain).doesNotContain("Updated:");
        assertThat(plain).doesNotContain("›");
    }

    @Test
    void printUpdatedLine_singular_package() {
        String out = Capture.stdout(() -> UpdateCommand.printUpdatedLine(Path.of("jk-lock.toml"), 1, Path.of(".")));
        assertThat(TestAnsi.strip(out)).contains("Updated 1 package in jk-lock.toml");
    }

    @Test
    void printGitSummary_uses_update_wedge() {
        String out = Capture.stdout(() -> UpdateCommand.printGitSummary(2));
        assertThat(TestAnsi.strip(out)).contains("Update").contains("Refreshed 2 git dependencies.");
    }
}
