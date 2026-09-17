// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Optional wall-time counters for lock/resolve hot paths. Enable with {@code
 * -Djk.resolve.profile=true} (or env {@code JK_RESOLVE_PROFILE=1}). Thread-safe accumulators for a
 * single process; call {@link #reset()} before a measured lock and {@link #report()} after — after a
 * lock that failed as much as after one that finished, since where a failed lock's time went is what
 * a reader of the report most wants to know.
 */
public final class ResolveProfile {

    private static final AtomicLong POM_BUILD_NS = new AtomicLong();
    private static final AtomicLong POM_BUILD_CALLS = new AtomicLong();
    private static final AtomicLong POM_BUILD_HITS = new AtomicLong();
    private static final AtomicLong DEPS_NS = new AtomicLong();
    private static final AtomicLong DEPS_CALLS = new AtomicLong();
    private static final AtomicLong VERSIONS_NS = new AtomicLong();
    private static final AtomicLong VERSIONS_CALLS = new AtomicLong();
    private static final AtomicLong KMP_NS = new AtomicLong();
    private static final AtomicLong KMP_CALLS = new AtomicLong();
    private static final AtomicLong SOLVE_NS = new AtomicLong();
    private static final AtomicLong SOLVE_CALLS = new AtomicLong();
    private static final AtomicLong RELATION_NS = new AtomicLong();
    private static final AtomicLong RELATION_CALLS = new AtomicLong();
    private static final AtomicLong PHASE_PREP_NS = new AtomicLong();
    private static final AtomicLong PHASE_RESOLVE_NS = new AtomicLong();
    private static final AtomicLong PHASE_POST_NS = new AtomicLong();
    private static final AtomicLong PHASE_PARTITION_NS = new AtomicLong();

    /**
     * Cached enable flag. {@link #on()} sits in the PubGrub inner loop ({@code relationTo} entry +
     * finally, millions of calls on NIA-scale solves), so it must be a plain volatile read — not a
     * Properties hashtable walk plus {@code System.getenv} per call. Production enables via
     * {@code -D}/env at JVM launch, which class init sees; tests that flip the property afterwards
     * already call {@link #reset()}, which re-reads.
     */
    private static volatile boolean on = readOn();

    private ResolveProfile() {}

    /** True when {@code -Djk.resolve.profile=true} or {@code JK_RESOLVE_PROFILE=1}. */
    public static boolean on() {
        return on;
    }

    private static boolean readOn() {
        return Boolean.getBoolean("jk.resolve.profile")
                || EnvValues.bool(System::getenv, "JK_RESOLVE_PROFILE").orElse(false);
    }

    public static void reset() {
        on = readOn();
        POM_BUILD_NS.set(0);
        POM_BUILD_CALLS.set(0);
        POM_BUILD_HITS.set(0);
        DEPS_NS.set(0);
        DEPS_CALLS.set(0);
        VERSIONS_NS.set(0);
        VERSIONS_CALLS.set(0);
        KMP_NS.set(0);
        KMP_CALLS.set(0);
        SOLVE_NS.set(0);
        SOLVE_CALLS.set(0);
        RELATION_NS.set(0);
        RELATION_CALLS.set(0);
        PHASE_PREP_NS.set(0);
        PHASE_RESOLVE_NS.set(0);
        PHASE_POST_NS.set(0);
        PHASE_PARTITION_NS.set(0);
    }

    /** Wall time for lock plan prep (git/path materialize, repo build) outside PubGrub. */
    public static void phasePrep(long nanos) {
        if (!on()) return;
        PHASE_PREP_NS.addAndGet(nanos);
    }

    /** Wall time for {@code LockOrchestrator.lock} (graph + materialize). */
    public static void phaseResolve(long nanos) {
        if (!on()) return;
        PHASE_RESOLVE_NS.addAndGet(nanos);
    }

    /** Wall time after resolve (kotlin pin, stamp, write lockfile). */
    public static void phasePost(long nanos) {
        if (!on()) return;
        PHASE_POST_NS.addAndGet(nanos);
    }

    /**
     * Wall time of the member-partition pass: every workspace member the merged answer cannot serve
     * solved again on its own. Part of {@link #phaseResolve}, reported apart so a reactor whose members
     * re-solve for minutes says so.
     */
    public static void phasePartition(long nanos) {
        if (!on()) return;
        PHASE_PARTITION_NS.addAndGet(nanos);
    }

    /** A {@link Phases} over the JVM's clock. */
    public static Phases phases() {
        return new Phases(Clock.SYSTEM);
    }

    /**
     * The phases of one lock, credited as the pipeline moves from one to the next. {@link #end} credits
     * the phase in flight, so a caller that ends the phases from a {@code finally} has the time of a
     * lock that threw attributed as well as the time of one that finished.
     */
    public static final class Phases {

        private final Clock clock;
        private @Nullable LongConsumer inFlight;
        private long startedAt;

        Phases(Clock clock) {
            this.clock = clock;
        }

        /** Ends the phase in flight, if any, and starts one credited to {@code credit}. */
        public void begin(LongConsumer credit) {
            end();
            inFlight = credit;
            startedAt = clock.nanos();
        }

        /** Credits the phase in flight with the time since it began; nothing when none is. */
        public void end() {
            LongConsumer credit = inFlight;
            if (credit == null) return;
            inFlight = null;
            credit.accept(clock.nanos() - startedAt);
        }
    }

    public static void pomBuild(long nanos, boolean cacheHit) {
        if (!on()) return;
        POM_BUILD_NS.addAndGet(nanos);
        POM_BUILD_CALLS.incrementAndGet();
        if (cacheHit) POM_BUILD_HITS.incrementAndGet();
    }

    public static void deps(long nanos) {
        if (!on()) return;
        DEPS_NS.addAndGet(nanos);
        DEPS_CALLS.incrementAndGet();
    }

    public static void versions(long nanos) {
        if (!on()) return;
        VERSIONS_NS.addAndGet(nanos);
        VERSIONS_CALLS.incrementAndGet();
    }

    public static void kmp(long nanos) {
        if (!on()) return;
        KMP_NS.addAndGet(nanos);
        KMP_CALLS.incrementAndGet();
    }

    public static void solve(long nanos) {
        if (!on()) return;
        SOLVE_NS.addAndGet(nanos);
        SOLVE_CALLS.incrementAndGet();
    }

    public static void relation(long nanos) {
        if (!on()) return;
        RELATION_NS.addAndGet(nanos);
        RELATION_CALLS.incrementAndGet();
    }

    public static String report() {
        if (!on()) return "resolve profile off";
        return "resolve-profile"
                + " pomBuild="
                + ms(POM_BUILD_NS)
                + "ms/"
                + POM_BUILD_CALLS.get()
                + "calls hits="
                + POM_BUILD_HITS.get()
                + " deps="
                + ms(DEPS_NS)
                + "ms/"
                + DEPS_CALLS.get()
                + " versions="
                + ms(VERSIONS_NS)
                + "ms/"
                + VERSIONS_CALLS.get()
                + " kmp="
                + ms(KMP_NS)
                + "ms/"
                + KMP_CALLS.get()
                + " solve="
                + ms(SOLVE_NS)
                + "ms/"
                + SOLVE_CALLS.get()
                + " relationTo="
                + ms(RELATION_NS)
                + "ms/"
                + RELATION_CALLS.get()
                + " phasePrep="
                + ms(PHASE_PREP_NS)
                + "ms phaseResolve="
                + ms(PHASE_RESOLVE_NS)
                + "ms phasePartition="
                + ms(PHASE_PARTITION_NS)
                + "ms phasePost="
                + ms(PHASE_POST_NS)
                + "ms";
    }

    private static long ms(AtomicLong nanos) {
        return nanos.get() / 1_000_000L;
    }
}
