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
                .with(new TestQuarantine.Entry("b.GoneTest", "lib", "", "", "https://example.org/issues/3"))
                .write(root);

        TestQuarantineReport report = TestQuarantineReport.of(state, root);

        assertThat(report.policy()).isEqualTo(TestQuarantine.Policy.DEFAULT);
        assertThat(report.rows())
                .extracting(r -> r.module() + " " + r.className() + " " + r.quarantined() + " " + r.history())
                .containsExactly(
                        "app a.FlakyTest true ..F.R..R", "app a.OnceTest false R.....", "lib b.GoneTest true ");
        assertThat(report.rows().getFirst().flakes(20)).isEqualTo(2);
        assertThat(report.named("FlakyTest"))
                .extracting(TestQuarantineReport.Row::className)
                .containsExactly("a.FlakyTest");
        assertThat(report.named("a.Nope")).isEmpty();
    }

    @Test
    void a_rerun_pass_older_than_the_window_is_not_flaky_any_more() throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[test]\nquarantine = { flakes = 1, runs = 3 }\n");
        history(root, Map.of("a.OldTest", "R...", "a.NewTest", "..R"));

        assertThat(TestQuarantineReport.of(state, root).rows())
                .extracting(r -> r.module() + " " + r.className())
                .containsExactly(". a.NewTest");
    }
}
