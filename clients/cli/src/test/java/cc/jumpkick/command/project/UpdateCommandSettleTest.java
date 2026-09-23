// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.RichText;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Settle chrome for {@code jk update} — wedge shape, not the old {@code Updated: path › N} line. */
class UpdateCommandSettleTest {

    @BeforeEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    @Test
    void printUpdatedLine_names_analyzed_and_changed_counts_in_lockfile() {
        String out = Capture.stdout(
                () -> UpdateCommand.printUpdatedLine(Path.of("/tmp/proj/jk-lock.toml"), 308, 12, Path.of("/tmp/proj")));
        String plain = TestAnsi.strip(out);
        assertThat(plain).contains("Update");
        assertThat(plain).contains("Analyzed 308 dependencies, 12 were updated in jk-lock.toml");
        assertThat(plain).doesNotContain("Updated:");
        assertThat(plain).doesNotContain("›");
        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            assertThat(out).contains(Theme.colorize("12", t.warning()));
            assertThat(out).contains(Theme.colorize("jk-lock.toml", t.path()));
        }
    }

    @Test
    void updatedTail_marks_changed_count_yellow_and_lockfile_path() {
        RichText tail = UpdateCommand.updatedTail(Path.of("/tmp/proj/jk-lock.toml"), 308, 233, Path.of("/tmp/proj"));
        assertThat(tail.plainText()).isEqualTo("Analyzed 308 dependencies, 233 were updated in jk-lock.toml");
        if (Theme.active().isAnsi()) {
            String rendered = tail.render();
            Theme t = Theme.active();
            assertThat(rendered).contains(Theme.colorize("233", t.warning()));
            assertThat(rendered).contains(Theme.colorize("jk-lock.toml", t.path()));
        }
    }

    @Test
    void printUpdatedLine_singular_counts() {
        String out = Capture.stdout(() -> UpdateCommand.printUpdatedLine(Path.of("jk-lock.toml"), 1, 1, Path.of(".")));
        assertThat(TestAnsi.strip(out)).contains("Analyzed 1 dependency, 1 was updated in jk-lock.toml");
    }

    @Test
    void printUpdatedLine_reports_zero_when_nothing_moved() {
        String out =
                Capture.stdout(() -> UpdateCommand.printUpdatedLine(Path.of("jk-lock.toml"), 308, 0, Path.of(".")));
        assertThat(TestAnsi.strip(out)).contains("Analyzed 308 dependencies, 0 were updated in jk-lock.toml");
    }

    @Test
    void printChange_shows_a_move_an_addition_a_removal_and_a_member_row() {
        String out = Capture.stdout(() -> {
            UpdateCommand.printChange(new UpdateCommand.Moved("com.acme:a", "1.0", "1.1", List.of()));
            UpdateCommand.printChange(new UpdateCommand.Moved("com.acme:b", null, "2.0", List.of()));
            UpdateCommand.printChange(new UpdateCommand.Moved("com.acme:c", "3.0", null, List.of()));
            UpdateCommand.printChange(new UpdateCommand.Moved("com.acme:d", "1.0", "1.2", List.of("api", "web")));
        });
        assertThat(TestAnsi.strip(out))
                .contains("  com.acme:a  1.0 → 1.1")
                .contains("  com.acme:b  new → 2.0")
                .contains("  com.acme:c  3.0 → removed")
                .contains("  com.acme:d  1.0 → 1.2  (api, web)");
    }

    @Test
    void a_lock_change_matching_a_pin_line_is_the_same_move() {
        UpdateCommand.Moved pin = new UpdateCommand.Moved("com.acme:a", "1.0", "1.1", List.of());
        assertThat(new UpdateCommand.Moved("com.acme:a", "1.0", "1.1", List.of()).sameMoveAs(pin))
                .isTrue();
        assertThat(new UpdateCommand.Moved("com.acme:a:jdk8", "1.0", "1.1", List.of()).sameMoveAs(pin))
                .isTrue();
        assertThat(new UpdateCommand.Moved("com.acme:a!aar", "1.0", "1.1", List.of()).sameMoveAs(pin))
                .isTrue();
        assertThat(new UpdateCommand.Moved("com.acme:a", "1.0", "1.2", List.of()).sameMoveAs(pin))
                .as("the resolve picked another version than the pin line showed")
                .isFalse();
        assertThat(new UpdateCommand.Moved("com.acme:ab", "1.0", "1.1", List.of()).sameMoveAs(pin))
                .isFalse();
    }

    @Test
    void printGitSummary_uses_update_wedge() {
        String out = Capture.stdout(() -> UpdateCommand.printGitSummary(2));
        assertThat(TestAnsi.strip(out)).contains("Update").contains("Refreshed 2 git dependencies.");
    }
}
