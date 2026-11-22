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
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk flaky list}, {@code show}, {@code issue} and {@code release} over a workspace's files. */
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
                        "a.FlakyTest",
                        "app",
                        "passed on a re-run twice",
                        "2026-11-20",
                        new TestQuarantine.Issue(null, "https://example.org/i/3")))
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

    @Test
    void issue_through_the_cli_stores_one_value_as_an_id_or_a_url_and_two_as_both() throws IOException {
        String dir = root.toString();
        Capture.Streams id =
                Capture.both(() -> Jk.execute(new String[] {"flaky", "issue", "FlakyTest", "PROJ-88", "--dir", dir}));
        assertThat(id.out() + id.err()).contains("a.FlakyTest in app: issue PROJ-88");
        assertThat(issue()).isEqualTo(new TestQuarantine.Issue("PROJ-88", null));

        Jk.execute(new String[] {"flaky", "issue", "FlakyTest", "https://linear.app/acme/issue/ENG-4", "--dir", dir});
        assertThat(issue()).isEqualTo(new TestQuarantine.Issue(null, "https://linear.app/acme/issue/ENG-4"));

        Jk.execute(new String[] {
            "flaky", "issue", "a.FlakyTest", "88", "https://github.com/acme/shop/issues/88", "--dir", dir
        });
        assertThat(issue()).isEqualTo(new TestQuarantine.Issue("88", "https://github.com/acme/shop/issues/88"));
        assertThat(Capture.stdout(() -> FlakyCommand.list(state, root, false))).contains("88");
        assertThat(Capture.stdout(() -> FlakyCommand.show(state, root, "FlakyTest", false)))
                .contains("issue:    88  https://github.com/acme/shop/issues/88");
        assertThat(Capture.stdout(() -> FlakyCommand.issue(root, "FlakyTest", List.of("PROJ-9"), true)))
                .contains("\"type\":\"flaky-issue\",\"class\":\"a.FlakyTest\",\"module\":\"app\","
                        + "\"issue\":{\"id\":\"PROJ-9\"}}");
    }

    @Test
    void issue_of_a_class_not_quarantined_or_in_the_wrong_order_fails_and_writes_nothing() throws IOException {
        String dir = root.toString();
        Capture.Streams gone = Capture.both(
                () -> assertThat(Jk.execute(new String[] {"flaky", "issue", "a.OnceTest", "PROJ-1", "--dir", dir}))
                        .isEqualTo(1));
        assertThat(gone.out() + gone.err()).contains("not quarantined: a.OnceTest");

        Capture.Streams swapped = Capture.both(() -> assertThat(
                        Jk.execute(new String[] {"flaky", "issue", "FlakyTest", "https://x/1", "PROJ-1", "--dir", dir}))
                .isEqualTo(1));
        assertThat(swapped.out() + swapped.err()).contains("not a URL: PROJ-1; give the id, then the URL");
        assertThat(issue()).isEqualTo(new TestQuarantine.Issue(null, "https://example.org/i/3"));
    }

    private TestQuarantine.@Nullable Issue issue() throws IOException {
        TestQuarantine.Entry e = TestQuarantine.read(root).find("app", "a.FlakyTest");
        return e == null ? null : e.issue();
    }
}
