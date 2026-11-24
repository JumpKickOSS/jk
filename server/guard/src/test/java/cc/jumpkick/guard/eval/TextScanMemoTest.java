// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TrimRegistry;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.Settled;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A text rule re-reads only the files changed since its last run over the same rules. */
class TextScanMemoTest {

    private static final String TODO = """
            [guards.todo]
            kind = "text"
            pattern = "TODO"
            blank = "none"
            instead = "i"
            why = "w"
            """;

    @BeforeEach
    void coldMemo() {
        TextEvaluator.MEMO.clear();
    }

    @AfterEach
    void realClock() {
        Settled.aheadForTests(null);
        TextEvaluator.MEMO.clear();
    }

    private static void tree(Path root) throws Exception {
        Path a = Files.createDirectories(root.resolve("mod-a/src/main/java/a"));
        Files.writeString(a.resolve("A.java"), "package a;\nclass A {} // TODO one\n");
        Files.writeString(a.resolve("B.java"), "package a;\nclass B {}\n");
        Files.writeString(a.resolve("C.java"), "package a;\nclass C {} // TODO three\n");
    }

    /** The todo sites, after one evaluation over {@code rules}. */
    private static List<String> sites(Path root, String rules) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx =
                new EvalContext(Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of);
        Evaluation ev = Objects.requireNonNull(LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx)
                .get("todo"));
        return ev.observations().stream().map(o -> o.file() + ":" + o.line()).toList();
    }

    private static long reads(ThrowingRun run) throws Exception {
        long before = TextFiles.READS.sum();
        run.run();
        return TextFiles.READS.sum() - before;
    }

    private interface ThrowingRun {
        void run() throws Exception;
    }

    @Test
    void an_unedited_file_is_not_read_again(@TempDir Path root) throws Exception {
        tree(root);
        Settled.aheadForTests(Duration.ofSeconds(10));
        List<String> first = sites(root, TODO);
        AtomicReference<List<String>> second = new AtomicReference<>();

        assertThat(reads(() -> second.set(sites(root, TODO)))).isZero();
        assertThat(second.get()).isEqualTo(first).hasSize(2);
    }

    @Test
    void an_edited_file_is_read_again_and_its_new_site_found(@TempDir Path root) throws Exception {
        tree(root);
        Settled.aheadForTests(Duration.ofSeconds(10));
        sites(root, TODO);
        Files.writeString(root.resolve("mod-a/src/main/java/a/B.java"), "package a;\nclass B {} // TODO two\n");
        AtomicReference<List<String>> again = new AtomicReference<>();

        assertThat(reads(() -> again.set(sites(root, TODO)))).isEqualTo(1);
        assertThat(again.get())
                .containsExactly(
                        "mod-a/src/main/java/a/A.java:2",
                        "mod-a/src/main/java/a/B.java:2",
                        "mod-a/src/main/java/a/C.java:2");
    }

    @Test
    void a_changed_rule_reads_every_file_again(@TempDir Path root) throws Exception {
        tree(root);
        Settled.aheadForTests(Duration.ofSeconds(10));
        sites(root, TODO);

        assertThat(reads(() -> sites(root, TODO.replace("\"TODO\"", "\"TODO t\""))))
                .isEqualTo(3);
    }

    @Test
    void a_file_written_within_the_settle_window_is_not_recorded(@TempDir Path root) throws Exception {
        tree(root);
        sites(root, TODO);

        assertThat(reads(() -> sites(root, TODO))).isEqualTo(3);
        assertThat(TextEvaluator.MEMO.size()).isZero();
    }

    @Test
    void the_engine_trims_drop_what_the_scans_kept(@TempDir Path root) throws Exception {
        tree(root);
        Settled.aheadForTests(Duration.ofSeconds(10));
        sites(root, TODO);
        int kept = TextEvaluator.MEMO.size();
        assertThat(kept).isPositive();

        TrimRegistry.trimAll(root.resolve("elsewhere"));
        assertThat(TextEvaluator.MEMO.size())
                .as("a trim that keeps another tree")
                .isZero();

        sites(root, TODO);
        TrimRegistry.trimAll(root);
        assertThat(TextEvaluator.MEMO.size()).as("a trim that keeps this tree").isEqualTo(kept);

        TrimRegistry.trimAll(null);
        assertThat(reads(() -> sites(root, TODO))).isEqualTo(3);
    }
}
