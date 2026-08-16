// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compiler blobs pick up file/line/col when folded into the journal. */
class BuildAccumulatorLocusTest {

    @Test
    void javac_blob_fills_file_line_and_caret_column() {
        String msg = String.join(
                "\n", "/ws/Foo.java:42: error: cannot find symbol", "    @Test", "     ^", "  symbol: class Test");
        var d = new BuildPlanResult.Diagnostic("compile-java", "javac", msg);
        BuildRecord.Diag out = BuildAccumulator.diagFromPlan("error", "/ws", "/ws", d);
        assertThat(out.file()).isEqualTo("/ws/Foo.java");
        assertThat(out.line()).isEqualTo(42);
        assertThat(out.col()).isEqualTo(6);
        assertThat(out.message()).isEqualTo(msg);
        assertThat(out.code()).isEqualTo("javac");
    }

    @Test
    void kotlinc_header_column_is_kept() {
        var d = new BuildPlanResult.Diagnostic(
                "compile-kotlin", "kotlinc", "src/Baz.kt:2:5: error: unresolved reference: Test");
        BuildRecord.Diag out = BuildAccumulator.diagFromPlan("error", "", "", d);
        assertThat(out.file()).isEqualTo("src/Baz.kt");
        assertThat(out.line()).isEqualTo(2);
        assertThat(out.col()).isEqualTo(5);
    }

    @Test
    void existing_file_and_line_are_not_overwritten() {
        var d = new BuildPlanResult.Diagnostic(
                "run-tests",
                "test-failure",
                "expected 1 but was 2",
                "",
                "AssertionError",
                "",
                "",
                "",
                "",
                "",
                "src/test/FooTest.java",
                9,
                0,
                List.of(),
                0);
        BuildRecord.Diag out = BuildAccumulator.diagFromPlan("error", "/ws", "/ws", d);
        assertThat(out.file()).isEqualTo("src/test/FooTest.java");
        assertThat(out.line()).isEqualTo(9);
        assertThat(out.col()).isZero();
    }

    @Test
    void preset_file_and_line_reject_a_column_from_a_quoted_locus() {
        var d = new BuildPlanResult.Diagnostic(
                "run-tests",
                "test-failure",
                "assertion failed while parsing Foo.java:42:7: error: fixture text",
                "",
                "AssertionError",
                "",
                "",
                "",
                "",
                "",
                "src/test/FooTest.java",
                9,
                0,
                List.of(),
                0);
        BuildRecord.Diag out = BuildAccumulator.diagFromPlan("error", "/ws", "/ws", d);
        assertThat(out.file()).isEqualTo("src/test/FooTest.java");
        assertThat(out.line()).isEqualTo(9);
        assertThat(out.col()).isZero();
    }
}
