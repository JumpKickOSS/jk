// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.engine.plugin.WorkerScope;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pool's bookkeeping against workers that answer from the test instead of a JVM: items go to one
 * worker while it has free threads, every frame reaches the item whose id it carries, a death puts
 * what was in flight back once, a cancel stops one compile and leaves the rest, and the worker's heap
 * and trim answers reach the engine.
 */
class CompilerPoolTest {

    @BeforeEach
    void manySlots() {
        CompilerBudget.slotsForTests(64);
        CompilerPool.closeWaitForTests(2_000);
    }

    @AfterEach
    void hostSlots() {
        CompilerBudget.slotsForTests(0);
        CompilerPool.closeWaitForTests(15_000);
    }

    @Test
    void items_fill_the_workers_threads_and_each_gets_its_own_frames(@TempDir Path dir) throws Exception {
        Fakes fakes = new Fakes(3);
        CompilerPool pool = pool(dir, fakes);
        List<CompileWork> items = items(dir, 5, job());
        for (CompileWork w : items) pool.enqueue(w);

        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().size() == 3, "three items in flight on three threads");
        assertThat(pool.queued()).isEqualTo(2);
        assertThat(fakes.started).as("one worker for the whole queue").hasSize(1);

        // Frames of different compiles interleave; each lands on the item it names.
        long first = worker.dispatched().get(0);
        long second = worker.dispatched().get(1);
        worker.frame(diagnostic(second, "B.java", "second's error"));
        worker.frame(diagnostic(first, "A.java", "first's warning"));
        worker.frame(result(second, "ERROR"));
        awaitTrue(() -> worker.dispatched().size() == 4, "a returned thread takes the next item");
        worker.frame(result(first, "OK"));

