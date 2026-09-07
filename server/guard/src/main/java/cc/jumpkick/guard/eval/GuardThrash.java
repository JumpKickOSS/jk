// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thrash detection: the same fingerprint red on consecutive runs of one lane in one engine session.
 * An agent that cannot satisfy a rule loops — the loop is visible here and to nobody else — so the
 * second time a site is red the message stops saying "fix per instead" and says "stop and ask".
 *
 * <p>Engine-lifetime state, keyed by the lane's task id (which names the workspace): a red lane is
 * never a cached verdict, so consecutive runs of a lane are consecutive builds that saw it. An
 * engine restart forgets everything — a human returning is a new conversation.
 */
public final class GuardThrash {

    /** Runs a site has been red for before the message turns to stop-and-ask. */
    public static final int THRESHOLD = 2;

    private static final Map<String, Set<String>> LAST_RUN = new HashMap<>();
    private static final Map<String, Map<String, Integer>> STREAKS = new HashMap<>();

    private GuardThrash() {}

    /** The streak key of one site: rule id and fingerprint. */
    public static String key(String ruleId, String fingerprint) {
        return ruleId + "|" + fingerprint;
    }

    /**
     * Record a lane run's fresh sites; returns every site's consecutive-run count after this run
     * (1 for a site that was not red the previous time this lane ran).
     */
    public static synchronized Map<String, Integer> record(String taskId, List<RuleReport> reports) {
        Set<String> now = new HashSet<>();
        for (RuleReport r : reports) for (Observation o : r.fresh()) now.add(key(r.id(), o.key()));
        Set<String> previous = LAST_RUN.getOrDefault(taskId, Set.of());
        Map<String, Integer> streaks = STREAKS.getOrDefault(taskId, Map.of());
        Map<String, Integer> next = new HashMap<>();
        for (String k : now) next.put(k, previous.contains(k) ? streaks.getOrDefault(k, 1) + 1 : 1);
        LAST_RUN.put(taskId, now);
        STREAKS.put(taskId, next);
        return Map.copyOf(next);
    }

    /** For tests: what an engine restart does. */
    public static synchronized void reset() {
        LAST_RUN.clear();
        STREAKS.clear();
    }
}
