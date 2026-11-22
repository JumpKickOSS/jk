// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The flaky and quarantined classes of a workspace, read from its histories and its quarantine file. */
class TestQuarantineReportTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path state;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("ws"));
        state = tmp.resolve("state");
        TomlScan.clearCache();
    }

    private void history(Path moduleDir, Map<String, String> classes) throws IOException {
        Path file = TestHistory.file(state, moduleDir);
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("# " + moduleDir.toAbsolutePath().normalize() + "\n");
        classes.forEach(
                (cls, marks) -> sb.append(cls).append('\t').append(marks).append('\n'));
        Files.writeString(file, sb.toString());
    }

    @Test
    void histories_under_the_root_are_read_and_others_are_not() throws IOException {
        history(root.resolve("app"), Map.of("a.OkTest", "..."));
        history(tmp.resolve("elsewhere"), Map.of("z.OkTest", "."));

        assertThat(TestHistory.under(state, root)).singleElement().satisfies(h -> {
            assertThat(h.moduleDir())
                    .isEqualTo(root.resolve("app").toAbsolutePath().normalize());
            assertThat(h.classes()).containsExactly(Map.entry("a.OkTest", "..."));
        });
        assertThat(TestHistory.under(tmp.resolve("no-state"), root)).isEmpty();
    }

    @Test
    void a_history_that_cannot_be_read_is_skipped_and_the_others_still_are() throws IOException {
        history(root.resolve("app"), Map.of("a.OkTest", "..."));
        Path broken = TestHistory.file(state, root.resolve("lib"));
        Files.write(broken, new byte[] {'#', ' ', (byte) 0xC3, (byte) 0x28, '\n'});

        assertThat(TestHistory.under(state, root)).singleElement().satisfies(h -> assertThat(h.classes())
                .containsOnlyKeys("a.OkTest"));
    }

    @Test
    void lists_quarantined_and_flaky_classes_with_their_history_and_skips_steady_ones() throws IOException {
        history(
                root.resolve("app"),
                Map.of(
                        "a.FlakyTest", "..F.R..R",
                        "a.OnceTest", "R" + ".".repeat(5),
                        "a.OkTest", "....",
                        "a.RedTest", "FFFF"));
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "flaky", "2026-11-20", null))
                .with(new TestQuarantine.Entry(
                        "b.GoneTest", "lib", "", "", new TestQuarantine.Issue(null, "https://example.org/issues/3")))
                .write(root);

        TestQuarantineReport report = TestQuarantineReport.of(state, root);

        assertThat(report.policy()).isEqualTo(TestQuarantine.Policy.DEFAULT);
        assertThat(report.listed())
                .extracting(r -> r.module() + " " + r.className() + " " + r.quarantined() + " " + r.history())
                .containsExactly(
                        "app a.FlakyTest true ..F.R..R", "app a.OnceTest false R.....", "lib b.GoneTest true ");
        assertThat(report.listed().getFirst().flakes(20)).isEqualTo(2);
        assertThat(report.named("FlakyTest"))
                .extracting(TestQuarantineReport.Row::className)
                .containsExactly("a.FlakyTest");
        assertThat(report.named("a.Nope")).isEmpty();
        assertThat(report.named("OkTest"))
                .as("a steady class is not listed but still shown")
                .extracting(TestQuarantineReport.Row::history)
                .containsExactly("....");
    }

    @Test
    void a_rerun_pass_older_than_the_window_is_not_flaky_any_more() throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[test]\nquarantine = { flakes = 1, runs = 3 }\n");
        history(root, Map.of("a.OldTest", "R...", "a.NewTest", "..R"));

        assertThat(TestQuarantineReport.of(state, root).listed())
                .extracting(r -> r.module() + " " + r.className())
                .containsExactly(". a.NewTest");
    }

    @Test
    void a_count_does_not_look_past_the_last_release() {
        assertThat(TestHistory.flakes("R.R|..R", 20)).isEqualTo(1);
        assertThat(TestHistory.counted("R.R|..R", 20)).isEqualTo("..R");
        assertThat(TestHistory.counted("R.R|..R", 2))
                .as("the window is shorter")
                .isEqualTo(".R");
        assertThat(TestHistory.count("RF|F.", 20, TestHistory.FAILED)).isEqualTo(1);
        assertThat(TestHistory.flakes("RR|", 20)).as("nothing ran since").isZero();
    }

    @Test
    void the_last_runs_keep_the_releases_among_them_and_a_release_is_not_a_run() {
        assertThat(TestHistory.last("R.|R.", 3)).isEqualTo(".|R.");
        assertThat(TestHistory.last("R.|R.", 2)).isEqualTo("R.");
        assertThat(TestHistory.last("..", 5)).isEqualTo("..");
    }

    @Test
    void release_marks_the_released_class_in_its_modules_and_keeps_its_history() throws IOException {
        history(root.resolve("app"), Map.of("a.FlakyTest", "R.R", "a.OkTest", "..."));
        history(root.resolve("lib"), Map.of("a.FlakyTest", "R"));
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "", "", null))
                .write(root);

        assertThat(TestQuarantine.release(state, root, "FlakyTest")).hasSize(1);

        assertThat(TestHistory.read(TestHistory.file(state, root.resolve("app"))))
                .containsExactly(Map.entry("a.FlakyTest", "R.R|"), Map.entry("a.OkTest", "..."));
        assertThat(TestHistory.read(TestHistory.file(state, root.resolve("lib"))))
                .as("the entry held only app")
                .containsExactly(Map.entry("a.FlakyTest", "R"));
        TestQuarantineReport report = TestQuarantineReport.of(state, root);
        assertThat(report.listed())
                .extracting(r -> r.module() + " " + r.className())
                .containsExactly("lib a.FlakyTest");
        assertThat(report.named("FlakyTest"))
                .extracting(TestQuarantineReport.Row::history)
                .containsExactly("R.R|", "R");
    }
}
