// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Lifecycle watchdog over {@link EngineElection#state}: a {@link EngineState#DISPLACED displaced}
 * engine yields HTTP at once so the successor can bind the port (attached dashboard streams get no
 * vote; a tab reconnects to the successor), drains its in-flight jobs on its own generation socket
 * and exits when idle or at the drain deadline. An {@link EngineState#ORPHANED orphaned} one exits
 * once unused, keeping HTTP while a browser is attached because there is no successor to hand it
 * to. A {@link EngineState#PRIMARY primary} one never self-terminates.
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
        // Engine-lifetime election watch; reads no session.
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
        return switch (election.state()) {
            case DISPLACED -> {
                log.accept("jk engine: displaced by a newer engine — yielding HTTP and draining");
                yieldListeners.accept(activeBuildPlans.getAsInt() == 0);
                yield true;
            }
            case ORPHANED -> {
                if (!orphanedAndUnused()) yield false;
                log.accept("jk engine: no endpoint names this engine and it is unused — exiting");
                exitUnused.run();
                yield true;
            }
            case PRIMARY -> false;
        };
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
