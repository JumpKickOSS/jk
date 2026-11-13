// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Single serial worker that keeps each project's run directories within their retention: the
 * newest {@link #DEFAULT_MAX_RUNS} runs, none older than {@link #DEFAULT_MAX_AGE_DAYS} days.
 *
 * <p>Every build finish calls {@link #request} with its builds root; requests made while a pass is
 * in flight queue that root, once however often it is asked for.
 */
public final class RunReaper {

    /** Default: keep 50 runs per project. */
    public static final int DEFAULT_MAX_RUNS = 50;

    /** Default: drop runs older than 90 days. */
    public static final int DEFAULT_MAX_AGE_DAYS = 90;

    private static final RunReaper INSTANCE = new RunReaper();

    private final Object startLock = new Object();

    /** Builds roots asked for and not yet reaped, in request order; guarded by {@link #startLock}. */
    private final Set<Path> pending = new LinkedHashSet<>();

    /** Whether the worker thread is up; guarded by {@link #startLock}. */
    private boolean running;

    private volatile int maxRuns = DEFAULT_MAX_RUNS;
    private volatile long maxAgeMillis = DEFAULT_MAX_AGE_DAYS * 86_400_000L;

    private RunReaper() {}

    public static RunReaper get() {
        return INSTANCE;
    }

    public void configure(int maxRunsPerProject, int maxAgeDays) {
        if (maxRunsPerProject > 0) this.maxRuns = maxRunsPerProject;
        if (maxAgeDays > 0) this.maxAgeMillis = maxAgeDays * 86_400_000L;
    }

    /**
     * Request a pass over {@code buildsRoot}, the root the finished run was journaled under; starts
     * the worker if idle, else queues the root behind the pass in flight.
     */
    public void request(Path buildsRoot) {
        synchronized (startLock) {
            pending.add(buildsRoot.toAbsolutePath().normalize());
            if (running) return;
            running = true;
        }
        Thread t = new Thread(this::loop, "jk-run-reaper");
        t.setDaemon(true);
        t.start();
    }

    /** True while a pass is in flight or queued. */
    public boolean busy() {
        synchronized (startLock) {
            return running || !pending.isEmpty();
        }
    }

    /**
     * Block until the reaper is idle or {@code timeoutMs} elapses. Used so idle-boundary work that
     * walks the builds root trails the deletes.
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (busy() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void loop() {
        boolean drained = false;
        try {
            for (Path root = next(); root != null; root = next()) {
                try {
                    runOnce(root);
                } catch (RuntimeException e) {
                    // never fail the product over retention
                    Log.debug("RunReaper: pass failed", e);
                }
            }
            drained = true;
        } finally {
            // next() already stopped a drained worker, and a later request may have started another.
            if (!drained) {
                synchronized (startLock) {
                    running = false;
                }
            }
        }
    }

    /** The next queued root, or null after marking the worker stopped when none is left. */
    private @Nullable Path next() {
        synchronized (startLock) {
            Iterator<Path> it = pending.iterator();
            if (!it.hasNext()) {
                running = false;
                return null;
            }
            Path root = it.next();
            it.remove();
            return root;
        }
    }

    /** Reap every project home under {@code buildsRoot} now (tests). */
    public void runOnce(Path buildsRoot) {
        long now = System.currentTimeMillis();
        for (Path home : ProjectBuilds.listProjectHomes(buildsRoot)) reapProject(home, now);
    }

    private void reapProject(Path home, long now) {
        List<Path> runs = new ArrayList<>(ProjectBuilds.listRuns(home));
        for (Path run : List.copyOf(runs)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(run, BasicFileAttributes.class);
                long created = attrs.creationTime().toMillis();
                if (created <= 0) created = attrs.lastModifiedTime().toMillis();
                if (now - created > maxAgeMillis) {
                    PathUtil.deleteRecursively(run);
                    runs.remove(run);
                }
            } catch (IOException ignored) {
                // gone already
            }
        }
        while (runs.size() > maxRuns) {
            PathUtil.deleteRecursively(runs.removeLast());
        }
    }
}
