// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.diagnostic.CompilerLocus;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class MavenCompilerDiagnosticsTest {

    /** A maven-compiler-plugin CompilationFailureException message, as the spy records it. */
    private static final String CAPTURED = String.join(
            "\n",
            "Compilation failure",
            "Compilation failure: ",
            "/ws/lib/src/main/java/com/example/Lib.java:[5,12] cannot find symbol",
            "  symbol:   class Missing",
            "  location: class com.example.Lib",
            "/ws/lib/src/main/java/com/example/Lib.java:[9,5] ';' expected",
            "");

    @Test
    void each_site_has_file_line_col_and_its_continuation_lines() {
        List<MavenCompilerDiagnostics.Site> sites = MavenCompilerDiagnostics.parse(CAPTURED);
        assertThat(sites).hasSize(2);
        MavenCompilerDiagnostics.Site first = sites.get(0);
        assertThat(first.file()).isEqualTo("/ws/lib/src/main/java/com/example/Lib.java");
        assertThat(first.line()).isEqualTo(5);
        assertThat(first.col()).isEqualTo(12);
        assertThat(first.message())
                .isEqualTo("cannot find symbol\n  symbol:   class Missing\n  location: class com.example.Lib");
        assertThat(sites.get(1).line()).isEqualTo(9);
        assertThat(sites.get(1).col()).isEqualTo(5);
        assertThat(sites.get(1).message()).isEqualTo("';' expected");
    }

    @Test
    void a_header_is_what_the_journal_locus_parser_reads() {
        MavenCompilerDiagnostics.Site site =
                MavenCompilerDiagnostics.parse(CAPTURED).get(0);
        CompilerLocus locus = Objects.requireNonNull(CompilerLocus.parse(site.asHeader()), "no compiler locus");
        assertThat(locus.file()).isEqualTo("/ws/lib/src/main/java/com/example/Lib.java");
        assertThat(locus.line()).isEqualTo(5);
        assertThat(locus.col()).isEqualTo(12);
    }

    @Test
    void a_site_repeated_by_the_short_and_long_message_is_one_site() {
        String twice = "/ws/A.java:[3,4] boom\nCompilation failure\n/ws/A.java:[3,4] boom";
        assertThat(MavenCompilerDiagnostics.parse(twice)).hasSize(1);
    }

    @Test
    void a_test_failure_message_names_no_site() {
        assertThat(MavenCompilerDiagnostics.parse(
                        "There are test failures.\n\nPlease refer to target/surefire-reports"))
                .isEmpty();
    }
}
