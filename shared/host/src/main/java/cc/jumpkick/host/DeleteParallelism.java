// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.config.EnvValues;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How many files jk unlinks at once when it removes a tree. Unlink is latency-bound, so one thread
 * leaves most of a disk idle; but the right number is a property of the OS and its filesystem, not
 * of the CPU count, and the wrong one on Windows is slower than one thread.
 *
 * <p>The defaults come from a delete bench on ext4 (WSL) and on NTFS with Defender live; macOS
 * takes Linux's rule until it is measured. {@code JK_DELETE_JOBS=N} overrides them for a network
 * or virtual disk that wants fewer, and for the bench harness. The build's {@code -j} budget is a
 * different number and is not consulted.
 */
public final class DeleteParallelism {

    /** Environment override: a positive integer; {@code 1} is the serial walk. */
    public static final String ENV = "JK_DELETE_JOBS";

    /** NTFS: more than a few concurrent unlinks contend in the filesystem and the scanner. */
    static final int WINDOWS = 4;

    /** ext4 and APFS: unlinkers per online processor, and the cap that keeps a big box sane. */
    static final int POSIX_PER_CPU = 4;

    static final int POSIX_CAP = 64;

    /** Hard ceiling on the override, so a typo does not spawn a thousand threads. */
    static final int MAX = 256;

    private DeleteParallelism() {}

    /** The width for this host: the override when set, else the OS default. */
    public static int width() {
        return width(System::getenv, Os.isWindows(), HostProcessors.count());
    }

    /** {@link #width()} with its inputs explicit, for tests. */
    static int width(Function<String, @Nullable String> env, boolean windows, int cpus) {
        Optional<Integer> override = EnvValues.intValue(env, ENV);
        if (override.isPresent() && override.get() > 0) return Math.min(MAX, override.get());
        if (windows) return WINDOWS;
        return Math.max(1, Math.min(POSIX_CAP, cpus * POSIX_PER_CPU));
    }

    /**
     * The one pool every tree delete in this JVM runs on, sized to {@link #width()}. Daemon
     * threads, so an idle pool never holds the process open; idle workers time out on their own.
     */
    public static ForkJoinPool pool() {
        return Shared.POOL;
    }

    /** A pool of exactly {@code width} for a measurement; the caller shuts it down. */
    static ForkJoinPool pool(int width) {
        return new ForkJoinPool(Math.max(1, width), DAEMON_WORKERS, null, false);
    }

    private static final ForkJoinPool.ForkJoinWorkerThreadFactory DAEMON_WORKERS = pool -> {
        var t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        t.setDaemon(true);
        t.setName("jk-delete-" + t.getPoolIndex());
        return t;
    };

    /** Lazily built on first use so a JVM that never deletes a tree never reads the width. */
    private static final class Shared {
        static final ForkJoinPool POOL = pool(width());
    }
}
