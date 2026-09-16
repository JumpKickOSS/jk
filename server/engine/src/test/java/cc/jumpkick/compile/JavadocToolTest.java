// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JavadocMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavadocToolTest {

    @Test
    void lenient_turns_doclint_off_and_strict_keeps_javadocs_own_checks() {
        List<String> lenient = JavadocTool.options(JavadocMode.LENIENT, 17);
        assertThat(lenient).contains("-Xdoclint:none", "-notimestamp", "-quiet").containsSequence("--release", "17");
        List<String> strict = JavadocTool.options(JavadocMode.STRICT, 17);
        assertThat(strict).doesNotContain("-Xdoclint:none").contains("-notimestamp");
        assertThat(JavadocTool.options(JavadocMode.LENIENT, 0)).doesNotContain("--release");
    }

    /** javadoc repeats a comment's problems once per index pass; the results want each once. */
    @Test
    void parse_splits_located_lines_by_severity_and_dedupes() {
        String output = String.join(
                "\n",
                "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                " * @custom something",
                "   ^",
                "src/com/example/One.java:12: warning: reference not found: Missing",
                "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                "src/com/example/Two.java:3: error: malformed HTML",
                "3 warnings",
                "1 error");
        JavadocTool.Result r = JavadocTool.parse(1, output);
        assertThat(r.success()).isFalse();
        assertThat(r.warnings())
                .containsExactly(
                        "src/com/example/One.java:6: warning: unknown tag. Unregistered custom tag?",
                        "src/com/example/One.java:12: warning: reference not found: Missing");
        assertThat(r.errors()).containsExactly("src/com/example/Two.java:3: error: malformed HTML");
        assertThat(r.output()).isEqualTo(output);
    }

    @Test
    void argfile_quotes_every_argument_and_joins_the_classpath() {
        List<String> lines = JavadocTool.argfileLines(
                Path.of("/tmp/out"),
                List.of(Path.of("/ws/lib/src/A.java")),
                List.of(Path.of("/ws/dep a.jar"), Path.of("/ws/classes")),
                List.of("-quiet", "-Xdoclint:none"));
        assertThat(lines).startsWith("-d", "\"/tmp/out\"", "\"-quiet\"", "\"-Xdoclint:none\"", "-classpath");
        assertThat(lines.get(5)).startsWith("\"/ws/dep a.jar").contains("/ws/classes\"");
        assertThat(lines).endsWith("\"/ws/lib/src/A.java\"");
        assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("\n"));
    }
}
