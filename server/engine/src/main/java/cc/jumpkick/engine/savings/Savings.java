// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What jk saved over a period, overall or for one project: one row of {@code jk stats}, of its
 * {@code -O jsonl} stream and of {@code GET /api/stats}, all from {@link #toMap}.
 *
 * @param scope {@link #ALL} for every project, {@link #PROJECT} for one, {@link #EPISODE} for one
 *     episode ({@link EpisodeReceipt})
 * @param projectId the project's durable id; null for {@link #ALL}, or for a directory with none
 * @param dir the project's newest checkout, or the episode's; null for {@link #ALL}
 * @param coord the project's {@code group:name} as its newest run recorded it, or null
 * @param since the period's start, epoch millis; 0 for every run the journal keeps
 * @param until the period's end, epoch millis
 * @param runs the runs that finished in the period
 * @param savedMillis build and test time the action cache avoided: the sum of each run's estimate
 *     ({@link cc.jumpkick.runtime.base.CacheBenefit}), so an estimate, not a measurement
 * @param estimatedUncachedMillis what those runs would have taken with no cache, estimated likewise
 * @param coveredSkips cache hits whose saving is backed by a measured time
 * @param estimatedSkips cache hits the estimates counted; {@code coveredSkips / estimatedSkips} is
 *     how much of {@code savedMillis} rests on measured times
 * @param stepsSkipped steps whose result came from the cache instead of running
 * @param suitesSkipped of those, test suites
 * @param testClassesNotRerun test classes left out of a re-run because they passed under the same
 *     inputs
 * @param graphCalls code-graph questions answered ({@code query}, {@code usages}, {@code read},
 *     {@code find})
 * @param refactorings refactorings applied ({@link cc.jumpkick.engine.sessions.ActionRecord#REFACTORINGS})
 * @param edits dependency and workspace edits made ({@link
 *     cc.jumpkick.engine.sessions.ActionRecord#edits})
 * @param violations the guard-rule violations a run stopped, each rule in each file once however
 *     many runs it stopped ({@link SavingsLedger#violation}); {@link #guardViolations} counts them
 * @param guardStops runs that stopped on at least one
 */
public record Savings(
        String scope,
        @Nullable String projectId,
        @Nullable String dir,
        @Nullable String coord,
        long since,
        long until,
        long runs,
        long savedMillis,
        long estimatedUncachedMillis,
        long coveredSkips,
        long estimatedSkips,
        long stepsSkipped,
        long suitesSkipped,
        long testClassesNotRerun,
        long graphCalls,
        long refactorings,
        long edits,
        Set<String> violations,
        long guardStops) {

    public static final String ALL = "all";
    public static final String PROJECT = "project";
    public static final String EPISODE = "episode";

    public Savings {
        violations = Set.copyOf(violations);
    }

    /** Nothing saved yet, in {@code scope} over {@code since}..{@code until}. */
    static Savings empty(
            String scope,
            @Nullable String projectId,
            @Nullable String dir,
            @Nullable String coord,
            long since,
            long until) {
        return new Savings(scope, projectId, dir, coord, since, until, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Set.of(), 0);
    }

    /** This row with {@code o}'s counts added to its own; its identity and period stay. */
    Savings plus(Savings o) {
        return new Savings(
                scope,
                projectId,
                dir,
                coord,
                since,
                until,
                runs + o.runs,
                savedMillis + o.savedMillis,
                estimatedUncachedMillis + o.estimatedUncachedMillis,
                coveredSkips + o.coveredSkips,
                estimatedSkips + o.estimatedSkips,
                stepsSkipped + o.stepsSkipped,
                suitesSkipped + o.suitesSkipped,
                testClassesNotRerun + o.testClassesNotRerun,
                graphCalls + o.graphCalls,
                refactorings + o.refactorings,
                edits + o.edits,
                union(violations, o.violations),
                guardStops + o.guardStops);
    }

    /** The guard-rule violations stopped: distinct rule and file pairs, not one per run. */
    public long guardViolations() {
        return violations.size();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        if (b.isEmpty() || a.containsAll(b)) return a;
        if (a.isEmpty()) return b;
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    /** Whether nothing in the period counted toward any figure. */
    public boolean nothing() {
        return runs == 0 && graphCalls == 0 && refactorings == 0 && edits == 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        if (projectId != null) m.put("projectId", projectId);
        if (dir != null) m.put("dir", dir);
        if (coord != null) m.put("coord", coord);
        m.put("since", since);
        m.put("until", until);
        m.put("runs", runs);
        m.put("savedMillis", savedMillis);
        m.put("estimatedUncachedMillis", estimatedUncachedMillis);
        m.put("coveredSkips", coveredSkips);
        m.put("estimatedSkips", estimatedSkips);
        m.put("stepsSkipped", stepsSkipped);
        m.put("suitesSkipped", suitesSkipped);
        m.put("testClassesNotRerun", testClassesNotRerun);
        m.put("graphCalls", graphCalls);
        m.put("refactorings", refactorings);
        m.put("edits", edits);
        m.put("guardViolations", guardViolations());
        m.put("guardStops", guardStops);
        return m;
    }
}
