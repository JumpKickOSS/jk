// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.testing.Await;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/** The keys {@code jk dev} reads on a terminal: what each asks for, and the mode given back. */
class DevKeysTest {

    /** Stdin the test feeds after the attach-time typeahead drain has run. */
    private static final class FeedStream extends InputStream {
        private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();

        void feed(char c) {
            queue.add((int) c);
        }

        @Override
        public int available() {
            return queue.size();
        }

        @Override
        public int read() {
            Integer b = queue.poll();
            return b == null ? -1 : b;
        }
    }

    @Test
    void r_c_and_q_in_either_case_are_the_three_actions_and_nothing_else_is() {
        assertThat(DevKeys.actionOf(new Key.Char('r'))).isEqualTo(DevKeys.Action.RESTART);
        assertThat(DevKeys.actionOf(new Key.Char('R'))).isEqualTo(DevKeys.Action.RESTART);
        assertThat(DevKeys.actionOf(new Key.Char('c'))).isEqualTo(DevKeys.Action.CLEAR);
        assertThat(DevKeys.actionOf(new Key.Char('q'))).isEqualTo(DevKeys.Action.QUIT);
        assertThat(DevKeys.actionOf(new Key.Char('x'))).isNull();
        assertThat(DevKeys.actionOf(new Key.Enter())).isNull();
        assertThat(DevKeys.actionOf(new Key.CtrlC())).isNull();
    }

    @Test
    void keys_pressed_arrive_in_order_and_close_gives_the_mode_back() throws Exception {
        var in = new FeedStream();
        in.feed('q'); // typed before the session: drained, not a command
        MemoryTerminal t = Terminals.memory(in, new ByteArrayOutputStream());
        DevKeys keys = Objects.requireNonNull(DevKeys.attach(t));
        try (keys) {
            assertThat(t.mode()).isEqualTo(InputMode.PLAN_KEYS);
            Await.until(Duration.ofSeconds(10), () -> in.available() == 0);
            in.feed('x');
            in.feed('r');
            in.feed('q');
            List<DevKeys.Action> seen = new ArrayList<>();
            Await.until(Duration.ofSeconds(10), () -> {
                DevKeys.Action next = keys.next();
                if (next != null) seen.add(next);
                return seen.size() == 2;
            });
            assertThat(seen).containsExactly(DevKeys.Action.RESTART, DevKeys.Action.QUIT);
        }
        assertThat(t.mode()).isEqualTo(InputMode.COOKED);
        keys.close();
        assertThat(t.mode()).as("a second close pops nothing").isEqualTo(InputMode.COOKED);
    }

    @Test
    void a_paused_reader_leaves_the_keys_for_whoever_reads_during_the_build() throws Exception {
        var in = new FeedStream();
        MemoryTerminal t = Terminals.memory(in, new ByteArrayOutputStream());
        DevKeys keys = Objects.requireNonNull(DevKeys.attach(t));
        try (keys) {
            keys.pause();
            in.feed('r');
            assertThat(t.readKey(Duration.ofSeconds(10)))
                    .as("the build's own listener reads the key")
                    .contains(new Key.Char('r'));
            assertThat(keys.next()).isNull();
            keys.resume();
            in.feed('q');
            Await.until(Duration.ofSeconds(10), () -> keys.next() == DevKeys.Action.QUIT);
        }
    }

    @Test
    void clear_writes_the_clear_screen_sequence_to_the_terminal() {
        MemoryTerminal t = Terminals.memory(new FeedStream(), new ByteArrayOutputStream());
        try (DevKeys keys = Objects.requireNonNull(DevKeys.attach(t))) {
            keys.clearScreen();
            assertThat(new String(t.written(), StandardCharsets.UTF_8)).contains("\u001b[H\u001b[2J");
        }
    }

    @Test
    void a_dead_terminal_reads_no_keys() {
        MemoryTerminal t = Terminals.memory(new FeedStream(), new ByteArrayOutputStream());
        t.close();
        assertThat(DevKeys.attach(t)).isNull();
        assertThat(t.mode()).isEqualTo(InputMode.COOKED);
    }
}
