// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The runner's string rules, without a process or an IDE. */
class JkCliLinesTest {

    @Test
    void stream_kind_is_read_off_the_output_type_name() {
        assertThat(JkCliLines.isStderr("stderr")).isTrue();
        assertThat(JkCliLines.isStderr("STDERR")).isTrue();
        assertThat(JkCliLines.isStderr("ProcessOutputType[STDERR]")).isTrue();
        assertThat(JkCliLines.isStderr("stdout")).isFalse();
        assertThat(JkCliLines.isStderr("system")).isFalse();
    }

    @Test
    void a_capture_keeps_the_whole_transcript() {
        StringBuilder sb = new StringBuilder();
        String chunk = "x".repeat(50_000);
        for (int i = 0; i < 4; i++) JkCliLines.append(sb, chunk, false);
        assertThat(sb.length()).isEqualTo(200_000);
    }

    @Test
    void a_streaming_run_keeps_only_the_tail_and_the_tail_is_the_newest_text() {
        StringBuilder sb = new StringBuilder();
        JkCliLines.append(sb, "a".repeat(JkCliLines.STREAM_RETAIN_CHARS * 2), true);
        assertThat(sb.length()).isEqualTo(JkCliLines.STREAM_RETAIN_CHARS * 2); // at the cap, not over it: kept
        JkCliLines.append(sb, "b".repeat(10), true);
        assertThat(sb.length()).isEqualTo(JkCliLines.STREAM_RETAIN_CHARS);
        assertThat(sb.toString().endsWith("bbbbbbbbbb")).isTrue();
        assertThat(sb.toString().startsWith("a".repeat(JkCliLines.STREAM_RETAIN_CHARS + 1)))
                .isFalse();
    }

    @Test
    void the_progress_line_is_stripped_and_capped_at_100_chars() {
        assertThat(JkCliLines.trimLine("  compiling \n")).isEqualTo("compiling");
        String longLine = "y".repeat(150);
        String trimmed = JkCliLines.trimLine(longLine);
        assertThat(trimmed.length()).isEqualTo(98);
        assertThat(trimmed.endsWith("…")).isTrue();
        assertThat(trimmed.substring(0, 97)).isEqualTo("y".repeat(97));
        assertThat(JkCliLines.trimLine("z".repeat(100))).isEqualTo("z".repeat(100));
    }

    @Test
    void a_failure_is_reported_by_its_first_error_line() {
        assertThat(JkCliLines.firstErrorLine("\nno jk.toml in /w\n  at Foo.bar\n", "{\"x\":1}", "?"))
                .isEqualTo("no jk.toml in /w");
        assertThat(JkCliLines.firstErrorLine("", "{\"type\":\"progress\"}\n✗ IDE  engine refused\n", "?"))
                .isEqualTo("✗ IDE  engine refused");
        assertThat(JkCliLines.firstErrorLine(null, "{\"type\":\"progress\"}", "jk failed (exit 2)"))
                .isEqualTo("jk failed (exit 2)");
    }

    @Test
    void args_is_a_mutable_copy() {
        List<String> args = JkCliRunner.args("build", "--flat");
        args.add("--offline");
        assertThat(args).isEqualTo(List.of("build", "--flat", "--offline"));
    }

    @Test
    void a_result_is_ok_only_at_exit_zero() {
        assertThat(new JkCliRunner.Result(0, "", "").ok()).isTrue();
        assertThat(new JkCliRunner.Result(1, "", "").ok()).isFalse();
        assertThat(new JkCliRunner.Result(130, "", "").ok()).isFalse();
    }
}
