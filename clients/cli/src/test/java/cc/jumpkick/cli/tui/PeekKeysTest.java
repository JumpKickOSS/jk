// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.Terminals;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The Ctrl-O listener on the seam terminal: the mode guard is always given back, the reader ends
 * when the plan settles, and only {@code 0x0F} dispatches.
 */
class PeekKeysTest {

    /** Queue-backed stdin the test can feed after the attach-time typeahead drain has run. */
    private static final class FeedStream extends InputStream {
        private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();

        void feed(int b) {
            queue.add(b);
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
    void ctrl_o_byte_dispatches_and_other_bytes_do_not() throws Exception {
        var in = new FeedStream();
        var fired = new AtomicInteger();
        var latch = new CountDownLatch(1);
        MemoryTerminal t = Terminals.memory(in, new ByteArrayOutputStream());
        PeekKeys keys = PeekKeys.attach(
                t,
                () -> {
                    fired.incrementAndGet();
                    latch.countDown();
                },
                () -> false);
        assertThat(keys).isNotNull();
        try (keys) {
            // Let the reader get past its attach-time typeahead drain before feeding.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!readerAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(60);
            // A non-Ctrl-O byte first: if it dispatched, fired would be 2 after the latch.
            in.feed('a');
            in.feed(0x0F);
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fired.get()).isEqualTo(1);
        }
    }

    @Test
    void finished_flipping_true_ends_the_reader() throws Exception {
        var finished = new AtomicBoolean(false);
        MemoryTerminal t = Terminals.memory(new FeedStream(), new ByteArrayOutputStream());
        PeekKeys keys = PeekKeys.attach(t, () -> {}, finished::get);
        assertThat(keys).isNotNull();
        try (keys) {
            assertThat(t.mode()).isEqualTo(InputMode.PLAN_KEYS);
            finished.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (readerAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(readerAlive()).isFalse();
        }
        assertThat(t.mode()).isEqualTo(InputMode.COOKED);
    }

    @Test
    void close_twice_restores_the_mode_exactly_once() {
        MemoryTerminal t = Terminals.memory(new FeedStream(), new ByteArrayOutputStream());
        try (ModeGuard outer = t.enter(InputMode.PROMPT)) {
            PeekKeys keys = PeekKeys.attach(t, () -> {}, () -> false);
            assertThat(keys).isNotNull();
            assertThat(t.mode()).isEqualTo(InputMode.PLAN_KEYS);
            keys.close();
            assertThat(t.mode()).isEqualTo(InputMode.PROMPT);
            // A second close must not pop the outer guard's mode.
            keys.close();
            assertThat(t.mode()).isEqualTo(InputMode.PROMPT);
        }
    }

    @Test
    void dead_terminal_attach_returns_null_with_no_mode_entered() {
        MemoryTerminal t = Terminals.memory(new FeedStream(), new ByteArrayOutputStream());
        t.close();
        PeekKeys keys = PeekKeys.attach(t, () -> {}, () -> false);
        assertThat(keys).isNull();
        assertThat(t.mode()).isEqualTo(InputMode.COOKED);
    }

    private static boolean readerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(th -> "jk-output-keys".equals(th.getName()) && th.isAlive());
    }
}
