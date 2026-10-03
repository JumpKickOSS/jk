// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which runner a test script runs, how it is asked for JUnit XML, and the XML read back. */
class NodeTestReportTest {

    @Test
    void the_runner_is_read_off_the_script() {
        assertThat(NodeTestReport.runner("vitest run")).isEqualTo(NodeTestReport.Runner.VITEST);
        assertThat(NodeTestReport.runner("tsc && vitest --run")).isEqualTo(NodeTestReport.Runner.VITEST);
        assertThat(NodeTestReport.runner("jest --ci")).isEqualTo(NodeTestReport.Runner.JEST);
        assertThat(NodeTestReport.runner("node --test test/")).isEqualTo(NodeTestReport.Runner.NODE_TEST);
        assertThat(NodeTestReport.runner("node --experimental-strip-types --test"))
                .isEqualTo(NodeTestReport.Runner.NODE_TEST);
        assertThat(NodeTestReport.runner("mocha")).isEqualTo(NodeTestReport.Runner.OTHER);
        assertThat(NodeTestReport.runner("node run-tests.js")).isEqualTo(NodeTestReport.Runner.OTHER);
    }

    @Test
    void each_runner_is_asked_for_its_report_its_own_way(@TempDir Path tmp) {
        Path xml = tmp.resolve("TEST-node.xml");
        assertThat(NodeTestReport.wiring(NodeTestReport.Runner.VITEST, xml, false, "")
                        .extraArgs())
                .contains("--reporter=junit", "--outputFile.junit=" + xml);
        assertThat(NodeTestReport.wiring(NodeTestReport.Runner.JEST, xml, false, "")
                        .extraArgs())
                .as("jest needs jest-junit installed")
                .isEmpty();
        assertThat(NodeTestReport.wiring(NodeTestReport.Runner.JEST, xml, true, "")
                        .env())
                .containsEntry("JEST_JUNIT_OUTPUT_FILE", xml.toString());
        assertThat(NodeTestReport.wiring(NodeTestReport.Runner.NODE_TEST, xml, false, "--max-old-space-size=512")
                        .env()
                        .get("NODE_OPTIONS"))
                .startsWith("--max-old-space-size=512 ")
                .contains("--test-reporter=junit --test-reporter-destination=" + xml);
        assertThat(NodeTestReport.wiring(NodeTestReport.Runner.OTHER, xml, false, "")
                        .extraArgs())
                .isEmpty();
    }

    @Test
    void nested_suites_are_counted_case_by_case(@TempDir Path tmp) throws IOException {
        Path xml = Files.writeString(tmp.resolve("r.xml"), """
                <testsuites>
                  <testsuite name="math">
                    <testsuite name="add"><testcase classname="add" name="ones"/></testsuite>
                    <testcase classname="math" name="divides"><failure message="expected 2" type="AssertionError">at x</failure></testcase>
                    <testcase classname="math" name="later"><skipped/></testcase>
                  </testsuite>
                </testsuites>
                """);
        TestSummary s = Objects.requireNonNull(NodeTestReport.read(xml, "g:web"));
        assertThat(List.of(s.total(), s.succeeded(), s.failed(), s.skipped())).containsExactly(3L, 1L, 1L, 1L);
        assertThat(s.failures().get(0).method()).isEqualTo("divides");
        assertThat(s.failures().get(0).message()).isEqualTo("expected 2");
        assertThat(NodeTestReport.read(tmp.resolve("absent.xml"), "g:web")).isNull();
    }

    @Test
    void a_tool_s_file_line_column_output_is_a_diagnostic() {
        assertThat(NodeProcess.diagnostic("src/app.ts(12,5): error TS2322: Type 'x' is not assignable"))
                .isEqualTo(new NodeProcess.Diagnostic("src/app.ts", 12, 5, "TS2322: Type 'x' is not assignable"));
        assertThat(NodeProcess.diagnostic("\u001B[31msrc/app.tsx:3:14: ERROR: Expected \";\"\u001B[0m"))
                .isEqualTo(new NodeProcess.Diagnostic("src/app.tsx", 3, 14, "ERROR: Expected \";\""));
        assertThat(NodeProcess.diagnostic("vite v6.0.0 building for production..."))
                .isNull();
    }
}
