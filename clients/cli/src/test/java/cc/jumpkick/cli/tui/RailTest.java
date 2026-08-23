// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RailTest {

    @Test
    void completed_step_prompt_is_dark_gray_without_strikethrough() {
        var rendered =
                Rail.stepBullet(Rail.StepState.COMPLETED, "Project name:").toAnsi();
        assertThat(rendered).doesNotMatch("(?s).*\\[(?:\\d+;)*9(?:;\\d+)*m.*");
        assertThat(rendered).contains("Project name:");
        if (cc.jumpkick.cli.theme.Theme.colorEnabled()) {
            assertThat(rendered).contains("38;2;");
        }
    }

    @Test
    void active_step_prompt_has_no_strikethrough() {
        var rendered = Rail.stepBullet(Rail.StepState.ACTIVE, "Pick one:").toAnsi();
        assertThat(rendered).doesNotMatch("(?s).*\\[(?:\\d+;)*9(?:;\\d+)*m.*");
        assertThat(rendered).contains("Pick one:");
        assertThat(rendered).contains("1;");
    }
}
