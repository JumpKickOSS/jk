// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.model.JavadocMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The step's decision table over javadoc's exit. Only strict mode fails the step; the lenient
 * default turns an error — the shape an older JDK's javadoc reports for a malformed comment with
 * doclint off — into warnings and still writes a jar.
 */
class PlannerJavadocTest {

    private static final String MALFORMED =
            "src/main/java/com/xxl/job/admin/core/trigger/JobTrigger.java:52:" + " error: malformed HTML";

    private static final JavadocTool.Result MALFORMED_HTML =
            new JavadocTool.Result(1, List.of(), List.of(MALFORMED), MALFORMED + "\n * a < b\n1 error\n");

    @Test
    void a_lenient_javadoc_error_is_a_warning_and_the_jar_is_what_javadoc_wrote() {
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, MALFORMED_HTML, true))
                .isEqualTo(PlannerJavadoc.Verdict.LENIENT_TREE);
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, MALFORMED_HTML, false))
                .isEqualTo(PlannerJavadoc.Verdict.LENIENT_README);
        assertThat(PlannerJavadoc.errorLines(MALFORMED_HTML)).containsExactly(MALFORMED);
    }

    @Test
    void only_strict_mode_fails_the_step() {
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, MALFORMED_HTML, true))
                .isEqualTo(PlannerJavadoc.Verdict.FAIL);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, MALFORMED_HTML, false))
                .isEqualTo(PlannerJavadoc.Verdict.FAIL);
    }

    @Test
    void a_clean_exit_documents_and_a_source_set_without_api_is_the_readme_jar_in_both_modes() {
        JavadocTool.Result clean =
                new JavadocTool.Result(0, List.of("Foo.java:6: warning: unknown tag"), List.of(), "");
        JavadocTool.Result noApi = new JavadocTool.Result(
                1, List.of(), List.of(), "javadoc: error - No public or protected classes found to document.\n");

        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, clean, true))
                .isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, clean, true))
                .isEqualTo(PlannerJavadoc.Verdict.DOCUMENTED);
        assertThat(PlannerJavadoc.verdict(JavadocMode.LENIENT, noApi, false)).isEqualTo(PlannerJavadoc.Verdict.NO_API);
        assertThat(PlannerJavadoc.verdict(JavadocMode.STRICT, noApi, false)).isEqualTo(PlannerJavadoc.Verdict.NO_API);
    }

    /** A failure javadoc did not locate still names its exit and shows its output. */
    @Test
    void an_unlocated_failure_reports_the_exit_and_the_output() {
        JavadocTool.Result crashed = new JavadocTool.Result(2, List.of(), List.of(), "javadoc: error - cannot read");

        assertThat(PlannerJavadoc.errorLines(crashed))
                .containsExactly("javadoc exited 2\njavadoc: error - cannot read");
    }
}
