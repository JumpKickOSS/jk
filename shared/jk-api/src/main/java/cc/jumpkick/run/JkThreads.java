// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Shared lazy executors: {@link #cpu()} (bounded FJP, max(8, cores)), {@link #platform()} (a platform
 * thread per task) and {@link #io()} (virtual threads), each wrapped once in {@link
 * ContextPropagatingExecutorService}.
 *
 * <p>{@link #io()} is for waiting only: a socket, a download, a child process. A virtual thread that
 * loads a class, or blocks in a native call the scheduler does not compensate for, pins its carrier,
 * and with every carrier pinned no virtual thread in the engine runs; a fresh engine preparing a wide
 * workspace on virtual threads deadlocked that way. Planning, hashing, packaging, a module's
 * build and anything else that computes or loads classes runs on {@link #cpu()}, or, when it also
 * waits on others for long stretches, on {@link #platform()}. Shutdown hook on first use;
 * Ctrl-C {@code halt} kills both pools.
 */
public final class JkThreads {

    /**
     * Parallelism for the CPU pool: the machine's cores with a floor of 8. The floor keeps small
     * hosts responsive: CPU steps mostly wait on forked JVMs, and how many of those run is the
     * worker lease ledger, not this pool. Those waits are {@link #blockManaged} blocks, so a step
     * waiting on its fork lets the pool start a thread for the steps queued behind it.
     */
    public static final int CPU_THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 8);

    /** The real pools; {@link #cpu}/{@link #io} hold their context-propagating wrappers. */
    private static volatile @Nullable ExecutorService cpuReal;

    private static volatile @Nullable ExecutorService ioReal;
    private static volatile @Nullable ExecutorService platformReal;
    private static volatile @Nullable ExecutorService platform;
    private static volatile @Nullable ExecutorService cpu;
    private static volatile @Nullable ExecutorService io;
    private static final Object LOCK = new Object();

    private JkThreads() {}

    /** Bounded CPU-bound pool. Threads are daemon, named {@code jk-cpu-N}. */
    public static ExecutorService cpu() {
        ExecutorService local = cpu;
        if (local != null) return local;
        synchronized (LOCK) {
            if (cpu == null) {
                cpuReal = newCpuPool();
                cpu = new ContextPropagatingExecutorService(cpuReal);
                registerShutdownHook();
            }
            return cpu;
        }
    }

    /**
     * True when every thread of {@link #cpu()} is busy, so a task submitted now queues until one
     * finishes or blocks in a way the pool compensates for. False before the pool exists.
     */
    public static boolean cpuSaturated() {
        return cpuReal instanceof ForkJoinPool pool && pool.getActiveThreadCount() >= pool.getParallelism();
    }

    /** A wait {@link #blockManaged} runs once. */
    @FunctionalInterface
    public interface Wait {
        void await() throws InterruptedException;
    }

    /**
     * Run {@code wait} once as a {@link ForkJoinPool#managedBlock}: a {@link #cpu()} thread that
     * waits so lets the pool start another for the tasks queued behind it, which a latch, a join, a
     * monitor wait or a pipe read does not. Off the pool it just runs {@code wait}.
     */
    public static void blockManaged(Wait wait) throws InterruptedException {
        ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
            private boolean done;

            @Override
            public boolean block() throws InterruptedException {
                wait.await();
                done = true;
                return true;
            }

            @Override
            public boolean isReleasable() {
                return done;
            }
        });
    }

    /** {@code monitor.wait(millis)}, whose monitor the caller holds, as a {@link #blockManaged} block. */
    public static void waitManaged(Object monitor, long millis) throws InterruptedException {
        blockManaged(() -> monitor.wait(millis));
    }

    /** Virtual-thread executor. One thread per task; names start with {@code jk-io-}. */
    public static ExecutorService io() {
        ExecutorService local = io;
        if (local != null) return local;
        synchronized (LOCK) {
            if (io == null) {
                ioReal = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("jk-io-", 0).factory());
                io = new ContextPropagatingExecutorService(ioReal);
                registerShutdownHook();
            }
            return io;
        }
    }

    /**
     * A platform thread per task, daemon, named {@code jk-run-N}: for a body that computes or loads
     * classes and also waits on other work for long stretches — a module's build waiting on its
     * steps, a step waiting on a tool, an estimate priced beside the build. Unbounded like {@link
     * #io()}, so a body may wait on another body here without starving it; bound the callers, not
     * the pool.
     */
    public static ExecutorService platform() {
        ExecutorService local = platform;
        if (local != null) return local;
        synchronized (LOCK) {
            if (platform == null) {
                platformReal = Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().daemon().name("jk-run-", 0).factory());
                platform = new ContextPropagatingExecutorService(platformReal);
                registerShutdownHook();
            }
            return platform;
        }
    }

    private static ExecutorService newCpuPool() {
        AtomicInteger counter = new AtomicInteger();
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName("jk-cpu-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ForkJoinPool(CPU_THREADS, factory, null, /* asyncMode= */ false);
    }

    private static volatile boolean hookRegistered = false;

    private static void registerShutdownHook() {
        if (hookRegistered) return;
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(JkThreads::shutdown, "jk-threads-shutdown"));
    }

    private static void shutdown() {
        // Best-effort: virtual threads + daemon CPU workers will die with the JVM,
        // but politely shutting down lets in-flight tasks unblock first.
        ExecutorService localCpu = cpuReal;
        if (localCpu != null) localCpu.shutdown();
        ExecutorService localIo = ioReal;
        if (localIo != null) localIo.shutdown();
        ExecutorService localPlatform = platformReal;
        if (localPlatform != null) localPlatform.shutdown();
    }
}
