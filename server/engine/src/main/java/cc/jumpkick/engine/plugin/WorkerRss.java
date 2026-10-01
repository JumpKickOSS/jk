// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import java.time.Duration;
import java.util.List;

/**
 * A worker's measured resident set against its lease: when it is far over, the cap its own cgroup
 * gets, and the words that name it. {@link WorkerLeases.Ledger#sample} charges the reading.
 */
public final class WorkerRss {

    /**
     * How often the engine ledger reads each live worker's resident set. Memory that outgrows a
     * lease climbs over minutes; two seconds lets at most one burst of grants through on a stale
     * reading, the same staleness as the cached budget.
     */
    public static final Duration SAMPLE_EVERY = Duration.ofSeconds(2);

    /** Diagnostic code of the run warning that names a worker far over its lease. */
    public static final String OVER_LEASE_CODE = "memory-over-lease";

    /** A worker is {@linkplain #farOverLease far over its lease} only past at least this much. */
    static final long OVER_LEASE_FLOOR_BYTES = 1L << 30;

    /** The granularity a cgroup stores {@code memory.max} at. */
    private static final long PAGE_BYTES = 4096;

    /** System property the test launcher passes its runner class in. */
    private static final String PLUGIN_CLASS_FLAG = "-Djk.plugin.class=";

    private WorkerRss() {}

    /** A worker's resident bytes by pid; {@code -1} when it cannot be read. */
    @FunctionalInterface
    public interface Source {
        long rssBytes(long pid);
    }

    /**
     * True when a worker resident at {@code rssBytes} is far over {@code leaseBytes}: by more than
     * the larger of 1 GiB and a quarter of the lease. The lease already carries the overhead
     * measured above a filled heap (about 6%), so a quarter is native memory the estimate never
     * covered (metaspace, thread stacks, direct buffers); the 1 GiB floor keeps a small worker's
     * few hundred MiB of metaspace from being named.
     */
    public static boolean farOverLease(long leaseBytes, long rssBytes) {
        if (rssBytes <= 0) return false;
        long lease = Math.max(0, leaseBytes);
        return rssBytes - lease > Math.max(OVER_LEASE_FLOOR_BYTES, lease / 4);
    }

    /**
     * {@code memory.max} for one worker's own cgroup: three quarters of {@code budgetBytes}, so a
     * runaway worker leaves the rest of the build and the host a quarter, never below the worker's
     * lease and never above the budget, rounded down to a 4 KiB page as the kernel stores it. {@code
     * -1} when the budget is unknown.
     */
    public static long workerCapBytes(long leaseBytes, long budgetBytes) {
        if (budgetBytes <= 0) return -1;
        long share = budgetBytes / 4 * 3;
        return Math.min(budgetBytes, Math.max(Math.max(0, leaseBytes), share)) & -PAGE_BYTES;
    }

    /** {@code test JVM using 12.2 GiB, leased 700 MiB}. */
    public static String overLeasePhrase(String what, long rssBytes, long leaseBytes) {
        return what + " using " + WorkerLeases.format(rssBytes) + ", leased " + WorkerLeases.format(leaseBytes);
    }

    /** What {@link #describe} calls jk's test runner. */
    static final String TEST_JVM = "test JVM";

    /**
     * What a fork is, for the lines that name it: {@code test JVM} for jk's test runner, {@code
     * worker JVM} for another JVM, or the program's file name.
     */
    public static String describe(List<String> command) {
        for (String arg : command) {
            if (arg.startsWith(PLUGIN_CLASS_FLAG) && arg.endsWith(".TestRunner")) return TEST_JVM;
        }
        if (WorkerLeases.jvmCommand(command)) return "worker JVM";
        String exe = WorkerLeases.executable(command);
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        String name = slash >= 0 ? exe.substring(slash + 1) : exe;
        return name.isEmpty() ? "worker" : name;
    }

    /**
     * Run {@code pass} every {@code every} for the life of the engine, on a daemon platform thread
     * so a saturated virtual-thread scheduler cannot starve the reading. A pass that throws is a
     * debug line.
     */
    static void start(Duration every, Runnable pass) {
        SessionContext.startPlatform("jk-worker-rss", () -> {
            while (true) {
                try {
                    Thread.sleep(every);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    pass.run();
                } catch (RuntimeException e) {
                    Log.debug("worker rss sample", e);
                }
            }
        });
    }
}
