// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code provenance.tsv} must survive its own delimiters appearing in the data: a tab or newline
 * is legal in a POSIX path, and unescaped it split the record into bogus columns — the row read
 * as malformed, was silently skipped, and the prune stopped for that file. And two spellings of one
 * file — through a link and through its target — must reconcile as one, without asking the
 * filesystem about every file to get there.
 */
class GeneratedProvenanceTest {

    @Test
    void escape_round_trips_every_delimiter() {
        for (String hostile :
                List.of("plain", "with\ttab", "with\nnewline", "with\rreturn", "back\\slash", "\\t not a tab")) {
            assertThat(GeneratedProvenance.unescape(GeneratedProvenance.escape(hostile)))
                    .as("round trip of %s", hostile.replace("\n", "\\n"))
                    .isEqualTo(hostile);
        }
    }

    @Test
    void a_pre_escaping_windows_row_is_recognised_and_skipped() {
        // Raw separators: `\t` here is a directory named target, not a tab.
        assertThat(GeneratedProvenance.isPreEscaping("C:\\Users\\b\\target\\Gen.java\tC:\\Users\\b\\src\\A.java"))
                .isTrue();
        assertThat(GeneratedProvenance.isPreEscaping("D:\\tools\\thing.java\tD:\\tmp\\x.java"))
                .isTrue();
        // Rows this class wrote: every backslash is doubled or introduces a delimiter escape.
        assertThat(GeneratedProvenance.isPreEscaping(GeneratedProvenance.escape("C:\\Users\\b\\target\\Gen.java")))
                .isFalse();
        assertThat(GeneratedProvenance.isPreEscaping(GeneratedProvenance.escape("/tmp/we\tird/Gen.java")))
                .isFalse();
        assertThat(GeneratedProvenance.isPreEscaping("/tmp/plain/Gen.java\t/tmp/plain/A.java"))
                .isFalse();
    }

    @Test
    void a_pre_escaping_row_is_dropped_and_the_file_is_rewritten_clean(@TempDir Path tmp) throws Exception {
        Path workdir = Files.createDirectories(tmp.resolve("work"));
        Path sourceOutput = Files.createDirectories(tmp.resolve("gen"));
        Path classOutput = Files.createDirectories(tmp.resolve("classes"));
        Path origin = Files.createDirectories(tmp.resolve("src")).resolve("A.java");
        Files.writeString(origin, "class A {}");
        Path generated = sourceOutput.resolve("AGen.java");
        Files.writeString(generated, "class AGen {}");

        // One row from a jk that did not escape (raw Windows separators), one healthy row.
        Files.writeString(
                workdir.resolve("provenance.tsv"),
                "C:\\old\\target\\Gone.java\tC:\\old\\src\\Gone.java\n"
                        + GeneratedProvenance.escape(generated.toString())
                        + "\t"
                        + GeneratedProvenance.escape(origin.toString())
                        + "\n");

        GeneratedProvenance.of(workdir)
                .reconcile(sourceOutput, classOutput, List.of(origin), Map.of(generated, Set.of(origin)));

        String rewritten = Files.readString(workdir.resolve("provenance.tsv"));
        assertThat(rewritten).doesNotContain("Gone.java");
        assertThat(rewritten).contains("AGen.java");
        assertThat(generated).exists();
    }

    @Test
    void a_tab_in_a_path_survives_the_tsv_and_the_stale_output_is_still_pruned(@TempDir Path tmp) throws Exception {
        Path srcOut = Files.createDirectories(tmp.resolve("gen-src"));
        Path classOut = Files.createDirectories(tmp.resolve("classes"));
        Path origin;
        try {
            origin = Files.writeString(tmp.resolve("Wid\tget.java"), "class Widget {}");
        } catch (IOException | InvalidPathException unsupported) {
            assumeTrue(false, "filesystem does not allow a tab in a name");
            return;
        }
        Path gen = Files.writeString(srcOut.resolve("WidgetGen.java"), "class WidgetGen {}");

        GeneratedProvenance prov = GeneratedProvenance.of(tmp);
        // Build 1: the processor generated WidgetGen.java from the tab-named origin.
        prov.reconcile(srcOut, classOut, List.of(origin), Map.of(gen, Set.of(origin)));
        assertThat(gen).exists();

        // Build 2: the origin recompiled, nothing regenerated → the generated file must be
        // pruned. With the record split on the raw tab, the row was malformed, silently
        // skipped, and the stale file survived forever.
        prov.reconcile(srcOut, classOut, List.of(origin), Map.of());
        assertThat(gen).as("the stale generated source is pruned").doesNotExist();
    }

