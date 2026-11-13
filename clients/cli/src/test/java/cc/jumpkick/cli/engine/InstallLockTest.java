// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cli.engine.InstallLock.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host install lock across processes: each test forks a JVM standing in for another session
 * sharing the install, so the OS lock, not this JVM's bookkeeping, is what is under test.
 */
class InstallLockTest {

    @TempDir
    Path dir;

    private final List<String> notices = new CopyOnWriteArrayList<>();

    private Path lock() {
        return dir.resolve("engine").resolve("install.lock");
    }

    @AfterEach
    void restoreNotices() {
        InstallLock.notices(line -> {});
    }

    @Test
    void an_install_waits_for_another_sessions_run_and_names_it() throws Exception {
        InstallLock.notices(notices::add);
        try (var run = InstallLockHolderMain.start(lock(), "shared", "jk test")) {
            run.awaitHeld();
            OnThread install = new OnThread(() -> InstallLock.exclusive(lock(), "jk snapshot"));
            awaitWaiting(Mode.WAITING_EXCLUSIVE);
            assertThat(install.held()).isFalse();
            assertThat(notices)
                    .singleElement()
                    .asString()
                    .contains("jk snapshot waits", "pid " + run.process().pid(), "jk test (shared)");
            assertThat(InstallLock.holders(lock()))
                    .extracting(InstallLock.Holder::pid, InstallLock.Holder::mode)
                    .contains(
                            tuple(run.process().pid(), Mode.SHARED),
                            tuple(ProcessHandle.current().pid(), Mode.WAITING_EXCLUSIVE));

            run.release();
            install.awaitHeld();
            assertThat(InstallLock.holders(lock()))
                    .extracting(InstallLock.Holder::mode)
                    .containsExactly(Mode.EXCLUSIVE);
            install.close();
        }
        assertThat(InstallLock.holders(lock())).isEmpty();
    }

    @Test
    void a_run_waits_out_an_install_in_progress() throws Exception {
        InstallLock.notices(notices::add);
        try (var install = InstallLockHolderMain.start(lock(), "exclusive", "jk snapshot")) {
            install.awaitHeld();
            OnThread run = new OnThread(() -> InstallLock.shared(lock(), "jk test"));
            awaitWaiting(Mode.WAITING_SHARED);
            assertThat(run.held()).isFalse();
            assertThat(notices).singleElement().asString().contains("jk test waits", "jk snapshot (exclusive)");
            install.release();
            run.awaitHeld();
            run.close();
        }
    }

    @Test
    void runs_in_two_sessions_share_the_lock() throws Exception {
        InstallLock.notices(notices::add);
        try (var other = InstallLockHolderMain.start(lock(), "shared", "jk guard")) {
            other.awaitHeld();
            OnThread mine = new OnThread(() -> InstallLock.shared(lock(), "jk test"));
            mine.awaitHeld();
            mine.close();
            assertThat(notices).isEmpty();
        }
    }

    @Test
    void a_dead_holder_is_broken_and_its_record_pruned() throws Exception {
        try (var install = InstallLockHolderMain.start(lock(), "exclusive", "jk snapshot")) {
            install.awaitHeld();
            install.process().destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            OnThread run = new OnThread(() -> InstallLock.shared(lock(), "jk test"));
            run.awaitHeld();
            assertThat(InstallLock.holders(lock()))
                    .extracting(InstallLock.Holder::pid)
                    .containsExactly(ProcessHandle.current().pid());
            run.close();
        }
        try (var records = Files.list(lock().resolveSibling("install.holders"))) {
            assertThat(records).isEmpty();
        }
    }

    @Test
    void a_record_left_by_a_gone_process_is_not_a_holder() throws Exception {
        Path records = lock().resolveSibling("install.holders");
        Files.createDirectories(records);
        Process gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Path stale = records.resolve(gone.pid() + "-1.hold");
        Files.writeString(
                stale,
                "pid=" + gone.pid() + "\nmode=EXCLUSIVE\npurpose=jk snapshot\ncwd=/x\nsince=" + Instant.now() + "\n");
        assertThat(InstallLock.holders(lock())).isEmpty();
        assertThat(stale).doesNotExist();
    }

    @Test
    void an_exclusive_hold_inside_a_shared_run_sets_the_run_aside_and_resumes_it() throws Exception {
        try (InstallLock.Hold run = InstallLock.shared(lock(), "jk snapshot")) {
            try (InstallLock.Hold install = InstallLock.exclusive(lock(), "jk snapshot (installing jk)")) {
                try (var other = InstallLockHolderMain.start(lock(), "shared", "jk test")) {
                    // Another session's run cannot start while this one installs.
                    assertThatHolderWaits(other);
                }
            }
            // Back to shared: another session's run starts alongside.
            try (var other = InstallLockHolderMain.start(lock(), "shared", "jk test")) {
                other.awaitHeld();
                other.release();
            }
        }
    }

    private static void assertThatHolderWaits(InstallLockHolderMain.Holder other) throws Exception {
        assertThat(other.nextLine(Duration.ofSeconds(60))).startsWith("WAIT jk test waits");
        assertThat(other.printsWithin(Duration.ofSeconds(1)))
                .as("holder went on while the lock was exclusive")
                .isFalse();
    }

    private void awaitWaiting(Mode mode) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (InstallLock.holders(lock()).stream().noneMatch(h -> h.mode() == mode)) {
            if (System.nanoTime() > deadline) throw new TimeoutException("no " + mode + " record");
            Thread.sleep(20);
        }
    }

    /** A hold taken and released on one thread of its own, as a command takes and releases it. */
    private static final class OnThread implements AutoCloseable {
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<InstallLock.Hold> hold;

        OnThread(Callable<InstallLock.Hold> take) {
            hold = thread.submit(take);
        }

        boolean held() {
            return hold.isDone();
        }

        void awaitHeld() throws Exception {
            hold.get(60, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            thread.submit(() -> {
                        hold.get().close();
                        return null;
                    })
                    .get(60, TimeUnit.SECONDS);
            thread.shutdown();
        }
    }
}
