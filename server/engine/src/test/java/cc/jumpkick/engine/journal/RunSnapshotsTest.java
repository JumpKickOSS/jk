// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two snapshots a run leaves for the next one: every test's verdict and every file's hash. */
class RunSnapshotsTest {

    private static MarkdownTestReport.Entry entry(
            String cls, String name, @Nullable String failure, @Nullable String skip) {
        return new MarkdownTestReport.Entry(cls, name, 5, failure, null, skip);
    }

    @Test
    void test_outcomes_key_class_and_display_name_and_round_trip_as_tsv() {
        List<MarkdownTestReport.ModuleRun> runs = List.of(new MarkdownTestReport.ModuleRun(
                "/ws",
                "g:a",
                List.of(
                        entry("com.example.FooTest", "adds()", null, null),
                        entry("com.example.FooTest", "divides()", "by zero", null),
                        entry("com.example.SlowTest", "later()", null, "not today"),
                        // a parameterized test: one failing row fails the key
                        entry("com.example.ParamTest", "p(int)", null, null),
                        entry("com.example.ParamTest", "p(int)", "for 3", null))));

        Map<String, Character> outcomes = RunSnapshots.testOutcomes(runs);

        assertThat(outcomes)
                .containsEntry("com.example.FooTest#adds()", RunSnapshots.PASS)
                .containsEntry("com.example.FooTest#divides()", RunSnapshots.FAIL)
                .containsEntry("com.example.SlowTest#later()", RunSnapshots.SKIP)
                .containsEntry("com.example.ParamTest#p(int)", RunSnapshots.FAIL)
                .hasSize(4);
        String tsv = RunSnapshots.encodeTests(outcomes);
        assertThat(tsv).contains("F\tcom.example.FooTest#divides()\n");
        assertThat(RunSnapshots.decodeTests(tsv + "garbage line\nX\tnot a status\n"))
                .isEqualTo(outcomes);
    }

    @Test
    void the_source_walk_hashes_project_files_and_skips_build_output_and_hidden_trees(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"a\"\n");
        Files.createDirectories(root.resolve("src/com"));
        Files.writeString(root.resolve("src/com/A.java"), "class A {}\n");
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/A.class"), "bytes");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"), "ref");
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve("node_modules/x/index.js"), "");

        Map<String, RunSnapshots.FileRow> rows = requireNonNull(RunSnapshots.walk(root, Map.of()));

        assertThat(rows).containsOnlyKeys("jk.toml", "src/com/A.java");
        assertThat(requireNonNull(rows.get("src/com/A.java")).hash()).hasSize(64);

        // The next run reuses a known hash for a file whose size and mtime are unchanged, and
        // re-reads one that moved; the TSV round-trips both.
        Files.writeString(root.resolve("src/com/A.java"), "class A { int x; }\n");
        Map<String, RunSnapshots.FileRow> again = requireNonNull(RunSnapshots.walk(root, rows));
        assertThat(again.get("jk.toml")).isEqualTo(rows.get("jk.toml"));
        assertThat(requireNonNull(again.get("src/com/A.java")).hash())
                .isNotEqualTo(requireNonNull(rows.get("src/com/A.java")).hash());
        assertThat(RunSnapshots.decodeSources(RunSnapshots.encodeSources(again)))
                .isEqualTo(again);
        assertThat(JobDelta.changedFiles(RunSnapshots.hashes(rows), RunSnapshots.hashes(again)))
                .containsExactly("src/com/A.java");
    }

    @Test
    void the_source_walk_keeps_to_the_builds_inputs_and_leaves_the_rest_of_the_checkout_alone(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n[workspace]\nmodules = [\"app\"]\n");
        Files.writeString(root.resolve("jk-lock.toml"), "version = 1\n");
        Files.writeString(root.resolve("jk-guards.toml"), "");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve(".jk/after-build.kts"), "// script\n");
        Files.createDirectories(root.resolve("app/src/com"));
        Files.writeString(root.resolve("app/jk.toml"), "name = \"app\"\n");
        Files.writeString(root.resolve("app/src/com/A.java"), "class A {}\n");
        Files.createDirectories(root.resolve("app/resources"));
        Files.writeString(root.resolve("app/resources/app.properties"), "k=v\n");
        Files.createDirectories(root.resolve("app/test/src/com"));
        Files.writeString(root.resolve("app/test/src/com/ATest.java"), "class ATest {}\n");
        Files.createDirectories(root.resolve("app/notes"));
        Files.writeString(root.resolve("app/notes/todo.md"), "- later\n");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/guide.md"), "# guide\n");
        // A large untracked tree beside the modules: never a build input, never walked.
        Files.createDirectories(root.resolve("scratch/corpus"));
        for (int i = 0; i < 200; i++) Files.writeString(root.resolve("scratch/corpus/f" + i + ".txt"), "x");

        Map<String, RunSnapshots.FileRow> rows = requireNonNull(RunSnapshots.walk(root, Map.of()));

        assertThat(rows)
                .containsOnlyKeys(
                        "jk.toml",
                        "jk-lock.toml",
                        "jk-guards.toml",
                        ".jk/after-build.kts",
                        "app/jk.toml",
                        "app/src/com/A.java",
                        "app/resources/app.properties",
                        "app/test/src/com/ATest.java");
    }

    @Test
    void a_missing_root_is_no_snapshot(@TempDir Path root) {
        assertThat(RunSnapshots.walk(root.resolve("nope"), Map.of())).isNull();
        assertThat(RunSnapshots.takeSources(root.resolve("nope"), null)).isNull();
    }
}
