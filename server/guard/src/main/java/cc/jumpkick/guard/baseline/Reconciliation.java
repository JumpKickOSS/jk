// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One rule's observations against its baseline.
 *
 * <p>Sites: an observed fingerprint with an entry is {@code baselined}; without one it is {@code
 * fresh} (red); an entry nobody observed is {@code stale} and is dropped on tightening. Metrics: a
 * unit at or under its entry is baselined and the entry lowers to the measured value; over it is
 * fresh; a unit with no entry and no cap breach is nothing. Population: recorded when the rule ran
 * clean-or-baselined; a run examining under 80 % of it is {@code scope-shrunk} and the baseline is
 * left alone — a shrink is a question, not a fact.
 */
public record Reconciliation(
        String ruleId,
        List<Observation> fresh,
        List<Observation> baselined,
        List<Entry> stale,
        RuleBaseline tightened,
        boolean tighteningNeeded,
        @Nullable String scopeShrunk) {

    static final double SCOPE_FLOOR = 0.8;

    public static Reconciliation of(
            String ruleId, RuleBaseline before, List<Observation> observed, Map<String, Long> population) {
        String shrunk = scopeShrunk(before.population(), population);
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : before.entries()) byKey.put(e.key(), e);
        List<Observation> fresh = new ArrayList<>();
        List<Observation> baselined = new ArrayList<>();
        List<Entry> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Observation o : observed) {
            Entry e = byKey.get(o.key());
            seen.add(o.key());
            if (e == null) {
                fresh.add(o);
            } else if (o.isMetric() && e instanceof Entry.Metric m) {
                double v = o.value() == null ? 0 : o.value();
                if (v > m.value()) {
                    fresh.add(o);
                    kept.add(m);
                } else {
                    baselined.add(o);
                    kept.add(v < m.value() ? new Entry.Metric(m.unit(), v, m.reason()) : m);
                }
            } else {
                baselined.add(o);
                kept.add(e);
            }
        }
        List<Entry> stale = new ArrayList<>();
        for (Entry e : before.entries()) if (!seen.contains(e.key())) stale.add(e);
        Map<String, Long> newPopulation = shrunk == null ? population : before.population();
        RuleBaseline after = new RuleBaseline(newPopulation, kept);
        boolean tightening = shrunk == null
                && (!stale.isEmpty()
                        || !after.entries().equals(before.entries())
                        || !after.population().equals(before.population()));
        return new Reconciliation(ruleId, fresh, baselined, stale, after, tightening, shrunk);
    }

    private static @Nullable String scopeShrunk(Map<String, Long> recorded, Map<String, Long> now) {
        for (var e : recorded.entrySet()) {
            long was = e.getValue();
            long is = now.getOrDefault(e.getKey(), 0L);
            if (was > 0 && is < Math.ceil(was * SCOPE_FLOOR)) return e.getKey() + ": " + was + " → " + is;
        }
        return null;
    }

    /** The baseline grown by every fresh observation, each with {@code reason}: what {@code freeze} writes. */
    public RuleBaseline frozen(String reason) {
        List<Entry> entries = new ArrayList<>(tightened.entries());
        for (Observation o : fresh) {
            entries.add(
                    o.isMetric()
                            ? new Entry.Metric(o.key(), o.value() == null ? 0 : o.value(), reason)
                            : new Entry.Site(o.key(), reason));
        }
        return new RuleBaseline(new TreeMap<>(tightened.population()), entries);
    }

    public boolean red() {
        return !fresh.isEmpty() || scopeShrunk != null;
    }
}
