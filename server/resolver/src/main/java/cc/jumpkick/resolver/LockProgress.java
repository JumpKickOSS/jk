// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.model.PackageId;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one lock reports about itself: the {@link ResolveObserver} budget and ticks, and the phase
 * timings recorded as host-wide priors when the lock succeeds. The budget is graph phase plus
 * materialize phase (about twelve packages per declared root, each); the denominator grows when the
 * graph outruns the estimate, and every package ticks once — live as the solver decides it, caught
 * up after the solve if the solver did not report it, and once more when its artifact lands.
 */
final class LockProgress {

    /** Where phase timings go; {@link LockTimings#record} in production, a recorder in tests. */
    interface Timings {
        void record(long graphMs, int graphPackages, long materializeMs, int materializedPackages, long totalMs);
    }

    private final ResolveObserver observer;
    private final Timings timings;
    private final Set<String> graphSeen = new LinkedHashSet<>();
    private final long lockStartNanos = System.nanoTime();
    private int estimate;
    private long graphStartNanos;
    private long graphMs;
    private long materializeStartNanos;

    LockProgress(ResolveObserver observer, Timings timings) {
        this.observer = observer;
        this.timings = timings;
    }

    /** Announce the graph phase with a budget sized from {@code declared} roots. */
    void graphPhase(int declared) {
        estimate = Math.max(10, declared * 12);
        observer.onTotal(estimate * 2);
        observer.onPhase("Resolving dependency graph…");
        graphStartNanos = System.nanoTime();
    }

    /**
     * One graph tick per display module, whether it arrives live from a solver decision or is
     * caught up after a solve. Grows the denominator when the graph outruns the initial estimate.
     */
    void graphPackage(String moduleOrKey, String version) {
        String display = displayModule(moduleOrKey);
        if (!graphSeen.add(display)) return;
        observer.onGraphPackage(display, version);
        if (graphSeen.size() > estimate) {
            observer.onTotal(graphSeen.size() * 2 + 16);
        }
    }

    /** Catch-up ticks for the packages of a finished solve that the live decisions did not report. */
    void noteGraph(Resolution resolution) {
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            graphPackage(mod.module(), mod.version());
        }
    }

    /** Distinct display modules ticked so far. */
    int graphPackages() {
        return graphSeen.size();
    }

    /** Announce the download phase with the exact remaining budget for {@code uniquePackages} rows. */
    void materializePhase(int uniquePackages) {
        graphMs = (System.nanoTime() - graphStartNanos) / 1_000_000L;
        observer.onTotal(Math.max(estimate * 2, graphSeen.size() + uniquePackages));
        observer.onPhase("Downloading " + uniquePackages + " artifacts…");
        materializeStartNanos = System.nanoTime();
    }

    void materialized(String module, String version) {
        observer.onPackage(module, version);
    }

    /** The lock succeeded with {@code rows} rows: record the phase timings. Advisory — never fails a lock. */
    void finished(int rows) {
        long materializeMs = (System.nanoTime() - materializeStartNanos) / 1_000_000L;
        long totalMs = (System.nanoTime() - lockStartNanos) / 1_000_000L;
        try {
            timings.record(graphMs, Math.max(1, graphSeen.size()), materializeMs, Math.max(1, rows), totalMs);
        } catch (RuntimeException ignored) {
            // advisory — never fail a lock over metrics I/O
        }
    }

    /** Human-facing module id: {@code group:artifact} for Maven package keys. */
    static String displayModule(String moduleOrKey) {
        if (moduleOrKey == null) return "";
        if (PackageId.isMavenPackageKey(moduleOrKey)) {
            try {
                return PackageId.parse(moduleOrKey).ga();
            } catch (RuntimeException ignored) {
                return moduleOrKey;
            }
        }
        return moduleOrKey;
    }
}
