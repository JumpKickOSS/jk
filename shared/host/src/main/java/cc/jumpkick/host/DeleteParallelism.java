// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.config.EnvValues;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How many files jk unlinks at once when it removes a tree. Unlink is latency-bound, so one thread
 * leaves most of a disk idle; but the right number is a property of the filesystem, not of the CPU
 * count, and past it the unlinkers contend with each other and the whole thing slows down again.
 *
 * <p>The defaults were measured with {@code DeleteTreeBenchTest} on a built {@code target/} of
 * twenty thousand files: on ext4 (WSL, 24 processors) sixteen unlinkers were about seven times
 * faster than one and thirty-two or sixty-four were slower than sixteen; on NTFS with Defender
 * live, eight were about seven times faster than one and sixteen slightly slower than eight. macOS
 * takes the ext4 number until it is measured. {@code JK_DELETE_JOBS=N} overrides the default for a
 * network or virtual disk that wants something else, and for the bench. The build's {@code -j}
 * budget is a different number and is not consulted.
 */
public final class DeleteParallelism {

    /** Environment override: a positive integer; {@code 1} is the serial walk. */
    public static final String ENV = "JK_DELETE_JOBS";

    /** NTFS: the measured peak; sixteen was already slower. */
    static final int WINDOWS = 8;

    /** ext4, and APFS by assumption: the measured peak; thirty-two was already slower. */
    static final int POSIX = 16;

    /** Hard ceiling on the override, so a typo does not spawn a thousand threads. */
    static final int MAX = 256;

    private DeleteParallelism() {}

    /** The width for this host: the override when set, else the OS default. */
    public static int width() {
        return width(System::getenv, Os.isWindows());
    }

    /** {@link #width()} with its inputs explicit, for tests. */
    static int width(Function<String, @Nullable String> env, boolean windows) {
        Optional<Integer> override = EnvValues.intValue(env, ENV);
        if (override.isPresent() && override.get() > 0) return Math.min(MAX, override.get());
        return windows ? WINDOWS : POSIX;
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
