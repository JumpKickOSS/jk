// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import com.sun.management.ThreadMXBean;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A client waiting for its engine to start stops when its thread is interrupted (Ctrl-C, a test's
 * timeout). The interrupt stays set, so every later sleep returns at once and every channel the
 * thread opens fails at once; a wait that swallowed it polled the endpoint flat out until its
 * ceiling, burning a core and allocating hundreds of megabytes a second beside everything else in
 * the process.
 */
class EngineSpawnInterruptTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jki-");

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void an_interrupted_wait_for_the_engine_ends_at_once() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Files.createDirectories(paths.dir());
        // Once first, so class loading is not counted as the wait's.
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> EngineSpawn.awaitStartup(paths, "1.0.0", Duration.ofSeconds(5), null))
                .isInstanceOf(InterruptedIOException.class);
        var mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long cpu = mx.getCurrentThreadCpuTime();
        long bytes = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> EngineSpawn.awaitStartup(paths, "1.0.0", Duration.ofSeconds(5), null))
                .isInstanceOf(InterruptedIOException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(Duration.ofNanos(mx.getCurrentThreadCpuTime() - cpu)).isLessThan(Duration.ofMillis(500));
        assertThat(mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes)
                .as("bytes allocated by the wait")
                .isLessThan(4L << 20);
        assertThat(Thread.currentThread().isInterrupted())
                .as("the interrupt is kept for the caller")
                .isTrue();
    }
}
