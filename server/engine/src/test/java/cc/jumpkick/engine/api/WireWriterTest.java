// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Await;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One connection's writer is shared by the plan's worker threads, the envelope's heartbeat
 * watchdog, the cancel path and the job-finish tail. A JSONL line is only usable if it arrives
 * whole, so every line goes through the stream's one writer thread. A client that reads slowly
 * paces its producers; a client that stops reading is dropped.
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
     * A client that stops reading — a suspended terminal, a wedged pipe — paces its producers once
     * its queue is full, and is dropped once it has read nothing for the idle bound: the producer
     * returns, the stream is dead for every later line and the socket is closed. The pipe here is
     * never read, so its buffer fills within the first few lines.
     */
    @Test
    @Timeout(60)
    void a_client_that_stops_reading_is_dropped_within_the_idle_bound_and_frees_its_producer() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 300);
        String line = failureSizedLine();
        // Twice the byte bound: whatever the pipe's own buffer swallows, the queue crosses it.
        long lines = 2 * WireWriter.MAX_QUEUED_BYTES / (line.length() + 1);

        long started = System.nanoTime();
        Thread producer = Thread.ofPlatform().start(() -> {
            for (long i = 0; i < lines; i++) WireWriter.sendQuiet(writer, line);
        });
        producer.join(Duration.ofSeconds(30).toMillis());
        assertThat(producer.isAlive())
                .as("the producer is let go once the client is dropped")
                .isFalse();
        long waitedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(waitedMs).as("bounded by the idle bound, not by the client").isLessThan(10_000);

        assertThatThrownBy(() -> WireWriter.send(writer, line))
                .as("the stream is dead for every later line")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stopped reading");
        Await.until(Duration.ofSeconds(5), () -> !pipe.sink().isOpen());
    }

    /**
     * A client that reads slower than a burst arrives is still reading: a burst of twice the byte
     * bound, in failure-report-sized lines, reaches a reader that takes far longer than the idle
     * bound to drain it, never pausing for as long as that bound. Every line lands, the producer
     * waits for room instead of growing the queue past the bound, and the stream stays alive.
     */
    @Test
    @Timeout(120)
    void a_slow_reader_survives_a_burst_past_the_byte_bound_and_holds_the_queue_to_it() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        long idleMs = 2_000;
        WireWriter.bind(writer, idleMs);
        String line = failureSizedLine();
        int lines = (int) (2 * WireWriter.MAX_QUEUED_BYTES / (line.length() + 1));

        AtomicLong received = new AtomicLong();
        AtomicLong newlines = new AtomicLong();
        Thread reader = Thread.ofPlatform().start(() -> {
            ByteBuffer buf = ByteBuffer.allocate(32 * 1024);
            try {
                while (newlines.get() <= lines) {
                    buf.clear();
                    int n = pipe.source().read(buf);
                    if (n < 0) return;
                    for (int i = 0; i < n; i++) if (buf.get(i) == '\n') newlines.incrementAndGet();
                    received.addAndGet(n);
                    // About 3 MB/s: the burst takes seconds, each pause a sliver of the bound.
                    Thread.sleep(10);
                }
            } catch (IOException | InterruptedException e) {
                // the stream died under the reader; the assertions below say so
            }
        });

        long started = System.nanoTime();
        AtomicLong maxQueued = new AtomicLong();
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < lines; i++) {
                WireWriter.sendQuiet(writer, line);
                maxQueued.accumulateAndGet(WireWriter.queuedBytes(writer), Math::max);
            }
        });
        producer.join(Duration.ofSeconds(90).toMillis());
        assertThat(producer.isAlive()).isFalse();
        WireWriter.send(writer, "{\"type\":\"job-finish\"}");
        reader.join(Duration.ofSeconds(30).toMillis());
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(newlines.get()).as("every line reached the slow reader").isEqualTo(lines + 1L);
        assertThat(tookMs)
                .as("the burst outlasted the idle bound, so only draining kept it alive")
                .isGreaterThan(idleMs);
        assertThat(maxQueued.get())
                .as("the producer waited for room rather than growing the queue")
                .isLessThanOrEqualTo(WireWriter.MAX_QUEUED_BYTES + line.length() + 1);
        assertThat(pipe.sink().isOpen()).isTrue();
    }

    /**
     * A CPU-pool worker that waits for a slow client to drain leaves the pool its parallelism: the
     * pool runs a spare thread, so another job's task on the same one-thread pool still runs.
     */
    @Test
    @Timeout(60)
    void a_pool_worker_waiting_for_room_does_not_starve_the_pool() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 20_000);
        String line = failureSizedLine();
        long lines = 2 * WireWriter.MAX_QUEUED_BYTES / (line.length() + 1);
        ForkJoinPool pool = new ForkJoinPool(1);
        try {
            CountDownLatch waiting = new CountDownLatch(1);
            pool.execute(() -> {
                for (long i = 0; i < lines; i++) {
                    if (WireWriter.queuedBytes(writer) + line.length() + 1 > WireWriter.MAX_QUEUED_BYTES) {
                        waiting.countDown();
                    }
                    WireWriter.sendQuiet(writer, line);
                }
            });
            assertThat(waiting.await(30, TimeUnit.SECONDS))
                    .as("the queue filled")
                    .isTrue();
            Thread.sleep(100);
            assertThat(pool.submit(() -> "ran").get(10, TimeUnit.SECONDS)).isEqualTo("ran");
        } finally {
            pipe.source().close();
            pool.shutdownNow();
        }
    }

    /** A cancel or a deadline from another thread is queued at once, whatever the client has left unread. */
    @Test
    @Timeout(30)
    void send_no_wait_queues_past_the_byte_bound_without_waiting() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 20_000);
        String line = failureSizedLine();
        long lines = 2 * WireWriter.MAX_QUEUED_BYTES / (line.length() + 1);
        long started = System.nanoTime();
        for (long i = 0; i < lines; i++) WireWriter.sendNoWait(writer, line);
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(tookMs).isLessThan(10_000);
        assertThat(WireWriter.queuedBytes(writer)).isGreaterThan(WireWriter.MAX_QUEUED_BYTES);
        pipe.source().close();
    }

    /** About the size of one test-failure line: a capped message and a capped stack. */
    private static String failureSizedLine() {
        return "{\"type\":\"error\",\"stack\":\"" + "s".repeat(38_000) + "\"}";
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
        Await.until(Duration.ofSeconds(5), () -> !pipe.sink().isOpen());
    }

    /**
     * A connection's bound moves with its phase: bound loose for request/reply, then given the job's
     * stream-idle bound once a job owns it — or the other way round — and the lines that follow are
     * held to the bound in force when they wait.
     */
    @Test
    @Timeout(30)
    void the_idle_bound_of_a_bound_stream_moves_with_the_connections_phase() throws Exception {
        Pipe pipe = Pipe.open();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8));
        WireWriter.bind(writer, 0);
        WireWriter.idleBound(writer, 300);
        String line = line(0, 0);
        for (int i = 0; i < 2_000; i++) WireWriter.sendQuiet(writer, line);

        long started = System.nanoTime();
        assertThatThrownBy(() -> WireWriter.send(writer, line))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stopped reading");
        long waitedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(waitedMs).as("bounded by the bound set after binding").isLessThan(5_000);
        Await.until(Duration.ofSeconds(5), () -> !pipe.sink().isOpen());
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
