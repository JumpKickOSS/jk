// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.jobs.LiveJobsFile;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.ShelfPins;
import cc.jumpkick.engine.plugin.StartupCaches;
import cc.jumpkick.host.Log;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * The one-time start sequence after the election is won, as an ordered list of named steps in
 * {@link #run}. The order is load-bearing and each step says why: the predecessor is told to yield
 * first, and HTTP binds only after the predecessor's {@code bye}.
 *
 * <p>Process-global statics this reaches, by name rather than by pretending they are injected:
 * {@link JkEngineConfig#resolve} and {@link JvmOptions#planAndApply} size the shared worker heap
 * plan once per process; {@link BuildJournal} is the one the engine was composed with.
 */
final class EngineStartup {

    /** The two long-lived chores started here; the lifecycle owner closes them at cleanup. */
    record Started(EngineMaintenance maintenance) {}

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
        reportPreviousOutOfMemory();
        sizeSharedWorkerMemory();
        log.accept("jk engine: listening on " + won.active().socket() + " (pid " + pid + ")");
        yieldPredecessor(won);
        adoptShelfPins();
        sweepStartupCaches();
        bindHttp();
        abandonStaleJournalRows();
        Started started = startChores();
        scheduleWarmups();
        return started;
    }

    /**
     * An engine that ran out of heap could say nothing as it died ({@code -XX:+ExitOnOutOfMemoryError});
     * the jobs file it kept says what it held ({@link LiveJobsFile}). Read before this engine admits
     * anything.
     */
    private void reportPreviousOutOfMemory() {
        try {
            for (String line : LiveJobsFile.previous(EnginePaths.current(), pid)) log.accept(line);
        } catch (RuntimeException e) {
            Log.debug("previous engine's jobs: unread", e);
        }
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

    /**
     * Startup caches another jk version recorded can never map again; under a snapshot jk, those
     * unmapped for 12 hours go too ({@link StartupCaches}). After the predecessor yielded, so its
     * trainer cannot land a cache behind the sweep.
     */
    private void sweepStartupCaches() {
        StartupCaches.sweepQuietly(log);
    }

    /**
     * Read the shelf manifest now, while the pointer still names this engine: a later install from
     * another checkout rewrites it for another engine, and this engine must keep forking the
     * workers it was installed with until it drains.
     */
    private void adoptShelfPins() {
        var pins = ShelfPins.current();
        if (pins == null) {
            log.accept(
                    "jk engine: no shelf manifest names this engine; first-party workers launch from the shelf as is");
            return;
        }
        log.accept("jk engine: " + pins.jars().size() + " shelf jars pinned by the install from " + pins.source());
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
     * The 1-minute loop reloads config.toml on mtime and runs the wall-clock 12 h maintenance (cache
     * sweep and prune, calibration); laptop suspend-safe, since due work runs on the next minute
     * tick after resume.
     */
    private Started startChores() {
        EngineMaintenance maintenance = new EngineMaintenance(log, idle::enqueueScheduledCachePrune);
        maintenance.start();
        return new Started(maintenance);
    }

    /**
     * First-start self-heal: host calibration on the idle worker (does not block
     * accept), then touch resolve/PubGrub classes so the first real lock does not pay classload on
     * the critical path.
     */
    private void scheduleWarmups() {
        idle.scheduleHostWarmup();
        idle.scheduleResolveClassWarmup();
    }
}
