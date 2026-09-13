// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One rule's slice of the baseline. A rule the module lanes run is reconciled one module at a
 * time, so its populations and entries are kept per lane ({@code ""} for a rule one lane owns):
 * lane {@code shared/host} tightening its slice leaves {@code clients/cli}'s alone.
 *
 * @param populations the population the rule examined when the entries were recorded, by lane and
 *     then by unit ({@code classes}, {@code files}, …) — the scope-shrunk floor
 * @param entries the tolerated violations, every lane's
 * @param scopeReasons by lane, the reason a human gave when a smaller population was accepted as
 *     the floor ({@code jk guard freeze --accept-scope}); a lane with no population carries none
 */
public record RuleBaseline(
        Map<String, Map<String, Long>> populations, List<Entry> entries, Map<String, String> scopeReasons) {
    public static final RuleBaseline EMPTY = new RuleBaseline(Map.of(), List.of());

    public RuleBaseline {
        Map<String, Map<String, Long>> pops = new TreeMap<>();
        populations.forEach((lane, p) -> {
            if (!p.isEmpty()) pops.put(lane, Collections.unmodifiableMap(new TreeMap<>(p)));
        });
        populations = Collections.unmodifiableMap(pops);
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((Entry e) -> e.in()).thenComparing(Entry::key));
        entries = List.copyOf(sorted);
        Map<String, String> reasons = new TreeMap<>();
        scopeReasons.forEach((lane, r) -> {
            if (pops.containsKey(lane) && !r.isEmpty()) reasons.put(lane, r);
        });
        scopeReasons = Collections.unmodifiableMap(reasons);
    }

    public RuleBaseline(Map<String, Map<String, Long>> populations, List<Entry> entries) {
        this(populations, entries, Map.of());
    }

    /** A rule one lane owns: its population and entries under {@code ""}. */
    public static RuleBaseline of(Map<String, Long> population, List<Entry> entries) {
        return new RuleBaseline(population.isEmpty() ? Map.of() : Map.of("", population), entries);
    }

    public boolean isEmpty() {
        return populations.isEmpty() && entries.isEmpty();
    }

    /** The whole-rule lane's population. */
    public Map<String, Long> population() {
        return population("");
    }

    public Map<String, Long> population(String lane) {
        return populations.getOrDefault(lane, Map.of());
    }

    /** The reason lane {@code lane}'s population was accepted as a smaller floor, or {@code null}. */
    public @Nullable String scopeReason(String lane) {
        return scopeReasons.get(lane);
    }

    /** The entries lane {@code lane} owns. */
    public List<Entry> entries(String lane) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.in().equals(lane)) out.add(e);
        return out;
    }

    /** This baseline with lane {@code lane}'s slice replaced; every other lane untouched. */
    public RuleBaseline withLane(String lane, Map<String, Long> population, List<Entry> laneEntries) {
        Map<String, Map<String, Long>> pops = new TreeMap<>(populations);
        if (population.isEmpty()) pops.remove(lane);
        else pops.put(lane, population);
        List<Entry> all = new ArrayList<>();
        for (Entry e : entries) if (!e.in().equals(lane)) all.add(e);
        for (Entry e : laneEntries) all.add(e.in(lane));
        return new RuleBaseline(pops, all, scopeReasons);
    }

    /** This baseline with lane {@code lane}'s population carrying {@code reason} for the floor it records. */
    public RuleBaseline withScopeReason(String lane, String reason) {
        Map<String, String> reasons = new TreeMap<>(scopeReasons);
        reasons.put(lane, reason);
        return new RuleBaseline(populations, entries, reasons);
    }
}
