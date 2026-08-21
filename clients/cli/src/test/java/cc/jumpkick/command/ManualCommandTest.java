// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.docs.JkManual;
import cc.jumpkick.model.command.Exit;
import org.junit.jupiter.api.Test;

class ManualCommandTest {

    @Test
    void command_is_registered() {
        assertThat(CommandDispatch.commands().stream().map(c -> c.name())).contains("manual");
    }

    @Test
    void prints_the_playbook_markdown() {
        String out = Capture.stdout(() -> assertThat(Jk.execute("manual")).isEqualTo(Exit.SUCCESS));
        assertThat(out).isEqualTo(JkManual.markdown());
        assertThat(out).startsWith("# JumpKick playbook");
        assertThat(out).contains("target/jk-results.md");
        assertThat(out).contains("jk_bind");
    }
}
