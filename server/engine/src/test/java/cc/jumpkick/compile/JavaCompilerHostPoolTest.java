// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.compile.JavaCompilerHost.Session;
import cc.jumpkick.compile.JavaCompilerHost.Work;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
