// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link Confirm#ask()} takes the cooked (non-TTY) fallback — the same path piped/CI input hits —
 * when the render mode is not ANSI. Each case below pins plain mode rather than trusting the
 * ambient one: a Windows worker has a real console attached, and on the raw path {@code ask()}
 * reads a keystroke from that console instead of the {@code System.in} these cases inject, which
 * blocks until the suite's timeout rather than failing. With the mode pinned we drive it via
 * {@code System.in} and assert the y/n/default/EOF semantics, plus that the prompt is written to
 * stderr (so it stays visible when stdout is redirected).
 */
class ConfirmTest {

    @Test
    void typed_yes_and_no_are_honored() throws Exception {
        assertThat(askWith("y\n", /*defaultYes*/ false)).isTrue();
        assertThat(askWith("yes\n", false)).isTrue();
        assertThat(askWith("n\n", /*defaultYes*/ true)).isFalse();
        assertThat(askWith("no\n", true)).isFalse();
    }

    @Test
    void empty_line_takes_the_default() throws Exception {
        assertThat(askWith("\n", /*defaultYes*/ true)).isTrue();
        assertThat(askWith("\n", /*defaultYes*/ false)).isFalse();
    }

    @Test
    void eof_declines_regardless_of_default() throws Exception {
        assertThat(askWith("", /*defaultYes*/ true)).isFalse();
        assertThat(askWith("", /*defaultYes*/ false)).isFalse();
    }

    @Test
    void prompt_is_written_to_stderr_not_stdout() throws Exception {
        InputStream savedIn = System.in;
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            NoAnsi.forced(() -> Confirm.of("Proceed?", false).ask());
        } finally {
            System.setIn(savedIn);
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
        // The question rides stderr so `cmd | less` still shows it; stdout stays clean for piping.
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Proceed?");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("Proceed?");
    }

    @Test
    void raw_mode_needs_both_a_promptable_human_and_ansi() {
        // --no-ansi (or TERM=dumb etc.) must take the cooked line-input path even when a
        // human is on a TTY — raw keystroke intercept and the CSI settle assume ANSI capability.
        assertThat(Confirm.rawEligible(true, true)).isTrue();
        assertThat(Confirm.rawEligible(true, false)).isFalse();
        assertThat(Confirm.rawEligible(false, true)).isFalse();
        assertThat(Confirm.rawEligible(false, false)).isFalse();
    }

    @Test
    void cooked_path_settles_a_plain_answer_on_stderr() throws Exception {
        InputStream savedIn = System.in;
        PrintStream savedErr = System.err;
        var err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            NoAnsi.forced(() -> Confirm.of("Proceed?", false).ask());
        } finally {
            System.setIn(savedIn);
            System.setErr(savedErr);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Yes");
    }

    private static boolean askWith(String input, boolean defaultYes) throws Exception {
        InputStream savedIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            return NoAnsi.forced(() -> Confirm.of("Proceed?", defaultYes).ask());
        } finally {
            System.setIn(savedIn);
        }
    }
}
