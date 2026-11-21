// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host.time;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Spells when the engine was not running: the machine suspended, hibernated or had its lid closed,
 * the VM was paused, or the process was stopped. A heartbeat looks at the clocks once a second
 * while a job runs, and only then: an idle engine never wakes for it. A look that arrives many
 * seconds late is a gap ({@link Gap}), told to the {@link Listener} with the jobs that were running
 * across it.
 *
 * <p>Which clock sees a gap depends on the OS. On Linux {@code System.nanoTime} is {@code
 * CLOCK_MONOTONIC}, which stops through a suspend, so only the wall clock moves; macOS's monotonic
 * clock stops through sleep as well. On Windows the JVM's monotonic clock keeps counting through
 * sleep, and a stopped process sees both clocks move everywhere. So a gap is either the monotonic
 * clock arriving {@link #STALL_MS} late, or the wall clock running {@link #SUSPEND_MS} ahead of the
 * monotonic one. The second is also what a wall-clock step (NTP) looks like, so it counts only when
 * the boot clock agrees with the wall clock ({@code /proc/uptime} on Linux counts suspended time and
 * never steps), or, where there is no boot clock, when it is at least {@link #UNCONFIRMED_MS}.
 *
 * <p>Anything may {@link #poll} between heartbeats, so a reader of the clocks learns of a gap before
 * it uses an elapsed time that spans it.
 */
public final class Suspensions {

    /** How often the heartbeat looks at the clocks while a job runs. */
    static final Duration PERIOD = Duration.ofSeconds(1);

    /** How late a look on the monotonic clock is a gap: the process did not run. */
    static final long STALL_MS = 10_000;

    /** How far the wall clock may run ahead of the monotonic one before that is a suspend. */
    static final long SUSPEND_MS = 5_000;

    /** As {@link #SUSPEND_MS} on a host with no boot clock to tell a suspend from a clock step. */
    static final long UNCONFIRMED_MS = 30_000;

    /** How far two clocks may differ across one interval and still agree. */
    static final long AGREE_MS = 2_000;

    /** Gaps remembered; a job reads its own within the job. */
    private static final int MAX_GAPS = 256;

    private static final Path UPTIME = Path.of("/proc/uptime");

    /**
     * One time the engine was not running, detected at {@code endedAt} (epoch ms): {@code wallMs}
     * on the wall clock, which is how long it was away, and {@code monoMs} on the monotonic clock,
     * which is what an elapsed time read on that clock grew by across it (about 0 for a suspend on
     * Linux and macOS, about {@code wallMs} for a stopped process or a suspend on Windows).
     */
    public record Gap(long endedAt, long wallMs, long monoMs) {
        /** When it began, epoch ms. */
        public long startedAt() {
            return endedAt - wallMs;
        }
    }

    /** What the engine does about a gap: {@code jobs} are the request ids that ran across it. */
    public interface Listener {
        void suspended(Gap gap, Set<Long> jobs);
    }

    private static final Suspensions SYSTEM = new Suspensions(Clock.SYSTEM, Suspensions::uptimeMillis);

    /** The engine's watch. */
    public static Suspensions system() {
        return SYSTEM;
    }

    private final Clock clock;
    private final LongSupplier bootMillis;

    /** Starts the heartbeat on a platform thread of the engine's; none until the engine gives one. */
    private volatile @Nullable Consumer<Runnable> heartbeat;

    private final Set<Long> jobs = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Listener listener;

    /** Per request, the milliseconds it ran across gaps; bounded, a run reads its total within seconds of its end. */
    private final Map<Long, Long> totals = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_GAPS;
        }
    });

    /** Gaps detected, oldest first, the first one numbered {@link #dropped}; guarded by this. */
    private final List<Gap> gaps = new ArrayList<>();

    private long dropped;

    /** Whether the clocks are being watched: a job runs; guarded by this. */
    private boolean watching;

    private boolean beating;
    private long lastWall;
    private long lastMono;

    /**
     * The boot clock less the monotonic one, in ms, at the last look that read it; {@link
     * Long#MIN_VALUE} with no boot clock. It grows by exactly the time suspended, and is read only
     * when the wall clock ran ahead, so the boot clock is not read once a second.
     */
    private long bootOffset = Long.MIN_VALUE;

    /**
     * A watch over {@code clock} and {@code bootMillis} (a clock that counts suspended time and
     * never steps, {@code -1} where there is none). It has no heartbeat until {@link #beatOn}.
     */
    Suspensions(Clock clock, LongSupplier bootMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bootMillis = Objects.requireNonNull(bootMillis, "bootMillis");
    }

    /** A watch a test drives by hand: no heartbeat, {@link #poll} looks. */
    public static Suspensions forTests(Clock clock, LongSupplier bootMillis) {
        return new Suspensions(clock, bootMillis);
    }

    /**
     * The heartbeat runs on what {@code starter} starts it on: a platform thread, so it looks on
     * time however busy the virtual-thread carriers are. Until then only {@link #poll} looks.
     */
    public void beatOn(Consumer<Runnable> starter) {
        heartbeat = Objects.requireNonNull(starter, "starter");
    }

    /** The engine's listener, told of each gap; {@code null} detaches it. */
    public synchronized void listen(@Nullable Listener l) {
        listener = l;
    }

    /** Detach {@code l} when it is still the listener; one another engine set since stays. */
    public synchronized void detach(Listener l) {
        if (listener == l) listener = null;
    }

    /** A job started: the clocks are watched until the last job ends. */
    public void jobStarted(long requestId) {
        jobs.add(requestId);
        boolean start;
        synchronized (this) {
            if (!watching) {
                watching = true;
                rebase();
            }
            start = heartbeat != null && !beating;
            if (start) beating = true;
        }
        Consumer<Runnable> starter = heartbeat;
        if (start && starter != null) starter.accept(this::beat);
    }

    /** The job {@link #jobStarted} named ended. */
    public void jobEnded(long requestId) {
        jobs.remove(requestId);
        synchronized (this) {
            if (jobs.isEmpty()) watching = false;
        }
    }

    /** Whether the heartbeat thread runs now. */
    synchronized boolean beating() {
        return beating;
    }

    private void beat() {
        try {
            while (true) {
                Thread.sleep(PERIOD.toMillis());
                synchronized (this) {
                    if (!watching) {
                        beating = false;
                        return;
                    }
                }
                poll();
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                beating = false;
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            synchronized (this) {
                beating = false;
            }
            throw e;
        }
    }

    /**
     * Look at the clocks now: a gap since the last look is recorded and told to the listeners on
     * this thread before this returns. The gap, or null when there was none.
     */
    public @Nullable Gap poll() {
        Gap gap;
        Set<Long> running;
        synchronized (this) {
            if (!watching) return null;
            long wall = clock.millis();
            long mono = clock.nanos();
            gap = classify(wall - lastWall, (mono - lastMono) / 1_000_000L, wall, mono);
            lastWall = wall;
            lastMono = mono;
            if (gap == null) return null;
            gaps.add(gap);
            if (gaps.size() > MAX_GAPS) {
                gaps.remove(0);
                dropped++;
            }
            running = Set.copyOf(jobs);
            for (long rid : running) totals.merge(rid, gap.wallMs(), Long::sum);
        }
        Listener l = listener;
        if (l != null) {
            l.suspended(gap, running);
        }
        return gap;
    }

    /** The gap the clocks moved by since the last look, or null when they moved as expected. */
    private @Nullable Gap classify(long dw, long dm, long wall, long mono) {
        long period = PERIOD.toMillis();
        long monoLate = dm - period;
        long wallLate = dw - period;
        if (monoLate >= STALL_MS) {
            // The process did not run. A clock step in the same interval leaves the monotonic reading.
            long away = Math.abs(dw - dm) <= AGREE_MS ? wallLate : monoLate;
            return new Gap(wall, away, monoLate);
        }
        if (dw - dm < SUSPEND_MS) return null;
        long before = bootOffset;
        bootOffset = bootOffset(mono);
        boolean confirmed = before != Long.MIN_VALUE && bootOffset != Long.MIN_VALUE
                ? Math.abs((bootOffset - before) - (dw - dm)) <= AGREE_MS
                : dw - dm >= UNCONFIRMED_MS;
        // Not confirmed: the wall clock stepped (NTP), nothing was suspended.
        if (!confirmed) return null;
        return new Gap(wall, wallLate, Math.max(0, monoLate));
    }

    private void rebase() {
        lastWall = clock.millis();
        lastMono = clock.nanos();
        bootOffset = bootOffset(lastMono);
    }

    /** {@link #bootOffset} at monotonic reading {@code mono}. */
    private long bootOffset(long mono) {
        long boot = bootMillis.getAsLong();
        return boot < 0 ? Long.MIN_VALUE : boot - mono / 1_000_000L;
    }

    /** Where the record of gaps stands now, after a look: {@link #since} reads the gaps after it. */
    public long cursor() {
        poll();
        synchronized (this) {
            return dropped + gaps.size();
        }
    }

    /** The gaps detected after {@code cursor}, after a look. */
    public List<Gap> since(long cursor) {
        poll();
        synchronized (this) {
            int from = (int) Math.max(0, cursor - dropped);
            return from >= gaps.size() ? List.of() : List.copyOf(gaps.subList(from, gaps.size()));
        }
    }

    /**
     * Whether a gap was detected after {@code cursor}: a watchdog whose window started at the
     * cursor restarts it, since the time it measured includes time nothing could run.
     */
    public boolean resumedSince(long cursor) {
        return !since(cursor).isEmpty();
    }

    /**
     * When the last gap detected after {@code cursor} ended, epoch ms, or {@link Long#MIN_VALUE}
     * when there was none: a watchdog measuring a silence on the wall clock measures it from no
     * earlier than this, since nothing could have spoken while the engine was not running.
     */
    public long resumedAt(long cursor) {
        List<Gap> after = since(cursor);
        return after.isEmpty() ? Long.MIN_VALUE : after.get(after.size() - 1).endedAt();
    }

    /** Milliseconds {@code requestId} ran across gaps in all; 0 when none (or long forgotten). */
    public long suspendedMillis(long requestId) {
        poll();
        Long ms = totals.get(requestId);
        return ms == null ? 0 : ms;
    }

    /**
     * Milliseconds since boot on a clock that counts suspended time and never steps: {@code
     * /proc/uptime} on Linux; {@code -1} elsewhere.
     */
    static long uptimeMillis() {
        try {
            String s = Files.readString(UPTIME, StandardCharsets.US_ASCII).strip();
            int sp = s.indexOf(' ');
            return Math.round(Double.parseDouble(sp < 0 ? s : s.substring(0, sp)) * 1000.0);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
