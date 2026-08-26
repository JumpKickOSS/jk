// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.terminal.Width;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
        String plain = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).doesNotContain("@@source");
        assertThat(plain).doesNotContain("@@src");
        assertThat(plain).doesNotContain("Test Failure end");
        assertThat(plain).contains("FAILED Foo.bar()");
        assertThat(plain).contains("Foo.java:3");
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
        String plain = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).contains("Note: Recompile with -Xlint");
    }

    @Test
    void output_streams_live_and_is_not_held_until_step_finish() {
        // A hung run-tests step must still show its output under --verbose.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var v = new VerboseListener(out, out);
        v.output("run-tests", "test stdout: starting slow thing");
        // No stepFinish — the line is already on screen.
        String plain = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).contains("test stdout: starting slow thing");
    }

    @Test
    void failure_block_paints_at_its_footer_before_step_finish() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var v = new VerboseListener(out, out);
        v.output("run-tests", "before block");
        for (String line : List.of("Test Failure", "1 test failed", "", "FAILED Foo.bar()", "Test Failure end")) {
            v.output("run-tests", line);
        }
        v.output("run-tests", "after block");
        // Still no stepFinish: the block painted at its footer, trailing output streamed on.
        String plain = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).contains("before block");
        assertThat(plain).contains("FAILED Foo.bar()");
        assertThat(plain).doesNotContain("Test Failure end");
        assertThat(plain).contains("after block");
        // The block was consumed — stepFinish must not repaint it.
        int first = plain.indexOf("FAILED Foo.bar()");
        v.stepFinish("run-tests", "test", TaskStatus.FAIL, Duration.ofMillis(1));
        String after = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(after.indexOf("FAILED Foo.bar()", first + 1)).isNegative();
    }

    @Test
    void second_header_without_a_footer_flushes_the_first_block() {
        // A worker killed mid-block never delivers the footer; the next failure's header
        // must paint the stranded first block instead of appending into it — otherwise both
        // sit until stepFinish and paint as one malformed unit.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var v = new VerboseListener(out, out);
        for (String line : List.of("Test Failure", "1 test failed", "", "FAILED First.a()")) {
            v.output("run-tests", line); // no footer — stranded block
        }
        for (String line : List.of("Test Failure", "1 test failed", "", "FAILED Second.b()", "Test Failure end")) {
            v.output("run-tests", line);
        }
        String plain = Width.stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(plain).contains("FAILED First.a()");
        assertThat(plain).contains("FAILED Second.b()");
        assertThat(plain.indexOf("FAILED First.a()")).isLessThan(plain.indexOf("FAILED Second.b()"));
    }
}
