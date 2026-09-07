// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.Layout;
import cc.jumpkick.scaffold.NewInputs;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code jk new} says. The wizard path routes the same line through the controlling terminal
 * instead of {@link CliOutput}, so these assertions pin the composed line rather than the routing —
 * the routing is what {@link NewChrome} exists to keep in one place.
 */
class NewChromeTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    private static NewInputs inputs(String name) {
        return new NewInputs(
                "com.example",
                name,
                "25",
                25,
                25,
                null,
                null,
                false,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                Path.of("/tmp").resolve(name));
    }

    @Test
    void a_new_standalone_project_is_created() {
        String out = TestAnsi.strip(Capture.stdout(() -> NewChrome.created(inputs("widget"), null, false, null)));
        assertThat(out).contains("New Project");
        assertThat(out).contains("Created new project widget");
    }

    @Test
    void jk_new_dot_says_initialized_not_created() {
        String out = TestAnsi.strip(Capture.stdout(() -> NewChrome.created(inputs("widget"), null, true, null)));
        assertThat(out).contains("Init");
        assertThat(out).contains("Initialized project widget");
        assertThat(out).doesNotContain("Created new");
    }

    @Test
    void a_module_names_the_project_it_joined_and_never_says_project() {
        String out = TestAnsi.strip(Capture.stdout(() -> NewChrome.created(inputs("api"), "platform", false, null)));
        assertThat(out).contains("New Module");
        assertThat(out).contains("New module api added to project platform");
        assertThat(out).doesNotContain("Created new project");
    }

    @Test
    void an_existing_project_warns_then_fails_with_the_coord_split_at_the_colon() {
        String err =
                TestAnsi.strip(Capture.stderr(() -> NewChrome.projectExists("com.example:widget", false, false, null)));
        assertThat(err).contains("The com.example:widget project already exists in this directory.");
        // chipLine, not failureLine — failureLine prepends "Failed to new", which would double up.
        assertThat(err).contains("Failed to create project widget. Project already exists.");
        assertThat(err).doesNotContain("Failed to new");
    }

    @Test
    void an_existing_module_says_module_and_an_init_says_initialize() {
        String err = TestAnsi.strip(Capture.stderr(() -> NewChrome.projectExists("api", true, true, null)));
        assertThat(err).contains("The api module already exists in this directory.");
        assertThat(err).contains("Failed to initialize module api.");
    }

    @Test
    void no_jdks_points_at_jk_jdk_install() {
        String err = TestAnsi.strip(Capture.stderr(NewChrome::noJdks));
        assertThat(err).contains("No JDKs found on this system.");
        assertThat(err).contains("jk jdk install");
        assertThat(err).contains("jk new");
    }
}
