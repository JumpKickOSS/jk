// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.WorkerAotCache;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * The one-time start sequence after the election is won, as an ordered list of named steps in
 * {@link #run}. The order is load-bearing and each step says why: the predecessor is told to yield
 * first, and HTTP binds only after the predecessor's {@code bye}.
 *
 * <p>Process-global statics this reaches, by name rather than by pretending they are injected:
 * {@link JkEngineConfig#resolve} and {@link JvmOptions#planAndApply} size the shared worker heap
 * plan once per process; {@link EngineInstall} locates the install's displaced files;
 * {@link BuildJournal} is the one the engine was composed with.
 */
final class EngineStartup {

    /** The two long-lived chores started here; the lifecycle owner closes them at cleanup. */
    record Started(StoreFeedRefresh feeds, EngineMaintenance maintenance) {}

    private final String version;
    private final long pid;
    private final EngineElection election;
    private final EngineHttpFront http;
    private final BuildJournal journal;
    private final IdleHousekeeping idle;
    private final Consumer<String> log;

    EngineStartup(
            String version,
            long pid,
            EngineElection election,
            EngineHttpFront http,
            BuildJournal journal,
            IdleHousekeeping idle,
            Consumer<String> log) {
        this.version = version;
        this.pid = pid;
        this.election = election;
        this.http = http;
        this.journal = journal;
        this.idle = idle;
        this.log = log;
    }

    /** The sequence. Each step is one method below, in this order. */
    Started run(EngineElection.Won won) throws IOException {
        sizeSharedWorkerMemory();
        log.accept("jk engine: listening on " + won.active().socket() + " (pid " + pid + ")");
        yieldPredecessor(won);
        collectDisplacedInstallFiles();
        sweepForeignWorkerCaches();
        bindHttp();
        abandonStaleJournalRows();
        Started started = startChores();
        scheduleWarmups();
        return started;
    }

    /**
     * Size the shared worker-JVM memory plan once for the process (core-count concurrency). Hosted
     * builds pass {@code applyMemoryPlan=false} so concurrent requests do not overwrite it.
     */
    private void sizeSharedWorkerMemory() {
        int cap = Jobs.resolve(JkEngineConfig.resolve());
        JvmOptions.planAndApply(HeapPlan.requestedJvms(cap, 1, false, cap));
    }

    /**
     * Tell the predecessor to drain FIRST — that is what makes it suppress training and kill its
     * trainer sidecar. Wiping before that signal leaves a window in which its in-flight trainer can
     * atomically rename a fresh cache into the directory we are about to sweep, which is exactly the
     * refill the sweep was meant to prevent.
     */
    private void yieldPredecessor(EngineElection.Won won) throws IOException {
        election.askPredecessorToYield(won.displaced());
    }

    /** Worker startup caches for another jk version or another JDK can never map again. */
    private void sweepForeignWorkerCaches() {
        try {
            WorkerAotCache.sweepForeign();
        } catch (RuntimeException e) {
            Log.debug("sweepForeignWorkerCaches: best-effort", e);
        }
    }

    private void collectDisplacedInstallFiles() {
        try {
            var gc = EngineInstall.current().gc();
            if (!gc.isEmpty()) {
                log.accept("jk engine: removed " + gc.size() + " displaced install file(s)");
            }
        } catch (RuntimeException e) {
            // a predecessor may still have the previous jar mapped — retry on the next cycle
            Log.debug("collectDisplacedInstallFiles: a predecessor may still have the previous jar mapped", e);
        }
    }

    /**
     * HTTP binds only after the predecessor has yielded ({@code askPredecessorToYield} waits for
     * {@code bye}, which is sent after HTTP/UDS unbind). Binding earlier lost the handoff race and
     * stuck "Address already in use" in {@code jk engine status} for the engine's life.
     */
    private void bindHttp() {
        http.start();
    }

    /**
     * Leftover {@code running=true} journal rows from a killed engine cannot still be live; a
     * draining predecessor's rows are its own to finish and are left to it.
     */
    private void abandonStaleJournalRows() {
        BuildJournal.StaleSweep sweep = journal.abandonStaleRunning(version);
        if (sweep.abandoned() > 0) {
            log.accept("jk engine: abandoned " + sweep.abandoned() + " stale in-flight journal entries");
        }
        if (sweep.live() > 0) {
            log.accept("jk engine: left " + sweep.live() + " in-flight journal entries to a predecessor still running");
        }
    }

    /**
     * Store feeds are revalidated by HostWarmup / EngineMaintenance (not a 12 h process sleep). The
     * 1-minute loop reloads config.toml on mtime and runs the wall-clock 12 h maintenance (feeds,
     * templates, cache prune, calibration); laptop suspend-safe, since due work runs on the next minute
     * tick after resume.
     */
    private Started startChores() {
        StoreFeedRefresh feeds = new StoreFeedRefresh(log, null);
        EngineMaintenance maintenance = new EngineMaintenance(log, feeds, idle::enqueueScheduledCachePrune);
        maintenance.start();
        return new Started(feeds, maintenance);
    }

    /**
     * First-start self-heal: feeds → templates → host calibration on the idle worker (does not block
     * accept), then touch resolve/PubGrub classes so the first real lock does not pay classload on
     * the critical path.
     */
    private void scheduleWarmups() {
        idle.scheduleHostWarmup();
        idle.scheduleResolveClassWarmup();
    }
}