    @Test
    void two_spellings_of_one_file_through_a_symlinked_source_root_reconcile_as_one(@TempDir Path tmp)
            throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = symlinkOrSkip(tmp.resolve("link"), real);
        Files.createDirectories(real.resolve("src"));
        Files.createDirectories(real.resolve("gen-src"));
        Files.createDirectories(real.resolve("classes"));
        Path workdir = Files.createDirectories(real.resolve("work"));
        Files.writeString(real.resolve("src/A.java"), "class A {}");
        Files.writeString(real.resolve("src/B.java"), "class B {}");
        Files.writeString(real.resolve("gen-src/AGen.java"), "class AGen {}");
        Files.writeString(real.resolve("gen-src/BGen.java"), "class BGen {}");
        Files.writeString(real.resolve("classes/AGen.class"), "");
        Files.writeString(real.resolve("classes/BGen.class"), "");

        // The build spells its roots and sources through the link; javac real-paths what it reports.
        GeneratedProvenance prov = GeneratedProvenance.of(workdir);
        prov.reconcile(
                link.resolve("gen-src"),
                link.resolve("classes"),
                List.of(link.resolve("src/A.java"), link.resolve("src/B.java")),
                Map.of(
                        real.resolve("gen-src/AGen.java"), Set.of(real.resolve("src/A.java")),
                        real.resolve("gen-src/BGen.java"), Set.of(real.resolve("src/B.java"))));

        // Only A recompiled, and it generated nothing: AGen is pruned through either spelling, BGen kept.
        prov.reconcile(link.resolve("gen-src"), link.resolve("classes"), List.of(link.resolve("src/A.java")), Map.of());
        assertThat(real.resolve("gen-src/AGen.java")).doesNotExist();
        assertThat(link.resolve("gen-src/AGen.java")).doesNotExist();
        assertThat(real.resolve("classes/AGen.class")).doesNotExist();
        assertThat(real.resolve("gen-src/BGen.java")).exists();
        assertThat(real.resolve("classes/BGen.class")).exists();
        assertThat(Files.readString(workdir.resolve("provenance.tsv")))
                .contains("BGen.java")
                .doesNotContain("AGen.java");
    }

    @Test
    void a_file_beneath_a_root_costs_no_filesystem_call_and_any_other_directory_is_asked_once(@TempDir Path tmp)
            throws Exception {
        Path gen = Files.createDirectories(tmp.resolve("gen"));
        Files.createDirectories(tmp.resolve("src/p"));
        Files.createDirectories(tmp.resolve("src/q"));
        GeneratedProvenance.Canon canon = new GeneratedProvenance.Canon(List.of(gen));
        assertThat(canon.realPathCalls()).as("the root itself").isEqualTo(1);

        canon.canonical(gen.resolve("a/X.java"));
        canon.canonical(gen.resolve("b/Y.java"));
        assertThat(canon.realPathCalls())
                .as("files beneath a root are answered lexically")
                .isEqualTo(1);

        Path first = canon.canonical(tmp.resolve("src/p/One.java"));
        canon.canonical(tmp.resolve("src/p/Two.java"));
        canon.canonical(tmp.resolve("src/p/Three.java"));
        canon.canonical(tmp.resolve("src/q/Four.java"));
        assertThat(canon.realPathCalls())
                .as("one call per directory, not per file")
                .isEqualTo(3);

        assertThat(canon.canonical(tmp.resolve("src/q/../p/One.java"))).isEqualTo(first);

        // A directory that does not exist yet resolves through its nearest existing ancestor, once.
        canon.canonical(tmp.resolve("src/none/Z.java"));
        canon.canonical(tmp.resolve("src/none/W.java"));
        assertThat(canon.realPathCalls()).isEqualTo(5);
        assertThat(canon.canonical(tmp.resolve("src/none/Z.java")))
                .isEqualTo(tmp.toRealPath().resolve("src/none/Z.java"));
    }

    @Test
    void a_root_reached_through_a_link_resolves_files_beneath_either_spelling_to_one(@TempDir Path tmp)
            throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = symlinkOrSkip(tmp.resolve("link"), real);
        GeneratedProvenance.Canon canon = new GeneratedProvenance.Canon(List.of(link));
        assertThat(canon.canonical(link.resolve("p/X.java"))).isEqualTo(canon.canonical(real.resolve("p/X.java")));
        assertThat(canon.realPathCalls()).isEqualTo(1);
    }

    private static Path symlinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException cannot) {
            assumeTrue(false, "filesystem cannot create a symlink: " + cannot);
            throw new AssertionError("unreachable");
        }
    }
}
