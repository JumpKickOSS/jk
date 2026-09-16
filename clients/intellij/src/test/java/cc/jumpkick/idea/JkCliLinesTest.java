// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The runner's string rules, without a process or an IDE. */
public class JkCliLinesTest {

    @Test
    public void stream_kind_is_read_off_the_output_type_name() {
        assertTrue(JkCliLines.isStderr("stderr"));
        assertTrue(JkCliLines.isStderr("STDERR"));
        assertTrue(JkCliLines.isStderr("ProcessOutputType[STDERR]"));
        assertFalse(JkCliLines.isStderr("stdout"));
        assertFalse(JkCliLines.isStderr("system"));
    }

    @Test
    public void a_capture_keeps_the_whole_transcript() {
        StringBuilder sb = new StringBuilder();
        String chunk = "x".repeat(50_000);
        for (int i = 0; i < 4; i++) JkCliLines.append(sb, chunk, false);
        assertEquals(200_000, sb.length());
    }

    @Test
    public void a_streaming_run_keeps_only_the_tail_and_the_tail_is_the_newest_text() {
        StringBuilder sb = new StringBuilder();
        JkCliLines.append(sb, "a".repeat(JkCliLines.STREAM_RETAIN_CHARS * 2), true);
        assertEquals(JkCliLines.STREAM_RETAIN_CHARS * 2, sb.length()); // at the cap, not over it: kept
        JkCliLines.append(sb, "b".repeat(10), true);
        assertEquals(JkCliLines.STREAM_RETAIN_CHARS, sb.length());
        assertTrue(sb.toString().endsWith("bbbbbbbbbb"));
        assertFalse(sb.toString().startsWith("a".repeat(JkCliLines.STREAM_RETAIN_CHARS + 1)));
    }

    @Test
    public void the_progress_line_is_stripped_and_capped_at_100_chars() {
        assertEquals("compiling", JkCliLines.trimLine("  compiling \n"));
        String longLine = "y".repeat(150);
        String trimmed = JkCliLines.trimLine(longLine);
        assertEquals(98, trimmed.length());
        assertTrue(trimmed.endsWith("…"));
        assertEquals("y".repeat(97), trimmed.substring(0, 97));
        assertEquals("z".repeat(100), JkCliLines.trimLine("z".repeat(100)));
    }

    @Test
    public void a_failure_is_reported_by_its_first_error_line() {
        assertEquals(
                "no jk.toml in /w", JkCliLines.firstErrorLine("\nno jk.toml in /w\n  at Foo.bar\n", "{\"x\":1}", "?"));
        assertEquals(
                "✗ IDE  engine refused",
                JkCliLines.firstErrorLine("", "{\"type\":\"progress\"}\n✗ IDE  engine refused\n", "?"));
        assertEquals(
                "jk failed (exit 2)", JkCliLines.firstErrorLine(null, "{\"type\":\"progress\"}", "jk failed (exit 2)"));
    }

    @Test
    public void args_is_a_mutable_copy() {
        List<String> args = JkCliRunner.args("build", "--flat");
        args.add("--offline");
        assertEquals(List.of("build", "--flat", "--offline"), args);
    }

    @Test
    public void a_result_is_ok_only_at_exit_zero() {
        assertTrue(new JkCliRunner.Result(0, "", "").ok());
        assertFalse(new JkCliRunner.Result(1, "", "").ok());
        assertFalse(new JkCliRunner.Result(130, "", "").ok());
    }
}
