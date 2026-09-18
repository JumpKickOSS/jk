// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.compile.JavaCompilerHost.Session;
import cc.jumpkick.compile.JavaCompilerHost.SpecFile;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pool bookkeeping with lanes that run an in-process body instead of a worker JVM: what happens to
 * queued work when lanes die, when the pool closes, and in which order the two happen.
 */
class JavaCompilerHostPoolTest {

    @Test
    void work_queued_when_the_last_lane_dies_after_close_is_failed_not_orphaned(@TempDir Path dir) throws Exception {
        // Job teardown kills the workers first and calls end() second, so a lane's death is usually
        // observed with the pool already closed. The item it never took must still be failed, or
        // its caller blocks in compile.get() for the engine's lifetime.
        CountDownLatch release = new CountDownLatch(1);
        Lanes pool = new Lanes(
                1, (owner, index) -> new Session(owner, 1L, index, self -> release.await()), ForkedJavac::writeSpec);
        CompileWork w = CompileWork.compile(request(dir, "a"));
        pool.enqueue(w);
        assertThat(pool.liveLanes()).isEqualTo(1);
        assertThat(pool.queued())
                .as("the lane never reached READY, so the item is still queued")
                .isEqualTo(1);

        Thread closer = Thread.ofVirtual().start(pool::close);
        awaitTrue(pool::closing, "close() marks the pool closed");
        release.countDown(); // the lane dies now, after the close
        closer.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(closer.isAlive()).as("close() returns once the lane is gone").isFalse();
        assertThat(w.compile).isCompletedExceptionally();
        assertThat(w.forecast).isCompletedExceptionally();
    }

