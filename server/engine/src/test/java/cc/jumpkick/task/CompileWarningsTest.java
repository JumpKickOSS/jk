// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compile.CompileResult.Diagnostic;
import cc.jumpkick.compile.CompileResult.Severity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileWarningsTest {

    @Test
    void untouched_sources_keep_their_warnings_and_recompiled_ones_take_the_new(@TempDir Path dir) throws Exception {
        Module m = new Module(dir);
        Path a = m.source("A.java");
        Path b = m.source("B.java");
        Diagnostic oldA = warning(a, 3, "old A");
        Diagnostic oldB = warning(b, 4, "old B");
        Diagnostic newB = warning(b, 5, "new B");

        CompileWarnings.Merged merged =
                CompileWarnings.merge(List.of(oldA, oldB), List.of(b), List.of(a, b), List.of(newB));

        assertThat(merged.kept()).containsExactly(oldA);
        assertThat(merged.all()).containsExactly(oldA, newB);
    }

    @Test
    void a_source_with_a_fresh_warning_counts_as_recompiled(@TempDir Path dir) throws Exception {
        Module m = new Module(dir);
        Path a = m.source("A.java");
        Diagnostic oldA = warning(a, 3, "old A");
        Diagnostic newA = warning(a, 3, "new A");

        assertThat(CompileWarnings.merge(List.of(oldA), List.of(), List.of(a), List.of(newA))
                        .all())
                .containsExactly(newA);
    }

    @Test
    void a_whole_compile_keeps_nothing(@TempDir Path dir) throws Exception {
        Module m = new Module(dir);
        Path a = m.source("A.java");

        assertThat(CompileWarnings.merge(List.of(warning(a, 1, "old")), null, List.of(a), List.of())
                        .all())
                .isEmpty();
    }

    @Test
    void a_warning_with_no_source_belongs_to_the_last_compile(@TempDir Path dir) throws Exception {
        Module m = new Module(dir);
        Path a = m.source("A.java");
        Diagnostic loose = new Diagnostic(Severity.WARNING, null, 0, 0, "warning: processor said so");

        assertThat(CompileWarnings.merge(List.of(loose), List.of(), List.of(a), List.of())
                        .all())
                .as("nothing recompiled")
                .containsExactly(loose);
        assertThat(CompileWarnings.merge(List.of(loose), List.of(a), List.of(a), List.of())
                        .all())
                .as("a source recompiled")
                .isEmpty();
    }

    @Test
    void a_stored_warning_names_the_file_of_the_checkout_that_reads_it(@TempDir Path dir) throws Exception {
        Module one = new Module(dir.resolve("one"));
        Module two = new Module(dir.resolve("two"));
        Path a1 = one.source("src/A.kt");
        Path a2 = two.source("src/A.kt");
        Diagnostic kotlin = warning(a1, 3, a1.toUri() + ":3:5 'f' is deprecated.");
        Diagnostic javac = warning(a1, 3, a1 + ":3: warning: f() has been deprecated");

        List<Diagnostic> read = CompileWarnings.fromSaid(CompileWarnings.said(List.of(kotlin, javac)), List.of(a2));

        assertThat(read).extracting(Diagnostic::source).containsOnly(a2);
        assertThat(read.get(0).message()).isEqualTo(a2.toUri() + ":3:5 'f' is deprecated.");
        assertThat(read.get(1).message()).isEqualTo(a2 + ":3: warning: f() has been deprecated");
        assertThat(read).extracting(Diagnostic::line, Diagnostic::key).containsOnly(tuple(3L, "k"));
    }

    @Test
    void the_file_of_current_warnings_is_absent_until_written(@TempDir Path dir) throws Exception {
        Module m = new Module(dir);
        Path a = m.source("A.java");
        Path file = CompileWarnings.currentFile(dir.resolve("target/incremental"), "compile-main");

        assertThat(CompileWarnings.readCurrent(file, List.of(a))).isEmpty();
        CompileWarnings.writeCurrent(file, List.of());
        assertThat(CompileWarnings.readCurrent(file, List.of(a))).hasValue(List.of());
        CompileWarnings.writeCurrent(file, "javac", List.of(warning(a, 2, "w")));
        assertThat(CompileWarnings.readCurrent(file, List.of(a)))
                .hasValue(List.of(new CompileWarnings.Kept("javac", warning(a, 2, "w"))));
        assertThat(CompileWarnings.readCurrentUnder(file, m.root()))
                .as("a module the build does not run resolves its sources under its own dir")
                .containsExactly(new CompileWarnings.Kept("javac", warning(a, 2, "w")));
    }

    @Test
    void only_warnings_are_replayed_for_a_compiler_whose_other_lines_are_chatter() {
        Diagnostic warn = new Diagnostic(Severity.WARNING, null, 0, 0, "w");
        Diagnostic note = new Diagnostic(Severity.NOTE, null, 0, 0, "n");
        Diagnostic error = new Diagnostic(Severity.ERROR, null, 0, 0, "e");

        assertThat(CompileWarnings.replayable(List.of(warn, note, error), false))
                .containsExactly(warn, note);
        assertThat(CompileWarnings.replayable(List.of(warn, note, error), true)).containsExactly(warn);
    }

    @Test
    void a_step_of_several_tools_keeps_each_warnings_tool() {
        Diagnostic said = new Diagnostic(Severity.WARNING, null, 0, 0, "[KNEST-W021] Task.title has no @Length");
        List<CompileWarnings.Kept> kept = List.of(new CompileWarnings.Kept("warn", said));

        assertThat(CompileWarnings.keptFromSaid(CompileWarnings.keptSaid(kept))).isEqualTo(kept);
    }

    @Test
    void a_ksp_round_inside_another_step_keeps_its_own_file_under_that_step() {
        assertThat(CompileWarnings.roundFile("ksp")).isEqualTo("ksp");
        assertThat(CompileWarnings.roundFile("compile-test")).isEqualTo("compile-test+ksp");
        assertThat(CompileWarnings.stepOf("compile-test+ksp")).isEqualTo("compile-test");
        assertThat(CompileWarnings.stepOf("compile-java")).isEqualTo("compile-java");
    }

    private static Diagnostic warning(Path source, long line, String message) {
        return new Diagnostic(Severity.WARNING, source, line, 0, message, "k");
    }

    /** A module root: a directory with a manifest, which is what a source is spelled relative to. */
    private record Module(Path root) {
        Module {
            try {
                Files.createDirectories(root);
                Files.writeString(root.resolve("jk.toml"), "name = \"m\"\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Path source(String rel) throws Exception {
            Path p = root.resolve(rel).toAbsolutePath().normalize();
            Files.createDirectories(p.getParent());
            Files.writeString(p, "");
            return p;
        }
    }
}
