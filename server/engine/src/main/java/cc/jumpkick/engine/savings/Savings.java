// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * @param renames renames applied
 * @param edits dependency and workspace edits made ({@link
 *     cc.jumpkick.engine.sessions.ActionRecord#edits})
 * @param guardViolations guard-rule violations a run stopped
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
        long renames,
        long edits,
        long guardViolations,
        long guardStops) {

    public static final String ALL = "all";
    public static final String PROJECT = "project";
    public static final String EPISODE = "episode";

    /** Nothing saved yet, in {@code scope} over {@code since}..{@code until}. */
    static Savings empty(
            String scope,
            @Nullable String projectId,
            @Nullable String dir,
            @Nullable String coord,
            long since,
            long until) {
        return new Savings(scope, projectId, dir, coord, since, until, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                renames + o.renames,
                edits + o.edits,
                guardViolations + o.guardViolations,
                guardStops + o.guardStops);
    }

    /** Whether nothing in the period counted toward any figure. */
    public boolean nothing() {
        return runs == 0 && graphCalls == 0 && renames == 0 && edits == 0;
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
        m.put("renames", renames);
        m.put("edits", edits);
        m.put("guardViolations", guardViolations);
        m.put("guardStops", guardStops);
        return m;
    }
}