    @Test
    void a_lane_runs_under_the_session_of_the_request_that_grew_it(@TempDir Path dir) throws Exception {
        // A lane forks and drives its worker JVM from its own thread; the worker's flags and its
        // registration for cancel come off the session the submitting request had bound.
        var marked = SessionContext.current().withRequestedTestWorkers(23);
        AtomicReference<Integer> seen = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> new Session(owner, 6L, index, self -> {
                    seen.set(SessionContext.current().requestedTestWorkers());
                    for (CompileWork w = self.takeNext(); w != CompileWork.POISON; w = self.takeNext()) {
                        w.compile.complete(ok());
                    }
                }),
                ForkedJavac::writeSpec);
        CompileWork w = CompileWork.compile(request(dir, "a"));
        SessionContext.runWhere(marked, () -> pool.enqueue(w));
        pool.close();
        assertThat(w.compile).isCompletedWithValueMatching(ForkedJavac.Result::success);
        assertThat(seen.get()).isEqualTo(23);
    }

    @Test
    void the_last_lane_dying_with_the_pool_open_fails_the_queue(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Lanes pool = new Lanes(
                1, (owner, index) -> new Session(owner, 2L, index, self -> release.await()), ForkedJavac::writeSpec);
        CompileWork w = CompileWork.compile(request(dir, "a"));
        pool.enqueue(w);
        release.countDown();
        awaitTrue(w.compile::isDone, "the orphaned item is failed by the lane's death");
        assertThat(w.compile).isCompletedExceptionally();
        assertThat(pool.liveLanes()).isZero();
    }

    @Test
    void close_lets_a_live_lane_finish_what_was_queued_ahead_of_the_poison(@TempDir Path dir) throws Exception {
        Lanes pool = new Lanes(
                1,
                (owner, index) -> new Session(owner, 3L, index, self -> {
                    for (CompileWork w = self.takeNext(); w != CompileWork.POISON; w = self.takeNext()) {
                        w.compile.complete(ok());
                    }
                }),
                ForkedJavac::writeSpec);
        CompileWork a = CompileWork.compile(request(dir, "a"));
        CompileWork b = CompileWork.compile(request(dir, "b"));
        pool.enqueue(a);
        pool.enqueue(b);
        pool.close();
        assertThat(a.compile).isCompletedWithValueMatching(ForkedJavac.Result::success);
        assertThat(b.compile).isCompletedWithValueMatching(ForkedJavac.Result::success);
        assertThat(pool.liveLanes()).isZero();
    }

    @Test
    void work_enqueued_after_close_fails_at_once_instead_of_waiting_behind_the_poison(@TempDir Path dir)
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Lanes pool = new Lanes(
                1, (owner, index) -> new Session(owner, 4L, index, self -> release.await()), ForkedJavac::writeSpec);
        pool.enqueue(CompileWork.compile(request(dir, "a")));
        Thread closer = Thread.ofVirtual().start(pool::close);
        awaitTrue(pool::closing, "close() marks the pool closed");

        CompileWork late = CompileWork.compile(request(dir, "late"));
        pool.enqueue(late);
        assertThat(late.compile).isCompletedExceptionally();

        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(closer.isAlive()).isFalse();
    }

    @Test
    void a_spec_that_cannot_be_written_fails_its_item_and_the_lane_dispatches_the_next(@TempDir Path dir)
            throws Exception {
        // The worker has said READY and is waiting for a command. If the host fails to write the
        // spec and then sends nothing, worker and lane wait on each other for the rest of the job.
        Path badRoot = dir.resolve("bad");
        SpecFile specs = req -> {
            if (req.classOutput().startsWith(badRoot)) throw new IOException("no room for a spec");
            return Files.writeString(
                    dir.resolve(requireNonNull(req.classOutput().getParent()).getFileName() + ".spec"), "spec");
        };
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Session> lane = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    Session s = new Session(owner, 5L, index, self -> release.await());
                    lane.set(s);
                    return s;
                },
                specs);
        CompileWork bad = CompileWork.compile(request(dir, "bad"));
        CompileWork good = CompileWork.compile(request(dir, "good"));
        pool.enqueue(bad);
        pool.enqueue(good);

        List<String> sent = new ArrayList<>();
        lane.get().onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent));

        assertThat(bad.compile).isCompletedExceptionally();
        assertThat(sent)
                .as("the worker got a command for the item behind the one that failed")
                .hasSize(1);
        assertThat(sent.getFirst()).startsWith("COMPILE ").endsWith("good.spec");
        assertThat(good.compile).isNotDone();
        assertThat(lane.get().working()).isTrue();

        release.countDown();
        awaitTrue(good.compile::isDone, "the lane's death fails the item it had on the wire");
    }

    @Test
    void a_lane_that_has_taken_an_item_but_not_yet_dispatched_it_is_not_free(@TempDir Path dir) throws Exception {
        // Between take() and the COMPILE line sits PluginSlots.acquire(), which blocks for as long
        // as a permit takes. A lane parked there holds an item; growth must not count it as
        // capacity, or the next module queues behind a wait a fresh lane would have skipped.
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<@Nullable Session> first = new AtomicReference<>();
        Lanes pool = new Lanes(
                2,
                (owner, index) -> {
                    Session s = new Session(owner, 6L, index, self -> {
                        self.takeNext();
                        release.await();
                    });
                    first.compareAndSet(null, s);
                    return s;
                },
                ForkedJavac::writeSpec);
        pool.enqueue(CompileWork.compile(request(dir, "a")));
        awaitTrue(
                () -> pool.queued() == 0 && requireNonNull(first.get()).working(),
                "the first lane takes the item and is busy");

        pool.enqueue(CompileWork.compile(request(dir, "b")));
        assertThat(pool.liveLanes())
                .as("a lane holding an undispatched item is busy, so the second item opens a second lane")
                .isEqualTo(2);
        release.countDown();
    }

    @Test
    void a_lane_parked_on_the_queue_when_its_worker_dies_hands_back_what_it_takes(@TempDir Path dir) throws Exception {
        // The worker's pump thread is the one blocked in takeNext(), and it does not learn of the
        // worker's death by itself: the io thread does. An item the dead lane takes afterwards must
        // go back to the pool, or its caller waits on a worker that no longer exists.
        CountDownLatch firstDies = new CountDownLatch(1);
        CountDownLatch secondDies = new CountDownLatch(1);
        AtomicReference<@Nullable Session> first = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    if (first.get() == null) {
                        Session s = new Session(owner, 7L, index, self -> {
                            self.takeNext();
                            firstDies.await();
                        });
                        first.set(s);
                        return s;
                    }
                    return new Session(owner, 7L, index, self -> secondDies.await());
                },
                ForkedJavac::writeSpec);
        pool.enqueue(CompileWork.compile(request(dir, "a")));
        awaitTrue(() -> pool.queued() == 0, "the first lane's body took the item");

        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(first.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(
                () -> pump.getState() == Thread.State.WAITING || pump.getState() == Thread.State.TIMED_WAITING,
                "the pump is parked on the empty queue");

        firstDies.countDown();
        awaitTrue(() -> pool.liveLanes() == 0, "the first lane is gone");

        CompileWork b = CompileWork.compile(request(dir, "b"));
        pool.enqueue(b);
        pump.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(pump.isAlive()).as("the dead lane's pump unwinds").isFalse();
        assertThat(sent).as("the dead worker is told DONE, never COMPILE").containsExactly("DONE", "<eof>");
        // Handing the item back and spawning the replacement lane both happen off this thread, so
        // they are awaited rather than read the instant the pump unwinds: the item was observed
        // taken-but-not-yet-returned, which is a moment in the hand-back, not a lost item.
        awaitTrue(() -> pool.queued() == 1, "the item waits for a lane that is alive");
        awaitTrue(() -> pool.liveLanes() == 1, "a live lane replaces the dead one");
        assertThat(b.compile).isNotDone();

        secondDies.countDown();
        awaitTrue(b.compile::isDone, "the last lane's death fails the item that was handed back");
        assertThat(b.compile).isCompletedExceptionally();
    }

    @Test
    void a_lane_whose_worker_dies_between_take_and_dispatch_hands_the_item_back(@TempDir Path dir) throws Exception {
        // Between takeNext() and the COMPILE line the pump waits for a slot and writes the spec. A
        // worker that dies in that window has no in-flight item for failAll to fail, so the pump has
        // to notice on its own and give the item back rather than send it down a closed pipe.
        CountDownLatch specGate = new CountDownLatch(1);
        CountDownLatch firstDies = new CountDownLatch(1);
        CountDownLatch secondDies = new CountDownLatch(1);
        SpecFile specs = req -> {
            try {
                specGate.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return Files.writeString(dir.resolve("a.spec"), "spec");
        };
        AtomicReference<@Nullable Session> first = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    if (first.get() == null) {
                        Session s = new Session(owner, 8L, index, self -> firstDies.await());
                        first.set(s);
                        return s;
                    }
                    return new Session(owner, 8L, index, self -> secondDies.await());
                },
                specs);
        CompileWork a = CompileWork.compile(request(dir, "a"));
        pool.enqueue(a);

        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(first.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(
                () -> pool.queued() == 0 && requireNonNull(first.get()).working(),
                "the pump took the item and is in the spec write");

        firstDies.countDown();
        awaitTrue(() -> pool.liveLanes() == 0, "the first lane is gone while the pump still holds the item");
        specGate.countDown();
        pump.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(pump.isAlive()).isFalse();
        assertThat(sent).as("the dead worker is told DONE, never COMPILE").containsExactly("DONE", "<eof>");
        assertThat(pool.queued()).as("the item went back to the pool").isEqualTo(1);
        assertThat(pool.liveLanes())
                .as("handing it back opened a lane to take it")
                .isEqualTo(1);
        assertThat(a.compile).isNotDone();

        secondDies.countDown();
        awaitTrue(a.compile::isDone, "the last lane's death fails the item that was handed back");
        assertThat(a.compile).isCompletedExceptionally();
    }

    @Test
    void a_worker_that_runs_out_of_heap_is_answered_with_one_retry_on_twice_the_heap(@TempDir Path dir)
            throws Exception {
        // The lane's worker dies with the JVM's out-of-memory banner in its output. The item is not
        // failed: the pool hands it to a worker started with twice the heap, once.
        CountDownLatch dies = new CountDownLatch(1);
        AtomicReference<@Nullable Session> lane = new AtomicReference<>();
        AtomicReference<@Nullable CompileWork> retried = new AtomicReference<>();
        AtomicLong retryHeap = new AtomicLong();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    Session s = new Session(owner, 8L, index, self -> {
                        dies.await();
                        self.output("Terminating due to java.lang.OutOfMemoryError: GC overhead limit exceeded");
                        throw new IOException("zinc worker exited with status 3");
                    });
                    lane.set(s);
                    return s;
                },
                ForkedJavac::writeSpec,
                (failed, heap) -> {
                    retried.set(failed);
                    retryHeap.set(heap);
                });
        long heap = 512L << 20;
        CompileWork w = CompileWork.compile(request(dir, "a").withLabel("g:app compile-test"), heap);
        pool.enqueue(w);
        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(lane.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(() -> sent.stream().anyMatch(l -> l.startsWith("COMPILE ")), "the item is on the wire");
        pump.join(TimeUnit.SECONDS.toMillis(10));

        dies.countDown();
        awaitTrue(() -> retried.get() != null, "the pool is asked to retry the item");

        assertThat(retried.get()).isSameAs(w);
        assertThat(retryHeap.get()).as("twice the heap the worker had").isEqualTo(2 * heap);
        assertThat(w.compile)
                .as("the retry's outcome is the caller's; nothing is decided yet")
                .isNotDone();
    }

    @Test
    void a_second_exhaustion_fails_the_item_naming_the_module_and_both_heaps(@TempDir Path dir) throws Exception {
        CountDownLatch dies = new CountDownLatch(1);
        AtomicReference<@Nullable Session> lane = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    Session s = new Session(owner, 9L, index, self -> {
                        dies.await();
                        self.output("Terminating due to java.lang.OutOfMemoryError: Java heap space");
                        throw new IOException("zinc worker exited with status 3");
                    });
                    lane.set(s);
                    return s;
                },
                ForkedJavac::writeSpec,
                (failed, heap) -> {
                    throw new AssertionError("a retried item is not retried again");
                });
        CompileWork retry = CompileWork.compile(request(dir, "a").withLabel("g:app compile-test"), 2048L << 20);
        retry.previousHeapBytes = 1024L << 20;
        pool.enqueue(retry);
        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(lane.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(() -> sent.stream().anyMatch(l -> l.startsWith("COMPILE ")), "the item is on the wire");
        pump.join(TimeUnit.SECONDS.toMillis(10));

        dies.countDown();
        awaitTrue(retry.compile::isDone, "the second exhaustion is the failure");

        assertThat(retry.compile).isCompletedExceptionally();
        assertThatThrownBy(retry.compile::join)
                .cause()
                .isInstanceOf(IOException.class)
                .hasMessageContaining("g:app compile-test")
                .hasMessageContaining("1024 MiB")
                .hasMessageContaining("2048 MiB")
                .hasMessageContaining("OutOfMemoryError");
    }

    @Test
    void a_worker_that_dies_without_an_out_of_memory_banner_fails_its_item_at_once(@TempDir Path dir) throws Exception {
        CountDownLatch dies = new CountDownLatch(1);
        AtomicReference<@Nullable Session> lane = new AtomicReference<>();
        Lanes pool = new Lanes(
                1,
                (owner, index) -> {
                    Session s = new Session(owner, 10L, index, self -> {
                        dies.await();
                        self.output("Exception in thread main: java.lang.IllegalStateException: boom");
                        throw new IOException("zinc worker exited with status 1");
                    });
                    lane.set(s);
                    return s;
                },
                ForkedJavac::writeSpec,
                (failed, heap) -> {
                    throw new AssertionError("only an out-of-memory death is retried");
                });
        CompileWork w = CompileWork.compile(request(dir, "a"), 512L << 20);
        pool.enqueue(w);
        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(lane.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(() -> sent.stream().anyMatch(l -> l.startsWith("COMPILE ")), "the item is on the wire");
        pump.join(TimeUnit.SECONDS.toMillis(10));

        dies.countDown();
        awaitTrue(w.compile::isDone, "the death fails the item");
        assertThatThrownBy(w.compile::join)
                .cause()
                .hasMessageContaining("status 1")
                .hasMessageContaining("boom");
    }

    private static PluginProcess.Conversation recording(List<String> sent) {
        return new PluginProcess.Conversation() {
            @Override
            public void send(String line) {
                sent.add(line);
            }

            @Override
            public void closeInput() {
                sent.add("<eof>");
            }
        };
    }

    private static ForkedJavac.Result ok() {
        return new ForkedJavac.Result(true, List.of(), Map.of(), List.of(), 0L);
    }

    private static ForkedJavac.Request request(Path dir, String name) {
        Path root = dir.resolve(name);
        return new ForkedJavac.Request(
                null,
                root.resolve("worker.jar"),
                List.of(root.resolve("C.java")),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of());
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(5);
        }
    }
}
