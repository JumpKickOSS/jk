// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The idle bound: one env definition for both ends, switchable between reads, and visible after it fires. */
class BoundedLineReaderTest {

    @Test
    void stream_idle_reads_millis_then_minutes_then_the_default() {
        assertThat(BoundedLineReader.streamIdleMillis(k -> null)).isEqualTo(BoundedLineReader.DEFAULT_STREAM_IDLE_MS);
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MS", "1500")::get))
                .isEqualTo(1500);
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MINUTES", "2")::get))
                .isEqualTo(120_000);
        assertThat(BoundedLineReader.streamIdleMillis(
                        Map.of("JK_STREAM_IDLE_MS", "0", "JK_STREAM_IDLE_MINUTES", "5")::get))
                .as("milliseconds win, and 0 means off")
                .isZero();
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MS", "soon")::get))
                .isEqualTo(BoundedLineReader.DEFAULT_STREAM_IDLE_MS);
    }

    @Test
    void a_stalled_read_closes_the_peer_and_reports_the_timeout() throws IOException {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                BoundedLineReader reader = new BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(accepted), StandardCharsets.UTF_8), accepted, 0);
                peer.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8)));
                assertThat(reader.readLine()).as("no bound: a normal line").isEqualTo("hello");
                assertThat(reader.timedOut()).isFalse();

                reader.idleTimeout(100);
                assertThatThrownBy(reader::readLine)
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("no protocol traffic");
                assertThat(reader.timedOut()).isTrue();
                assertThat(accepted.isOpen()).as("the timer closed the peer").isFalse();
            }
        }
    }

    /**
     * Every read with a live bound schedules a guard and cancels it once the line arrives. A
     * cancelled guard must leave the scheduler queue at once: the CLI reads every streamed event
     * line under a 60-minute bound, so a guard that lingered for its delay would pin one task and
     * its captured peer per line for the length of a build.
     */
    @Test
    void a_cancelled_guard_leaves_the_queue_at_once() throws IOException {
        int lines = 1000;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) text.append("event ").append(i).append('\n');
        Closeable peer = () -> {};
        BoundedLineReader reader = new BoundedLineReader(new StringReader(text.toString()), peer, 60L * 60_000L);

        int before = BoundedLineReader.pendingWatchdogs();
        for (int i = 0; i < lines; i++) {
            assertThat(reader.readLine()).isEqualTo("event " + i);
        }

        assertThat(BoundedLineReader.pendingWatchdogs())
                .as("the queue stays flat across reads; a stalled read elsewhere may hold one")
                .isLessThanOrEqualTo(before + 1);
    }

    /**
     * A native client runs on 128 MiB of heap; a line bound of 64 Mi chars would blow that heap
     * before it fired, since the buffer that holds a growing line peaks near three times its
     * length. Sized to the heap, the bound fails the read first and names the peer.
     */
    @Test
    void the_line_bound_is_sized_to_the_heap_and_fires_before_the_heap_does() {
        assertThat(BoundedLineReader.maxLineForHeap(128L << 20)).isEqualTo(16 << 20);
        assertThat(BoundedLineReader.maxLineForHeap(8L << 20))
                .as("never below the floor")
                .isEqualTo(BoundedLineReader.MIN_HEAP_MAX_LINE);
        assertThat(BoundedLineReader.maxLineForHeap(4L << 30))
                .as("never above the default")
                .isEqualTo(BoundedLineReader.DEFAULT_MAX_LINE);
        assertThat(BoundedLineReader.maxLineForHeap(Long.MAX_VALUE))
                .as("an unbounded heap reads as the default")
                .isEqualTo(BoundedLineReader.DEFAULT_MAX_LINE);

        BoundedLineReader reader = new BoundedLineReader(new StringReader("x".repeat(5_000)), null, 0, 4_096);
        assertThat(reader.maxLine()).isEqualTo(4_096);
        assertThatThrownBy(reader::readLine).isInstanceOf(IOException.class).hasMessageContaining("exceeds 4096 chars");
    }
}
