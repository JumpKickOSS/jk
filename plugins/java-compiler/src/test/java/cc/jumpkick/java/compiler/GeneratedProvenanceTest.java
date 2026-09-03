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
 * as malformed, was silently skipped, and the prune stopped for that file.
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
}
