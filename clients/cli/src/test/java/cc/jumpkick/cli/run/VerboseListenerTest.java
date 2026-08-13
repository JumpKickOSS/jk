// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class VerboseListenerTest {

    @Test
    void paints_failure_block_and_does_not_leak_source_markers() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var v = new VerboseListener(out, out);
        for (String line : List.of(
                "Test Failure",
                "1 test failed",
                "",
                "FAILED Foo.bar()",
                "",
                "expected: \"1\"",
                " but was: \"2\"",
                "",
                "@@source path=Foo.java line=3 start=1 lang=java",
                "@@src 1|class Foo {",
                "@@src 3*|  void bar() {}",
                "@@src-end",
                "    AssertionFailedError thrown at line 3",
                "Test Failure end")) {
            v.output("run-tests", line);
        }
        v.stepFinish("run-tests", "test", TaskStatus.FAIL, Duration.ofMillis(12));
        String plain = AttributedString.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).doesNotContain("@@source");
        assertThat(plain).doesNotContain("@@src");
        assertThat(plain).doesNotContain("Test Failure end");
        assertThat(plain).contains("FAILED Foo.bar()");
        assertThat(plain).contains("Foo.java");
        assertThat(plain).contains("AssertionFailedError thrown at line 3");
        assertThat(plain).contains(DiagnosticReport.FOOTER);
    }

    @Test
    void non_failure_output_still_prints() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var v = new VerboseListener(out, out);
        v.output("compile-main", "Note: Recompile with -Xlint");
        v.stepFinish("compile-main", "compile", TaskStatus.SUCCESS, Duration.ofMillis(3));
        String plain = AttributedString.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).contains("Note: Recompile with -Xlint");
    }
}
