// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * An install waiting for the lock is not starved by sessions that run builds back to back: two
 * processes take the lock shared in overlapping loops, so at every instant at least one holds it,
 * and an exclusive acquirer must still get it within a few holds rather than when the loops stop.
 */
class InstallLockStarvationTest {

    private static final long HOLD_MS = 300;
    private static final long LOOP_MS = 12_000;

    @TempDir
    Path dir;

    @Test
    @Timeout(90)
    void an_install_gets_the_lock_while_two_sessions_loop_shared_holds() throws Exception {
        Path lock = dir.resolve("engine").resolve("install.lock");
        InstallLock.notices(line -> {});
        Process a = InstallLockLooperMain.start(lock, HOLD_MS, LOOP_MS);
        Process b = null;
        try {
            BufferedReader aOut = reader(a);
            assertThat(aOut.readLine()).isEqualTo("LOOPING");
            Thread.sleep(HOLD_MS / 2);
            b = InstallLockLooperMain.start(lock, HOLD_MS, LOOP_MS);
            BufferedReader bOut = reader(b);
            assertThat(bOut.readLine()).isEqualTo("LOOPING");
            Thread.sleep(2 * HOLD_MS);

            long start = System.nanoTime();
            InstallLock.Hold held = InstallLock.exclusive(lock, "jk install (installing jk)");
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            boolean loopsStillRunning = a.isAlive() || b.isAlive();
            held.close();
            System.out.println("exclusive waited " + waited.toMillis() + " ms; loopers still running when it got"
                    + " the lock: " + loopsStillRunning);
            a.waitFor(LOOP_MS + 30_000, TimeUnit.MILLISECONDS);
            b.waitFor(LOOP_MS + 30_000, TimeUnit.MILLISECONDS);
            System.out.println("looper A " + aOut.readLine() + ", looper B " + bOut.readLine());
            assertThat(waited)
                    .as("exclusive acquire under continuous overlapping shared holds")
                    .isLessThan(Duration.ofMillis(10 * HOLD_MS));
        } finally {
            a.destroyForcibly();
            if (b != null) b.destroyForcibly();
            InstallLock.notices(line -> {});
        }
    }

    private static BufferedReader reader(Process p) {
        return new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
    }
}
