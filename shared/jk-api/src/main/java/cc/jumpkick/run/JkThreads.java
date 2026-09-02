// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared lazy CLI executors: {@link #cpu()} (bounded FJP, max(8, cores)) and {@link #io()} (virtual threads),
 * each wrapped once in {@link ContextPropagatingExecutorService}. Shutdown hook on first use;
 * Ctrl-C {@code halt} kills both pools.
 */
public final class JkThreads {

    /**
     * Parallelism for the CPU pool: the machine's cores with a floor of 8. The old
     * {@code min(cores, 8)} capped big machines at 8 concurrent compile/package steps — with 13
     * modules ready at the workspace graph's widest level, a 24-core host ran at a third of its
     * width. The floor keeps small hosts responsive: CPU steps mostly BLOCK on forked compiler
     * JVMs, and actual fork concurrency is governed by the memory plan ({@code PluginSlots}), not
     * by this pool.
     */
    public static final int CPU_THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 8);

    /** The real pools; {@link #cpu}/{@link #io} hold their context-propagating wrappers. */
    private static volatile ExecutorService cpuReal;

    private static volatile ExecutorService ioReal;
    private static volatile ExecutorService cpu;
    private static volatile ExecutorService io;
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
    }
}
