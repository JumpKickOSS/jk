// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.diagnostic.CompilerLocus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Worker JSONL diagnostics must come back with a parseable {@code path:line[:col]:} header. */
class WorkerDiagnosticsTest {

    @Test
    void groovy_structured_diagnostic_gets_a_javac_style_header() {
        CompileResult.Diagnostic d =
                WorkerDiagnostics.located("ERROR", "src/main/groovy/Foo.groovy", 12, 5, "unexpected token: }");
        assertThat(d.severity()).isEqualTo(CompileResult.Severity.ERROR);
        assertThat(d.source()).isEqualTo(Path.of("src/main/groovy/Foo.groovy"));
        assertThat(d.line()).isEqualTo(12);
        assertThat(d.column()).isEqualTo(5);
        assertThat(d.describe()).isEqualTo("src/main/groovy/Foo.groovy:12:5: error: unexpected token: }");
        CompilerLocus locus = requireNonNull(CompilerLocus.parse(d.describe()));
        assertThat(locus.file()).isEqualTo("src/main/groovy/Foo.groovy");
        assertThat(locus.line()).isEqualTo(12);
        assertThat(locus.col()).isEqualTo(5);
    }

    @Test
    void groovy_header_omits_column_zero() {
        CompileResult.Diagnostic d = WorkerDiagnostics.located("WARNING", "A.groovy", 3, 0, "deprecated");
        assertThat(d.describe()).isEqualTo("A.groovy:3: warning: deprecated");
        assertThat(requireNonNull(CompilerLocus.parse(d.describe())).line()).isEqualTo(3);
    }

    @Test
    void unlocated_worker_diagnostic_keeps_the_message_and_severity() {
        CompileResult.Diagnostic d =
                WorkerDiagnostics.located("ERROR", null, 0, 0, "General error during semantic analysis");
        assertThat(d.source()).isNull();
        assertThat(d.describe()).isEqualTo("General error during semantic analysis");
    }

    @Test
    void kotlinc_located_message_passes_verbatim_so_the_header_parses() {
        // Pre-fix the driver prefixed "ERROR: ", and the lazy header regex absorbed it into the
        // file name ("ERROR: src/Baz.kt") — unreadable for resolveSource and the dashboard link.
        CompileResult.Diagnostic d = WorkerDiagnostics.text("ERROR", "src/Baz.kt:2:5: error: unresolved reference: x");
        assertThat(d.describe()).isEqualTo("src/Baz.kt:2:5: error: unresolved reference: x");
        CompilerLocus locus = requireNonNull(CompilerLocus.parse(d.describe()));
        assertThat(locus.file()).isEqualTo("src/Baz.kt");
        assertThat(locus.line()).isEqualTo(2);
        assertThat(locus.col()).isEqualTo(5);
    }

    @Test
    void kotlinc_headerless_message_keeps_a_lowercase_severity_label() {
        CompileResult.Diagnostic d = WorkerDiagnostics.text("ERROR", "daemon connection failed");
        assertThat(d.severity()).isEqualTo(CompileResult.Severity.ERROR);
        assertThat(d.describe()).isEqualTo("error: daemon connection failed");
    }

    @Test
    void kotlinc_info_maps_to_note() {
        CompileResult.Diagnostic d = WorkerDiagnostics.text("INFO", "performing incremental compilation");
        assertThat(d.severity()).isEqualTo(CompileResult.Severity.NOTE);
    }
}
