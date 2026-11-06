// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** What {@code jk new} says when the target already holds a project. */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class NewChromeTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    @Test
    void an_existing_project_warns_then_fails_with_the_coord_split_at_the_colon() throws Exception {
        String err = plainErr(() -> NewChrome.projectExists("com.example:widget", false, false));
        assertThat(err).contains("The com.example:widget project already exists in this directory.");
        // chipLine, not failureLine — failureLine prepends "Failed to new", which would double up.
        assertThat(err).contains("Failed to create project widget. Project already exists.");
        assertThat(err).doesNotContain("Failed to new");
    }

    @Test
    void an_existing_module_says_module_and_an_init_says_initialize() throws Exception {
        String err = plainErr(() -> NewChrome.projectExists("api", true, true));
        assertThat(err).contains("The api module already exists in this directory.");
        assertThat(err).contains("Failed to initialize module api.");
    }

    /** What {@code body} prints to stderr, rendered in plain mode: the mode is process-global, so it is pinned. */
    private static String plainErr(Runnable body) throws Exception {
        return NoAnsi.forced(() -> TestAnsi.strip(Capture.stderr(body)));
    }
}
