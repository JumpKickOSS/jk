// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.TestHistory;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TomlScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk flaky list}, {@code show} and {@code release} over a workspace's files. */
class FlakyCommandTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path state;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        state = tmp.resolve("state");
        TomlScan.clearCache();
        Path module = root.resolve("app");
        Path file = TestHistory.file(state, module);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "# " + module.toAbsolutePath().normalize() + "\n"
                        + "a.FlakyTest\t..F.R..R\n"
                        + "a.OnceTest\t....R\n"
                        + "a.OkTest\t.....\n");
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry(
                        "a.FlakyTest", "app", "passed on a re-run twice", "2026-11-20", "https://example.org/i/3"))
                .write(root);
    }

    @Test
    void list_names_each_flaky_and_quarantined_class_once() {
        String out = Capture.stdout(() -> FlakyCommand.list(state, root, false));
        assertThat(out)
                .contains("a.FlakyTest", "quarantined since 2026-11-20", "2 of 8")
                .contains("a.OnceTest", "flaky", "1 of 5")
                .doesNotContain("a.OkTest");
    }

    @Test
    void list_with_nothing_flaky_says_so() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertThat(Capture.stdout(() -> FlakyCommand.list(state, empty, false)))
                .contains("(no flaky or quarantined test classes)");
    }

    @Test
    void show_prints_the_entry_and_its_history_by_simple_name() {
        String out = Capture.stdout(() -> FlakyCommand.show(state, root, "FlakyTest", false));
        assertThat(out)
                .contains("a.FlakyTest  app")
                .contains("reason:   passed on a re-run twice")
                .contains("issue:    https://example.org/i/3")
                .contains("last 8:  2 passed on a re-run, 1 failed, 5 passed")
                .contains("history:  ..F.R..R");
    }

    @Test
    void show_of_a_steady_class_says_so_and_of_an_unknown_one_fails_naming_it() {
        assertThat(Capture.stdout(() -> FlakyCommand.show(state, root, "a.OkTest", false)))
                .contains("state:    not flaky", "history:  .....");
        Capture.Streams s = Capture.both(() ->
                assertThat(FlakyCommand.show(state, root, "a.Nope", false)).isEqualTo(1));
        assertThat(s.out() + s.err()).contains("no history or quarantine entry: a.Nope");
    }

    @Test
    void release_resets_the_count_and_show_still_prints_the_old_history() {
        assertThat(FlakyCommand.release(state, root, "FlakyTest", false)).isZero();

        assertThat(Capture.stdout(() -> FlakyCommand.list(state, root, false)))
                .as("its re-run passes were before the release")
                .doesNotContain("a.FlakyTest");
        assertThat(Capture.stdout(() -> FlakyCommand.show(state, root, "FlakyTest", false)))
                .contains("state:    released")
                .contains("last 0 since release:  0 passed on a re-run, 0 failed, 0 passed")
                .contains("history:  ..F.R..R|");
    }

    @Test
    void release_through_the_cli_deletes_the_entry_and_a_second_fails() {
        String dir = root.toString();
        Capture.Streams first =
                Capture.both(() -> Jk.execute(new String[] {"flaky", "release", "a.FlakyTest", "--dir", dir}));
        assertThat(first.out() + first.err()).contains("released a.FlakyTest in app");
        assertThat(TestQuarantine.file(root)).doesNotExist();

        Capture.Streams again =
                Capture.both(() -> assertThat(Jk.execute(new String[] {"flaky", "release", "FlakyTest", "--dir", dir}))
                        .isEqualTo(1));
        assertThat(again.out() + again.err()).contains("not quarantined: FlakyTest");
    }
}
