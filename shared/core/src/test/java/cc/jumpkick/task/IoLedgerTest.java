// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IoLedgerTest {

    @TempDir
    Path dir;

    @AfterEach
    void dropAmbient() {
        IoLedger.close();
    }

    @Test
    void starts_empty() {
        assertThat(new IoLedger().totals()).isEqualTo(IoLedger.Totals.ZERO);
        assertThat(new IoLedger().totals().isEmpty()).isTrue();
    }

    @Test
    void accumulates_each_direction_independently() {
        IoLedger io = new IoLedger();
        io.remoteDown(1_000);
        io.remoteDown(500);
        io.remoteUp(7);
        io.localUp(20);
        io.localDown(30);

        assertThat(io.totals()).isEqualTo(new IoLedger.Totals(7, 1_500, 20, 30));
        assertThat(io.totals().isEmpty()).isFalse();
    }

    @Test
    void ignores_zero_and_negative_deltas() {
        IoLedger io = new IoLedger();
        io.remoteDown(0);
        io.remoteUp(-5);
        io.localUp(-1);
        io.localDown(0);

        assertThat(io.totals()).isEqualTo(IoLedger.Totals.ZERO);
    }

    @Test
    void meters_a_file_by_its_size_at_rest() throws IOException {
        Path blob = Files.writeString(dir.resolve("blob.jar"), "0123456789", StandardCharsets.UTF_8);
        IoLedger io = new IoLedger();
        io.remoteDown(blob);
        io.localDown(blob);

        assertThat(io.totals().remoteDown()).isEqualTo(10);
        assertThat(io.totals().localDown()).isEqualTo(10);
    }

    @Test
    void an_unstattable_file_contributes_nothing() {
        IoLedger io = new IoLedger();
        io.remoteDown(dir.resolve("vanished.jar"));
        io.localDown((Path) null);

        assertThat(io.totals()).isEqualTo(IoLedger.Totals.ZERO);
        assertThat(IoLedger.sizeOf(dir.resolve("nope"))).isZero();
        assertThat(IoLedger.sizeOf(null)).isZero();
    }

    @Test
    void ambient_is_absent_until_opened_and_gone_after_close() {
        IoLedger first = IoLedger.currentOrNew();
        assertThat(IoLedger.currentOrNew()).isNotSameAs(first); // detached: a fresh one each time

        IoLedger run = new IoLedger();
        IoLedger.open(run);
        assertThat(IoLedger.currentOrNew()).isSameAs(run);
        assertThat(IoLedger.currentOrNew()).isSameAs(run);

        IoLedger.close();
        assertThat(IoLedger.currentOrNew()).isNotSameAs(run);
    }

    @Test
    void ambient_reaches_threads_the_run_forks() throws InterruptedException {
        IoLedger run = new IoLedger();
        IoLedger.open(run);
        Thread forked = new Thread(() -> IoLedger.currentOrNew().remoteDown(64));
        forked.start();
        forked.join();

        assertThat(run.totals().remoteDown()).isEqualTo(64);
    }

    @Test
    void concurrent_writers_do_not_lose_bytes() throws InterruptedException {
        IoLedger io = new IoLedger();
        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                io.localUp(3);
                                io.remoteDown(1);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }
        start.countDown();
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(io.totals().localUp()).isEqualTo(3L * threads * perThread);
        assertThat(io.totals().remoteDown()).isEqualTo((long) threads * perThread);
    }
}
