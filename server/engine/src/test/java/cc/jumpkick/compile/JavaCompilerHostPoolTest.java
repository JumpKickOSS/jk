// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.compile.JavaCompilerHost.Session;
import cc.jumpkick.compile.JavaCompilerHost.SpecFile;
import cc.jumpkick.compile.JavaCompilerHost.Work;
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
        Work w = Work.compile(request(dir, "a"));
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
    void the_last_lane_dying_with_the_pool_open_fails_the_queue(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Lanes pool = new Lanes(
                1, (owner, index) -> new Session(owner, 2L, index, self -> release.await()), ForkedJavac::writeSpec);
        Work w = Work.compile(request(dir, "a"));
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
                    for (Work w = self.takeNext(); w != Work.POISON; w = self.takeNext()) {
                        w.compile.complete(ok());
                    }
                }),
                ForkedJavac::writeSpec);
        Work a = Work.compile(request(dir, "a"));
        Work b = Work.compile(request(dir, "b"));
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
        pool.enqueue(Work.compile(request(dir, "a")));
        Thread closer = Thread.ofVirtual().start(pool::close);
        awaitTrue(pool::closing, "close() marks the pool closed");

        Work late = Work.compile(request(dir, "late"));
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
        Work bad = Work.compile(request(dir, "bad"));
        Work good = Work.compile(request(dir, "good"));
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
        pool.enqueue(Work.compile(request(dir, "a")));
        awaitTrue(
                () -> pool.queued() == 0 && requireNonNull(first.get()).working(),
                "the first lane takes the item and is busy");

        pool.enqueue(Work.compile(request(dir, "b")));
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
        pool.enqueue(Work.compile(request(dir, "a")));
        awaitTrue(() -> pool.queued() == 0, "the first lane's body took the item");

        List<String> sent = new ArrayList<>();
        Thread pump = Thread.ofVirtual().start(() -> requireNonNull(first.get())
                .onLine("{\"" + PluginProtocol.T + "\":\"" + PluginProtocol.READY + "\"}", recording(sent)));
        awaitTrue(
                () -> pump.getState() == Thread.State.WAITING || pump.getState() == Thread.State.TIMED_WAITING,
                "the pump is parked on the empty queue");

        firstDies.countDown();
        awaitTrue(() -> pool.liveLanes() == 0, "the first lane is gone");

        Work b = Work.compile(request(dir, "b"));
        pool.enqueue(b);
        pump.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(pump.isAlive()).as("the dead lane's pump unwinds").isFalse();
        assertThat(sent).as("the dead worker is told DONE, never COMPILE").containsExactly("DONE", "<eof>");
        assertThat(pool.queued()).as("the item waits for a lane that is alive").isEqualTo(1);
        assertThat(pool.liveLanes()).isEqualTo(1);
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
        Work a = Work.compile(request(dir, "a"));
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