        ForkedJavac.Result a = byId(items, first).compile.get(10, TimeUnit.SECONDS);
        ForkedJavac.Result b = byId(items, second).compile.get(10, TimeUnit.SECONDS);
        assertThat(a.success()).isTrue();
        assertThat(a.diagnostics())
                .singleElement()
                .satisfies(d -> assertThat(d.toString()).contains("A.java").contains("first's warning"));
        assertThat(b.success()).isFalse();
        assertThat(b.diagnostics())
                .singleElement()
                .satisfies(d -> assertThat(d.toString()).contains("B.java").contains("second's error"));
        pool.close();
    }

    @Test
    void a_worker_that_dies_with_items_in_flight_runs_each_again_one_at_a_time_on_a_fresh_worker(@TempDir Path dir)
            throws Exception {
        Fakes fakes = new Fakes(3);
        CompilerPool pool = pool(dir, fakes);
        List<CompileWork> items = items(dir, 3, job());
        for (CompileWork w : items) pool.enqueue(w);
        Fake first = fakes.await(0);
        awaitTrue(() -> first.dispatched().size() == 3, "all three in flight");

        first.die("zinc worker exited with status 134");
        Fake second = fakes.await(1);
        for (int n = 1; n <= 3; n++) {
            int sent = n;
            awaitTrue(() -> second.dispatched().size() == sent, "item " + sent + " runs again");
            Thread.sleep(50);
            assertThat(second.dispatched())
                    .as("compiles a death put back run one at a time")
                    .hasSize(sent);
            second.frame(result(second.dispatched().get(sent - 1), "OK"));
        }
        assertThat(second.dispatched()).containsExactlyInAnyOrderElementsOf(first.dispatched());
        for (CompileWork w : items) {
            assertThat(w.compile.get(10, TimeUnit.SECONDS).success()).isTrue();
            assertThat(w.deaths).isEqualTo(1);
        }

        List<CompileWork> later = items(dir, 2, job());
        for (CompileWork w : later) pool.enqueue(w);
        awaitTrue(() -> second.dispatched().size() == 5, "once they answered, items go out together again");
        assertThat(fakes.started).hasSize(2);
        pool.close();
    }

    @Test
    void a_compile_that_kills_its_worker_twice_fails_alone_and_its_neighbours_succeed(@TempDir Path dir)
            throws Exception {
        Fakes fakes = new Fakes(3);
        CompilerPool pool = pool(dir, fakes);
        List<CompileWork> items = items(dir, 3, job());
        for (CompileWork w : items) pool.enqueue(w);
        Fake first = fakes.await(0);
        awaitTrue(() -> first.dispatched().size() == 3, "all three in flight");
        long culprit = first.dispatched().get(2);
        first.die("zinc worker exited with status 1");

        Fake second = fakes.await(1);
        for (int i = 0; ; i++) {
            int index = i;
            awaitTrue(() -> second.dispatched().size() > index, "the next item runs alone");
            assertThat(second.dispatched())
                    .as("compiles a death put back run one at a time")
                    .hasSize(index + 1);
            long id = second.dispatched().get(index);
            if (id == culprit) break;
            second.frame(result(id, "OK"));
        }
        second.print("Exception in thread \"jk-javac-1\" java.lang.IllegalStateException: boom");
        second.die("zinc worker exited with status 1");

        for (CompileWork w : items) {
            if (w.id == culprit) {
                assertThatThrownBy(w.compile::join)
                        .cause()
                        .hasMessageContaining("status 1")
                        .hasMessageContaining("boom");
            } else {
                assertThat(w.compile.get(10, TimeUnit.SECONDS).success())
                        .as("a neighbour of the compile that killed the worker")
                        .isTrue();
            }
        }
        assertThat(fakes.started).as("no third worker: nothing is left to run").hasSize(2);
        pool.close();
    }

    @Test
    void a_compile_of_an_ended_job_is_refused_and_one_that_ignores_its_cancel_has_its_worker_stopped(@TempDir Path dir)
            throws Exception {
        WorkerScope ended = new WorkerScope(job());
        ended.shutdown(0L);
        Fakes fakes = new Fakes(2);
        CompilerPool pool = pool(dir, fakes);
        CompileWork late = ended.call(() -> item(dir, "late", ended.id()));
        pool.enqueue(late);
        assertThatThrownBy(late.compile::join).cause().isInstanceOf(InterruptedIOException.class);
        assertThat(fakes.started).as("no worker for a compile nobody waits on").isEmpty();

        long stuck = job();
        CompileWork deaf = item(dir, "deaf", stuck);
        CompileWork other = item(dir, "other", job());
        pool.enqueue(deaf);
        pool.enqueue(other);
        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().size() == 2, "both in flight");
        pool.abandon(w -> w.job == stuck);
        CompilerPool.awaitOrStop(stuck, Duration.ofMillis(200));

        assertThat(worker.killed)
                .as("the worker whose compile ignored its cancel is stopped")
                .isTrue();
        assertThat(deaf.compile).isCompletedExceptionally();
        Fake next = fakes.await(1);
        awaitTrue(() -> next.dispatched().contains(other.id), "the other job's compile runs again");
        next.frame(result(other.id, "OK"));
        assertThat(other.compile.get(10, TimeUnit.SECONDS).success()).isTrue();
        assertThat(other.deaths)
                .as("a stop for another job's compile is charged to nobody")
                .isZero();
        pool.close();
    }

    @Test
    void close_stops_a_worker_that_never_connected_and_fails_the_queue(@TempDir Path dir) throws Exception {
        CompilerPool.closeWaitForTests(200);
        {
            Fakes fakes = new Fakes(0, false);
            CompilerPool pool = pool(dir, fakes);
            CompileWork w = item(dir, "never", job());
            pool.enqueue(w);
            Fake worker = fakes.await(0);
            pool.close();
            assertThat(worker.killed).isTrue();
            assertThat(w.compile).isCompletedExceptionally();
        }
    }

    @Test
    void a_cancel_stops_one_compile_and_its_neighbours_go_on(@TempDir Path dir) throws Exception {
        Fakes fakes = new Fakes(2);
        CompilerPool pool = pool(dir, fakes);
        long myJob = job();
        CompileWork mine = items(dir, 1, myJob).getFirst();
        CompileWork theirs = items(dir, 1, job()).getFirst();
        pool.enqueue(mine);
        pool.enqueue(theirs);
        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().size() == 2, "both in flight");

        pool.abandon(w -> w.job == myJob);
        awaitTrue(() -> worker.sentOps().contains(PluginProtocol.OP_CANCEL + " " + mine.id), "the cancel is sent");
        assertThat(worker.sentOps()).doesNotContain(PluginProtocol.OP_CANCEL + " " + theirs.id);
        worker.frame("{\"id\":" + mine.id + ",\"t\":\"" + PluginProtocol.CANCELLED + "\"}");

        assertThatThrownBy(mine.compile::join).cause().isInstanceOf(InterruptedIOException.class);
        assertThat(theirs.compile).isNotDone();
        worker.frame(result(theirs.id, "OK"));
        assertThat(theirs.compile.get(10, TimeUnit.SECONDS).success()).isTrue();
        assertThat(fakes.started).as("the cancel stopped no process").hasSize(1);
        pool.close();
    }

    @Test
    void heap_frames_move_the_lease_and_an_idle_worker_is_told_to_trim(@TempDir Path dir) throws Exception {
        String heap = "{\"t\":\"heap\",\"used_after_gc\":104857600,\"committed\":314572800,\"max\":2147483648,"
                + "\"gc_frac\":0.0125,\"metaspace\":52428800,\"inflight\":3}";
        CompilerWorker.HeapReading reading = CompilerWorker.HeapReading.parse(heap);
        assertThat(reading)
                .isEqualTo(new CompilerWorker.HeapReading(100L << 20, 300L << 20, 2L << 30, 0.0125, 50L << 20, 3));

        Fakes fakes = new Fakes(1);
        CompilerPool pool = pool(dir, fakes);
        CompileWork w = items(dir, 1, job()).getFirst();
        pool.enqueue(w);
        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().size() == 1, "in flight");

        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 8L << 30, () -> 4, id -> false);
        worker.worker().leased(ledger.acquireBytes(CompilerWorker.FIRST_LEASE_BYTES, true, null));
        worker.frame(heap);
        assertThat(worker.worker().heap()).isEqualTo(reading);
        assertThat(ledger.snapshot().leasedBytes())
                .as("the worker is leased what it committed, not what it may grow to")
                .isEqualTo(WorkerLeases.jvmLease(300L << 20));

        worker.frame(result(w.id, "OK"));
        awaitTrue(() -> worker.sentOps().contains(PluginProtocol.OP_TRIM), "the drained worker is told to trim");
        worker.frame("{\"t\":\"trimmed\",\"used_after_gc\":73400320}");
        assertThat(worker.worker().trimmedTo()).isEqualTo(70L << 20);
        pool.close();
    }

    @Test
    void a_worker_that_never_says_hello_fails_the_queue_instead_of_restarting(@TempDir Path dir) throws Exception {
        Fakes fakes = new Fakes(0);
        CompilerPool pool = pool(dir, fakes);
        List<CompileWork> items = items(dir, 2, job());
        for (CompileWork w : items) pool.enqueue(w);
        fakes.await(0).die("zinc worker exited with status 1");
        for (CompileWork w : items) {
            awaitTrue(w.compile::isDone, "the queue fails with the death");
            assertThat(w.compile).isCompletedExceptionally();
        }
        assertThat(fakes.started).hasSize(1);
        pool.close();
    }

    @Test
    void a_spec_that_cannot_be_written_fails_its_item_and_the_worker_takes_the_next(@TempDir Path dir)
            throws Exception {
        Fakes fakes = new Fakes(1);
        CompilerPool pool = new CompilerPool("test", null, fakes, req -> {
            if (req.classOutput().toString().contains("bad")) throw new IllegalStateException("jar not on disk");
            return Files.createTempFile(dir, "spec", ".spec");
        });
        CompileWork bad = item(dir, "bad", job());
        CompileWork good = item(dir, "good", job());
        pool.enqueue(bad);
        pool.enqueue(good);
        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().contains(good.id), "the next item goes out");
        assertThatThrownBy(bad.compile::join).cause().hasMessageContaining("jar not on disk");
        worker.frame(result(good.id, "OK"));
        assertThat(good.compile.get(10, TimeUnit.SECONDS).success()).isTrue();
        pool.close();
    }

    @Test
    void close_tells_the_worker_to_finish_and_fails_what_never_ran(@TempDir Path dir) throws Exception {
        Fakes fakes = new Fakes(1);
        CompilerPool pool = pool(dir, fakes);
        List<CompileWork> items = items(dir, 2, job());
        for (CompileWork w : items) pool.enqueue(w);
        Fake worker = fakes.await(0);
        awaitTrue(() -> worker.dispatched().size() == 1, "one in flight");
        worker.exitOnDone = true;

        pool.close();
        assertThat(worker.sentOps()).contains(PluginProtocol.OP_DONE);
        for (CompileWork w : items) assertThat(w.compile).isCompletedExceptionally();
        CompileWork late = item(dir, "late", job());
        pool.enqueue(late);
        assertThat(late.compile).isCompletedExceptionally();
    }

    // ---- fakes --------------------------------------------------------------------------------

    /** Starts {@link Fake} workers that answer hello with {@code threads}, or never when it is 0. */
    private static final class Fakes implements CompilerPool.Starter {
        final int threads;
        final boolean connects;
        final List<Fake> started = new CopyOnWriteArrayList<>();

        Fakes(int threads) {
            this(threads, true);
        }

        Fakes(int threads, boolean connects) {
            this.threads = threads;
            this.connects = connects;
        }

        @Override
        public CompilerWorker.Launcher launcher() {
            Fake fake = new Fake(threads, connects);
            started.add(fake);
            return fake;
        }

        Fake await(int index) throws InterruptedException {
            awaitTrue(() -> started.size() > index && started.get(index).worker != null, "worker " + index + " starts");
            return started.get(index);
        }
    }

    /** A worker the test speaks for: it records what the engine sends and dies when told to. */
    private static final class Fake implements CompilerWorker.Launcher {
        private final int threads;
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch exit = new CountDownLatch(1);
        private volatile @Nullable CompilerWorker worker;
        private volatile @Nullable IOException death;
        private final boolean connects;
        volatile boolean exitOnDone;
        volatile boolean killed;

        Fake(int threads, boolean connects) {
            this.threads = threads;
            this.connects = connects;
        }

        @Override
        public void run(CompilerWorker w) throws Exception {
            w.killWith(() -> {
                killed = true;
                die("zinc worker killed");
            });
            w.attach(new PluginProcess.Conversation() {
                @Override
                public boolean offer(String line) {
                    if (!connects) return false;
                    send(line);
                    return true;
                }

                @Override
                public void send(String line) {
                    if (!connects) throw new IllegalStateException("the worker never connected");
                    sent.add(line);
                    String op = Jsonl.str(line, PluginProtocol.OP);
                    if (PluginProtocol.HELLO.equals(op) && threads > 0) {
                        w.onFrame("{\"t\":\"hello\",\"threads\":" + threads + ",\"pid\":4242}");
                    }
                    if (PluginProtocol.OP_DONE.equals(op) && exitOnDone) exit.countDown();
                }

                @Override
                public void closeInput() {}
            });
            worker = w;
            exit.await();
            IOException e = death;
            if (e != null) throw e;
        }

        CompilerWorker worker() {
            CompilerWorker w = worker;
            if (w == null) throw new AssertionError("not started");
            return w;
        }

        void frame(String json) {
            worker().onFrame(json);
        }

        void print(String line) {
            worker().output(line);
        }

        void die(String message) {
            death = new IOException(message);
            exit.countDown();
        }

        /** The ids of every compile sent, in order. */
        List<Long> dispatched() {
            return sent.stream()
                    .filter(l -> PluginProtocol.OP_COMPILE.equals(Jsonl.str(l, PluginProtocol.OP)))
                    .map(l -> Jsonl.longValue(l, PluginProtocol.ID, -1))
                    .toList();
        }

        /** Each command sent as {@code op} or {@code op id}. */
        List<String> sentOps() {
            return sent.stream()
                    .map(l -> {
                        String op = Jsonl.str(l, PluginProtocol.OP);
                        long id = Jsonl.longValue(l, PluginProtocol.ID, -1);
                        return id < 0 ? op : op + " " + id;
                    })
                    .toList();
        }
    }

    /**
     * Job ids no other test in this JVM has ended: a death puts an item back only while its job is
     * alive, and {@link cc.jumpkick.engine.plugin.JobWorkers#ended} remembers every job ever ended.
     */
    private static final AtomicLong JOBS = new AtomicLong(7_718_000L);

    private static long job() {
        return JOBS.incrementAndGet();
    }

    private static CompilerPool pool(Path dir, Fakes fakes) {
        return new CompilerPool(
                "test", null, fakes, req -> Files.createTempFile(dir, "spec", ".spec"), Duration.ofMillis(50));
    }

    private static List<CompileWork> items(Path dir, int n, long job) {
        List<CompileWork> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(item(dir, "m" + job + "-" + i, job));
        return out;
    }

    private static CompileWork item(Path dir, String name, long job) {
        Path root = dir.resolve(name);
        ForkedJavac.Request req = new ForkedJavac.Request(
                null,
                root.resolve("worker.jar"),
                List.of(root.resolve("C.java")),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of());
        return CompileWork.compile(req).forJob(job, SessionContext.current());
    }

    private static CompileWork byId(List<CompileWork> items, long id) {
        return items.stream().filter(w -> w.id == id).findFirst().orElseThrow();
    }

    private static String diagnostic(long id, String file, String msg) {
        return "{\"id\":" + id + ",\"t\":\"diagnostic\",\"sev\":\"ERROR\",\"file\":\"" + file
                + "\",\"line\":3,\"col\":5,\"msg\":\"" + msg + "\"}";
    }

    private static String result(long id, String status) {
        return "{\"id\":" + id + ",\"t\":\"result\",\"status\":\"" + status + "\",\"compiled\":[]}";
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(5);
        }
    }
}
