// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.terminal.Terminals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The raw-mode prompt paints its question and hint, then the answer in the hint's place. */
class PromptSettleTest {

    private static Prompt<Boolean> confirm(String question) {
        return new Prompt<>(
                RichText.plain(question),
                List.of(new Prompt.Binding<>('y', "Yes", true), new Prompt.Binding<>('n', "No", false)),
                false,
                false);
    }

    private record Asked(boolean answer, String hintFrame, String settleFrame) {}

    /** Each flush of the prompt's stream, which ends one frame. */
    private static final class Frames extends ByteArrayOutputStream {
        final List<String> flushed = new ArrayList<>();

        @Override
        public void flush() {
            if (size() > 0) flushed.add(toString(StandardCharsets.UTF_8));
            reset();
        }
    }

    private static Asked ask(Prompt<Boolean> prompt, String keys) throws Exception {
        var frames = new Frames();
        var tty = Terminals.memory(
                new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        try (tty;
                var stream = new PrintStream(frames, false, StandardCharsets.UTF_8)) {
            boolean answer = NoAnsi.forcedAnsi(() -> prompt.ask(tty, stream));
            assertThat(frames.flushed).hasSize(2);
            return new Asked(answer, frames.flushed.get(0), frames.flushed.get(1));
        }
    }

    @Test
    void settle_shows_the_answer_where_the_hint_was() throws Exception {
        Asked asked = ask(confirm("Delete the cache?"), "y");
        assertThat(asked.answer()).isTrue();

        var screen = new LiveRegionScreen(4, 80);
        screen.write(asked.hintFrame());
        assertThat(screen.row(0).stripTrailing()).isEqualTo("Delete the cache? [y/N]");
        screen.write(asked.settleFrame());
        assertThat(screen.row(0).stripTrailing()).isEqualTo("Delete the cache? Yes");
        assertThat(screen.viewport().get(1)).isBlank();
    }

    @Test
    void settle_leaves_the_question_alone() throws Exception {
        Asked asked = ask(confirm("Delete the cache?"), "n");
        assertThat(asked.answer()).isFalse();
        assertThat(asked.settleFrame()).doesNotContain("Delete the cache?").contains("No");
    }
}
