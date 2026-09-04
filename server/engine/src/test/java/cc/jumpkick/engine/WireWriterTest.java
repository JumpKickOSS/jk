// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * One connection's writer is shared by the plan's worker threads, the envelope's heartbeat
 * watchdog, the cancel path and the job-finish tail. A JSONL line is only usable if it arrives
 * whole, so every producer must hold the same monitor across write-line, write-newline, flush.
 *
 * <p>{@code BufferedWriter} synchronizes each individual call, which is what made the missing
 * monitor survive review: no write is ever torn mid-string. The sequence is what races — two
 * threads can emit both payloads and then both newlines, yielding one merged line and one empty
 * one. That is what this test looks for.
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
}
