// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which output lines become located diagnostics, and what they carry. */
class ToolDiagnosticsTest {

    @Test
    void path_line_and_message() {
        assertThat(ToolDiagnostics.parse("api/openapi.yaml:12: unknown property `foo`"))
                .contains(new ToolDiagnostics.Located("api/openapi.yaml", 12, 0, null, "unknown property `foo`"));
    }

    @Test
    void path_line_column_and_a_severity_word() {
        assertThat(ToolDiagnostics.parse("/abs/Grammar.g4:3:14: error: token recognition error"))
                .contains(new ToolDiagnostics.Located("/abs/Grammar.g4", 3, 14, "error", "token recognition error"));
        assertThat(ToolDiagnostics.parse("  src/x.xsd:7:1: warning - deprecated"))
                .contains(new ToolDiagnostics.Located("src/x.xsd", 7, 1, "warning", "deprecated"));
    }

    @Test
    void a_windows_path_keeps_its_drive() {
        assertThat(ToolDiagnostics.parse("C:\\work\\api.yaml:5: bad ref"))
                .contains(new ToolDiagnostics.Located("C:\\work\\api.yaml", 5, 0, null, "bad ref"));
    }

    @Test
    void unlocated_lines_are_log() {
        assertThat(ToolDiagnostics.parse("[main] INFO Generating with dryRun=false")).isEmpty();
        assertThat(ToolDiagnostics.parse("see https://example.com:8080: docs")).isEmpty();
        assertThat(ToolDiagnostics.parse("Processed 12 files")).isEmpty();
        assertThat(ToolDiagnostics.parse("")).isEmpty();
    }
}
