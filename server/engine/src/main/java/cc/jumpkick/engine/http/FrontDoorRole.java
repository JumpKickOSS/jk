// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.time.Clock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * A WSL guest's claim on the front door (8910): it takes the port when no front door has been heard
 * from for its takeover delay, keeps it only when Windows' 8910 reaches it, and lets it go when the
 * host asks. The port is the lock: binding 8910 is the only way to hold the role, so two holders
 * cannot share one loopback.
 *
 * <p>The front door (the host engine, or the guest holding 8910) tells every other paired engine it
 * is there every {@link #HEARTBEAT_MS}; a guest that hears nothing for its delay tries to take 8910.
 * The delay is a debounce that grows with each takeover it holds (5 s, 15 s, 60 s, at most 5 min) and resets
 * after 10 min without one, plus 2 s for each guest that outranks this one (lower engine id). A
 * front door that says it is leaving skips the debounce, and its handover does not count as one. {@link #tick} drives it, once a second.
 */
public final class FrontDoorRole {

    /** How often the front door tells the other engines it is there. */
    public static final long HEARTBEAT_MS = 2_000;

    /** The takeover debounce after 0, 1, 2 and 3 or more recent takeovers. */
    static final long[] DELAYS_MS = {5_000, 15_000, 60_000, 300_000};

    /** Without a takeover for this long, the debounce is back to its first step. */
    static final long STABLE_MS = 600_000;

    /** How often a holder checks again from Windows that 8910 still reaches it. */
    static final long RECHECK_MS = 30_000;

    /** What each guest that outranks this one adds to the delay. */
    static final long RANK_MS = 2_000;

    /** Whether Windows' 8910 reaches this guest, as checked from the Windows side. */
    public enum Reach {
        /** The identity there echoed this guest's nonce. */
        REACHABLE,
        /** Refused, a connection that never completed, or another listener answered. */
        UNREACHABLE,
        /** Interop or PowerShell could not say: not there, blocked, or it did not finish. */
        UNKNOWN
    }

    /** The port the role drives; the engine supplies it. */
    public interface Seat {
        /** Binds 8910 as the front door; false when it is taken. */
        boolean take();

        /** Lets 8910 go. */
        void release();

        /** Whether Windows' 8910 reaches this guest's listener. */
        Reach verify();
    }

    private final Seat seat;
    private final Clock clock;
    private final IntSupplier rank;
    private final Consumer<String> log;

    private long heardAt;
    private boolean relays;
    private boolean leaving;
    private boolean holding;
    private int recentTakeovers;
    private long lastTakeoverAt = Long.MIN_VALUE;
    private boolean saidUnknown;
    private long checkedAt;

    /**
     * @param rank how many paired guests outrank this one
     */
    public FrontDoorRole(Seat seat, Clock clock, IntSupplier rank, Consumer<String> log) {
        this.seat = seat;
        this.clock = clock;
        this.rank = rank;
        this.log = log;
        this.heardAt = clock.millis(); // a host starting beside this guest gets one delay to say so
    }

    /** A front door said it is there. */
    public synchronized void present() {
        present(false);
    }

    /** The front door is there; {@code relays} says it raises this engine's notifications for it. */
    public synchronized void present(boolean relays) {
        heardAt = clock.millis();
        leaving = false;
        this.relays = relays;
    }

    /**
     * Whether a front door raises this engine's notifications now: it said so in a heartbeat heard
     * within three beats, it is not leaving, and this engine is not the front door itself. A front
     * door that relays nothing, or stopped beating, leaves this engine notifying for itself.
     */
    public synchronized boolean relayed() {
        return relays && !leaving && !holding && clock.millis() - heardAt <= 3 * HEARTBEAT_MS;
    }

    /** The front door said it is stopping. */
    public synchronized void leaving() {
        leaving = true;
    }

    /** The host asked for 8910 back; true when this guest held it and has let it go. */
    public synchronized boolean stepAside() {
        heardAt = clock.millis();
        leaving = false;
        if (!holding) return false;
        holding = false;
        seat.release();
        log.accept("jk engine: gave the front door (8910) back to the host engine");
        return true;
    }

    /** Whether this guest holds 8910. */
    public synchronized boolean holding() {
        return holding;
    }

    /** How long this guest waits, with no front door heard from, before it tries to take 8910. */
    synchronized long delayMs() {
        long now = clock.millis();
        if (lastTakeoverAt != Long.MIN_VALUE && now - lastTakeoverAt >= STABLE_MS) recentTakeovers = 0;
        long ranked = RANK_MS * Math.max(0, rank.getAsInt());
        if (leaving) return ranked;
        return DELAYS_MS[Math.min(recentTakeovers, DELAYS_MS.length - 1)] + ranked;
    }

    /** One step: takes 8910 when the front door has been quiet for the delay; once a second. */
    public synchronized void tick() {
        long now = clock.millis();
        if (holding) {
            // A listener Windows stopped reaching (wslrelay lost the forward) is no front door.
            if (now - checkedAt < RECHECK_MS) return;
            checkedAt = now;
            if (seat.verify() == Reach.UNREACHABLE) {
                holding = false;
                heardAt = now;
                seat.release();
                log.accept("jk engine: front door unreachable from Windows; gave up 8910");
            }
            return;
        }
        if (now - heardAt < delayMs()) return;
        heardAt = now; // whatever happens next, wait a full delay before the next try
        boolean handedOver = leaving;
        leaving = false;
        if (!seat.take()) return; // another guest in this VM holds it
        checkedAt = now;
        Reach reach = seat.verify();
        if (reach == Reach.UNREACHABLE) {
            // Someone answers Windows' 8910 (the host, back already, or another program): no takeover.
            seat.release();
            log.accept("jk engine: front door unreachable from Windows; something else holds 8910 there");
            return;
        }
        holding = true;
        if (!handedOver) {
            // A front door that said it was leaving is not flapping; only a silent one backs off.
            recentTakeovers++;
            lastTakeoverAt = now;
        }
        if (reach == Reach.REACHABLE) {
            log.accept("jk engine: took the front door (8910); no front door answered");
        } else if (!saidUnknown) {
            saidUnknown = true;
            log.accept("jk engine: took the front door (8910); could not check it from Windows");
        }
    }
}
