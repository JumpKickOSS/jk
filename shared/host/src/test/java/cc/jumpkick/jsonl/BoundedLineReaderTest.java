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
                        .hasMessageContaining("no answer from the engine");
                assertThat(reader.timedOut()).isTrue();
                assertThat(accepted.isOpen()).as("the timer closed the peer").isFalse();
            }
        }
    }

    /**
     * The CLI reads every streamed event line under a 60-minute bound. Each read moves the one
     * watchdog's deadline instead of scheduling and cancelling a guard of its own, so a stream of
     * a thousand lines schedules it once.
     */
    @Test
    void a_stream_of_lines_schedules_the_watchdog_once() throws IOException {
        int lines = 1000;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) text.append("event ").append(i).append('\n');
        Closeable peer = () -> {};
        BoundedLineReader reader = new BoundedLineReader(new StringReader(text.toString()), peer, 60L * 60_000L);

        for (int i = 0; i < lines; i++) {
            assertThat(reader.readLine()).isEqualTo("event " + i);
        }
        assertThat(reader.readLine()).isNull();

        assertThat(reader.watchdogSchedules()).isEqualTo(1);
    }

    /** A bound shortened after a long one still fires on time; the long watchdog does not hide it. */
    @Test
    void a_shorter_bound_after_a_long_one_still_fires() throws IOException {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                BoundedLineReader reader = new BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(accepted), StandardCharsets.UTF_8),
                        accepted,
                        60L * 60_000L);
                peer.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8)));
                assertThat(reader.readLine()).isEqualTo("hello");

                reader.idleTimeout(100);
                assertThatThrownBy(reader::readLine).hasMessageContaining("no answer from the engine for 1s");
                assertThat(accepted.isOpen()).isFalse();
            }
        }
    }

    /** The bound is per read: a peer that keeps answering inside it never trips the watchdog. */
    @Test
    void lines_arriving_inside_the_bound_never_time_out() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                BoundedLineReader reader = new BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(accepted), StandardCharsets.UTF_8),
                        accepted,
                        300);
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(100);
                    peer.write(ByteBuffer.wrap(("line " + i + "\n").getBytes(StandardCharsets.UTF_8)));
                    assertThat(reader.readLine()).isEqualTo("line " + i);
                }
                assertThat(reader.timedOut()).isFalse();
                assertThat(accepted.isOpen()).isTrue();
            }
        }
    }

    @Test
    void every_line_ending_terminates_a_line_and_plain_reads_see_the_rest() throws IOException {
        BoundedLineReader reader = new BoundedLineReader(new StringReader("a\r\nb\rc\n\r\nd\r\nrest"));
        assertThat(reader.readLine()).isEqualTo("a");
        assertThat(reader.readLine()).isEqualTo("b");
        assertThat(reader.readLine()).isEqualTo("c");
        assertThat(reader.readLine()).isEqualTo("");
        assertThat(reader.readLine()).isEqualTo("d");
        assertThat(reader.read()).as("the \\n of a \\r\\n is not left behind").isEqualTo('r');
        char[] tail = new char[8];
        assertThat(reader.read(tail, 0, tail.length)).isEqualTo(3);
        assertThat(new String(tail, 0, 3)).isEqualTo("est");
        assertThat(reader.readLine()).isNull();
    }

    @Test
    void a_line_longer_than_the_buffer_reads_whole() throws IOException {
        String longLine = "y".repeat(50_000);
        BoundedLineReader reader = new BoundedLineReader(new StringReader(longLine + "\n" + longLine));
        assertThat(reader.readLine()).isEqualTo(longLine);
        assertThat(reader.readLine()).isEqualTo(longLine);
        assertThat(reader.readLine()).isNull();
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
        assertThatThrownBy(reader::readLine)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("longer than 4096 chars");
    }
}
