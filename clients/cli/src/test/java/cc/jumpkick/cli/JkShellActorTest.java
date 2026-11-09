// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.ChangeActor;
import org.junit.jupiter.api.Test;

class JkShellActorTest {

    @Test
    void the_actor_starts_at_the_command_and_keeps_no_option_value() {
        ChangeActor actor = Jk.shellActor(new String[] {"-y", "repo", "login", "nexus", "--password", "hunter2"});
        assertThat(actor.kind()).isEqualTo(ChangeActor.SHELL);
        assertThat(actor.label()).isEqualTo("jk repo login nexus --password");
    }
}
