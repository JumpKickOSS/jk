// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional wall-time counters for lock/resolve hot paths. Enable with {@code
 * -Djk.resolve.profile=true} (or env {@code JK_RESOLVE_PROFILE=1}). Thread-safe accumulators for a
 * single process; call {@link #reset()} before a measured lock and {@link #report()} after.
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

    private ResolveProfile() {}

    /** True when {@code -Djk.resolve.profile=true} or {@code JK_RESOLVE_PROFILE=1}. */
    public static boolean on() {
        if (Boolean.getBoolean("jk.resolve.profile")) return true;
        String env = System.getenv("JK_RESOLVE_PROFILE");
        return env != null && (env.equals("1") || env.equalsIgnoreCase("true"));
    }

    public static void reset() {
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
                + RELATION_CALLS.get();
    }

    private static long ms(AtomicLong nanos) {
        return nanos.get() / 1_000_000L;
    }
}
