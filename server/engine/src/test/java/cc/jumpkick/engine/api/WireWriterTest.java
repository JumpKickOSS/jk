// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One connection's writer is shared by the plan's worker threads, the envelope's heartbeat
 * watchdog, the cancel path and the job-finish tail. A JSONL line is only usable if it arrives
 * whole, so every line goes through the stream's one writer thread, and a client that stops
 * reading is dropped rather than waited for.
 *
 * <p>{@code BufferedWriter} synchronizes each individual call, so no write is ever torn
 * mid-string; the sequence is what races — two threads writing directly can emit both payloads
 * and then both newlines, yielding one merged line and one empty one. That is what the first test
 * looks for.
 */
class WireWriterTest {

    private static final int PRODUCERS = 8;
    private static final int LINES_EACH = 400;

    @Test
    void concurrent_producers_never_interleave_a_line() throws Exception {
        StringWriter sink = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sink);

        Set<String> expected = new HashSet<>();
        for (int p = 0; p < PRODUCERS; p++) {
            for (int i = 0; i < LINES_EACH; i++) {
                expected.add(line(p, i));
            }
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PRODUCERS);
        for (int p = 0; p < PRODUCERS; p++) {
            final int producer = p;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < LINES_EACH; i++) {
                        WireWriter.send(writer, line(producer, i));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("producers finished").isTrue();
        writer.flush();

        List<String> got = List.of(sink.toString().split("\n", -1));
        // split with -1 keeps a trailing empty element after the final newline; drop just that one.
        List<String> lines = got.subList(0, got.size() - 1);

        assertThat(lines).as("every line arrived, none merged or dropped").hasSize(PRODUCERS * LINES_EACH);
        assertThat(Set.copyOf(lines))
                .as("no line was merged with another or truncated")
                .isEqualTo(expected);
    }

    /** Long enough that a merged pair is unmistakable, and unique per (producer, index). */
    private static String line(int producer, int index) {
        String payload = "x".repeat(280);
        return "{\"type\":\"e\",\"p\":" + producer + ",\"i\":" + index + ",\"pad\":\"" + payload + "\"}";
    }

    @Test
    void send_quiet_tolerates_a_detached_job_with_no_writer() {
        // HTTP/MCP jobs have no wire at all; that is not an error path.
        assertThatCode(() -> WireWriter.sendQuiet(null, "{\"type\":\"e\"}")).doesNotThrowAnyException();
    }

    @Test
    void send_quiet_swallows_a_dead_client() throws Exception {
        BufferedWriter closed = new BufferedWriter(new StringWriter());
        closed.close();
        // The contract is that a gone client is not an error here — the cancel-watching read loop
        // sees the same disconnect. Not throwing IS the assertion.
        assertThatCode(() -> WireWriter.sendQuiet(closed, "{\"type\":\"e\"}")).doesNotThrowAnyException();
    }

    /**
     * The channel under the writer is interruptible, and the JDK closes it when the thread blocked
     * in it is interrupted. A cancelled runner is interrupted while it is still emitting progress,
     * so its line must be written by a thread nobody interrupts, or the socket dies under every
     * other producer.
     */
    @Test
    void a_line_sent_from_an_interrupted_thread_lands_and_leaves_the_channel_open() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        Thread.currentThread().interrupt();
        try {
            assertThatCode(() -> WireWriter.sendQuiet(writer, "{\"type\":\"progress\"}"))
                    .doesNotThrowAnyException();
        } finally {
            assertThat(Thread.interrupted())
                    .as("the interrupt stays the caller's")
                    .isTrue();
        }
        WireWriter.send(writer, "{\"type\":\"job-finish\"}");
        assertThat(pipe.sink().isOpen()).isTrue();
        ByteBuffer buf = ByteBuffer.allocate(256);
        pipe.source().read(buf);
        assertThat(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"progress\"}\n{\"type\":\"job-finish\"}\n");
    }

    /**
     * A client that stops reading — a suspended terminal, a wedged pipe — must not park the
     * producers of its events: they hand their lines to the stream and return, and once the stream
     * holds more than it will keep for a client that is not reading, the client is dropped and its
     * socket closed. The pipe here is never read, so its buffer fills within the first few lines.
     */
    @Test
    @Timeout(60)
    void a_client_that_stops_reading_parks_no_producer_and_is_dropped() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 0);
        String line = line(0, 0);
        // Twice the bound: whatever the pipe's own buffer swallows, the queue crosses it.
        long lines = 2 * WireWriter.MAX_QUEUED_BYTES / (line.length() + 1);

        Thread producer = Thread.ofPlatform().start(() -> {
            for (long i = 0; i < lines; i++) WireWriter.sendQuiet(writer, line);
        });
        producer.join(Duration.ofSeconds(30).toMillis());
        assertThat(producer.isAlive())
                .as("the producer returned without waiting for the client")
                .isFalse();

        assertThatThrownBy(() -> WireWriter.send(writer, line))
                .as("the stream is dead for every later line")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stopped reading");
        assertThat(pipe.sink().isOpen())
                .as("dropping the client closed its socket")
                .isFalse();
    }

    /** A line whose client has not read it within the stream's idle bound fails inside that bound. */
    @Test
    @Timeout(30)
    void a_send_to_a_client_that_does_not_read_fails_within_the_idle_bound() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 300);
        // Enough to fill any pipe buffer, so the writer thread is blocked in the socket.
        String line = line(0, 0);
        for (int i = 0; i < 2_000; i++) WireWriter.sendQuiet(writer, line);

        long started = System.nanoTime();
        assertThatThrownBy(() -> WireWriter.send(writer, line))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stopped reading");
        long waitedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(waitedMs).as("bounded by the idle bound, not by the client").isLessThan(5_000);
        assertThat(pipe.sink().isOpen()).isFalse();
    }

    /** Releasing a stream lands what it still holds first, so a job-finish handed over last is read. */
    @Test
    void release_waits_for_the_queued_lines_to_land() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 0);
        WireWriter.sendQuiet(writer, "{\"type\":\"progress\"}");
        WireWriter.sendQuiet(writer, "{\"type\":\"job-finish\"}");
        WireWriter.release(writer);
        writer.close();
        ByteBuffer buf = ByteBuffer.allocate(256);
        pipe.source().read(buf);
        assertThat(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"progress\"}\n{\"type\":\"job-finish\"}\n");
    }
}
