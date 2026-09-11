// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * The one-time start sequence after the election is won, as an ordered list of named steps in
 * {@link #run}. The order is load-bearing and each step says why: the predecessor is told to yield
 * before the AOT sweep, HTTP binds only after the predecessor's {@code bye}, and this engine's own
 * trainer starts after the sweep so it never sweeps its own output.
 *
 * <p>Process-global statics this reaches, by name rather than by pretending they are injected:
 * {@link JkEngineConfig#resolve} and {@link JvmOptions#planAndApply} size the shared worker heap
 * plan once per process; {@link JkDirs#state} and {@link EngineInstall} locate the AOT directory and
 * the install's displaced files; {@link BuildJournal} is the one the engine was composed with.
 */
final class EngineStartup {

    /** The two long-lived chores started here; the lifecycle owner closes them at cleanup. */
    record Started(StoreFeedRefresh feeds, EngineMaintenance maintenance) {}

    private final String version;
    private final long pid;
    private final EngineElection election;
    private final AotTrainer aot;
    private final EngineHttpFront http;
    private final BuildJournal journal;
    private final IdleHousekeeping idle;
    private final Consumer<String> log;

    EngineStartup(
            String version,
            long pid,
            EngineElection election,
            AotTrainer aot,
            EngineHttpFront http,
            BuildJournal journal,
            IdleHousekeeping idle,
            Consumer<String> log) {
        this.version = version;
        this.pid = pid;
        this.election = election;
        this.aot = aot;
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
        retireOtherVersionsAot();
        collectDisplacedInstallFiles();
        startOwnTrainer();
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

    /** Drop other product versions' AOT (engine + workers); keep ours (named {@code *-<version>-*}). */
    private void retireOtherVersionsAot() {
        try {
            int wiped = EngineInstall.wipeAotDirectory(JkDirs.state().resolve("aot"), version);
            if (wiped > 0) {
                log.accept("jk engine: retired " + wiped + " AOT cache(s) from other versions");
            }
        } catch (RuntimeException e) {
            // best-effort
            Log.debug("retireOtherVersionsAot: best-effort", e);
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

    /** Our own trainer starts after the sweep, so it never sweeps its own output. */
    private void startOwnTrainer() {
        aot.startIfConfigured();
    }

    /**
     * HTTP binds only after the predecessor has yielded ({@code askPredecessorToYield} waits for
     * {@code bye}, which is sent after HTTP/UDS unbind). Binding earlier lost the handoff race and
     * stuck "Address already in use" in {@code jk engine status} for the engine's life.
     */
    private void bindHttp() {
        http.start();
    }

    /** Leftover {@code running=true} journal rows from a killed engine cannot still be live. */
    private void abandonStaleJournalRows() {
        int abandoned = journal.abandonStaleRunning(version);
        if (abandoned > 0) {
            log.accept("jk engine: abandoned " + abandoned + " stale in-flight journal entries");
        }
    }

    /**
     * Store feeds are revalidated by HostWarmup / EngineMaintenance (not a 12 h process sleep). The
     * 1-minute loop reloads config.toml on mtime and runs the wall-clock 12 h maintenance (feeds,
     * templates, cache prune, AOT/cal); laptop suspend-safe, since due work runs on the next minute
     * tick after resume.
     */
    private Started startChores() {
        StoreFeedRefresh feeds = new StoreFeedRefresh(log, null);
        EngineMaintenance maintenance = new EngineMaintenance(log, feeds, idle::enqueueScheduledCachePrune);
        maintenance.start();
        return new Started(feeds, maintenance);
    }

    /**
     * First-start self-heal: feeds → templates → AOT/cal on the idle worker (does not block accept),
     * then touch resolve/PubGrub classes so the first real lock does not pay classload on the critical
     * path.
     */
    private void scheduleWarmups() {
        idle.scheduleHostWarmup(false);
        idle.scheduleResolveClassWarmup();
    }
}
