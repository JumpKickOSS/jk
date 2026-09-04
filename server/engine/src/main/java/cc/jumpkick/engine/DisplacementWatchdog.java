// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Lifecycle watchdog over the endpoint pointer <em>and</em> the pid file. The filename alone is
 * not identity: deleting and recreating the state dir leaves a ghost engine whose generation
 * name matches the successor's pointer, so it would never drain. The pid file and a hello of
 * the path catch that.
 *
 * <ul>
 * <li><b>Pointer, pid file, or live hello names someone else</b> — displaced. Yield UDS/TCP and
 * HTTP immediately so the successor can bind them, drain in-flight jobs, report status to the
 * successor, exit when idle. Attached dashboard streams get no vote: the successor needs the
 * port, and a tab reconnects to it.
 * <li><b>The pointer is absent</b> — orphaned. Exit once genuinely unused — no jobs and no
 * attached streams. Keep HTTP while a browser is attached, because here there is no successor
 * to hand it to.
 * <li><b>The pointer names this engine and the pid file matches</b> — primary. Never
 * self-terminates.
 * </ul>
 *
 * <p>The watchdog decides; the lifecycle owner transitions. Both transitions reach {@code
 * EngineServer} through the two callbacks, and this class never sees the lifecycle lock.
 */
final class DisplacementWatchdog {

    /** How often the endpoint pointer and pid file are re-read. */
    static final long TICK_MS = 1_000;

    private final EngineElection election;
    private final IntSupplier activeBuildPlans;
    private final IntSupplier liveEventStreams;
    private final BooleanSupplier shuttingDown;
    private final Consumer<Boolean> yieldListeners;
    private final Runnable exitUnused;
    private final Consumer<String> log;

    /**
     * @param yieldListeners the displaced transition; {@code true} means exit now (no plans in flight)
     * @param exitUnused the orphaned-and-unused transition
     */
    DisplacementWatchdog(
            EngineElection election,
            IntSupplier activeBuildPlans,
            IntSupplier liveEventStreams,
            BooleanSupplier shuttingDown,
            Consumer<Boolean> yieldListeners,
            Runnable exitUnused,
            Consumer<String> log) {
        this.election = election;
        this.activeBuildPlans = activeBuildPlans;
        this.liveEventStreams = liveEventStreams;
        this.shuttingDown = shuttingDown;
        this.yieldListeners = yieldListeners;
        this.exitUnused = exitUnused;
        this.log = log;
    }

    /** Tick on a daemon thread until the engine shuts down or the decision is made. */
    void start() {
        Thread t = new Thread(
                () -> {
                    while (!shuttingDown.getAsBoolean()) {
                        try {
                            Thread.sleep(TICK_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            if (tick()) return;
                        } catch (IOException ignored) {
                            // transient read failure — check again next tick
                        }
                    }
                },
                "jk-engine-displacement-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One check: displaced → yield listeners and drain; orphaned and unused → exit. Returns
     * {@code true} when the watchdog's work is done.
     */
    boolean tick() throws IOException {
        if (election.displacedBySuccessor()) {
            log.accept("jk engine: displaced by a newer engine — yielding listeners and draining");
            yieldListeners.accept(activeBuildPlans.getAsInt() == 0);
            return true;
        }
        if (election.endpointMissing() && orphanedAndUnused()) {
            log.accept("jk engine: no endpoint names this engine and it is unused — exiting");
            exitUnused.run();
            return true;
        }
        return false;
    }

    /**
     * True when an orphaned engine has nothing left to serve: no in-flight jobs and no attached SSE
     * stream.
     *
     * <p>The stream check is what keeps this from breaking the case that matters — a developer who works
     * through the Web UI, leaves the tab open overnight and comes back to it. A browser cannot spawn an
     * engine the way the CLI can, so exiting under an attached tab would leave them with a dead SPA and no
     * indication that the fix is to run a command.
     */
    boolean orphanedAndUnused() {
        if (activeBuildPlans.getAsInt() != 0) return false;
        return liveEventStreams.getAsInt() == 0;
    }
}
