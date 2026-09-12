// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** One stream, one line shape, one redaction, and a threshold that hides what it should. */
class LogTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @AfterEach
    void restoreStderr() {
        Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
    }

    private void install(System.Logger.Level level, UnaryOperator<String> redact) {
        Log.install(new PrintStream(bytes, true, StandardCharsets.UTF_8), level, redact);
    }

    private String written() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void every_line_is_time_level_message_and_the_threshold_hides_debug() {
        install(System.Logger.Level.INFO, UnaryOperator.identity());

        Log.debug("not this one");
        Log.info("jk engine: listening", "pid", 42);
        Log.warn("slow", "ms", 1200);

        assertThat(Log.debugEnabled()).isFalse();
        String[] lines = written().strip().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d INFO  jk engine: listening pid=42");
        assertThat(lines[1]).matches("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d WARN  slow ms=1200");
    }

    @Test
    void debug_level_lets_debug_lines_through() {
        install(System.Logger.Level.DEBUG, UnaryOperator.identity());

        Log.debug("perf forecast", "ms", 7);

        assertThat(Log.debugEnabled()).isTrue();
        assertThat(written()).contains(" DEBUG perf forecast ms=7");
    }

    @Test
    void the_redaction_covers_the_message_and_the_stack() {
        String secret = "hunter2-token-9f8e7d";
        install(System.Logger.Level.DEBUG, text -> text.replace(secret, "***"));

        Log.error("login failed for " + secret, new IllegalStateException("bad " + secret));

        String out = written();
        assertThat(out).doesNotContain(secret);
        assertThat(out).contains("ERROR login failed for ***");
        assertThat(out).contains("IllegalStateException: bad ***");
        assertThat(out).contains("at cc.jumpkick.host.LogTest");
    }

    @Test
    void detail_quotes_values_that_would_split_a_line() {
        assertThat(Log.detail("units", 3, "dir", "a b", "empty", "")).isEqualTo(" units=3 dir=\"a b\" empty=\"\"");
        assertThat(Log.detail()).isEmpty();
    }

    @Test
    void level_names_are_the_four_jk_uses_in_any_case() {
        assertThat(Log.level("debug")).contains(System.Logger.Level.DEBUG);
        assertThat(Log.level("INFO")).contains(System.Logger.Level.INFO);
        assertThat(Log.level("warn")).contains(System.Logger.Level.WARNING);
        assertThat(Log.level("Warning")).contains(System.Logger.Level.WARNING);
        assertThat(Log.level(" error ")).contains(System.Logger.Level.ERROR);
        assertThat(Log.level("verbose")).isEmpty();
        assertThat(Log.level(null)).isEmpty();
    }

    @Test
    void the_level_is_jk_s_alone_and_other_loggers_stay_at_info() {
        install(System.Logger.Level.DEBUG, UnaryOperator.identity());
        Logger.getLogger("jdk.httpserver.probe").fine("platform chatter");
        Logger.getLogger("jdk.httpserver.probe").info("platform notice");
        Log.debug("jk detail");
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(out).doesNotContain("platform chatter");
        assertThat(out).contains("platform notice");
        assertThat(out).contains("jk detail");
    }
}
