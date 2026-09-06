// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One rule's slice of the baseline.
 *
 * @param population the population the rule examined when the entries were recorded, by unit
 *     ({@code classes}, {@code files}, …) — the scope-shrunk floor
 */
public record RuleBaseline(Map<String, Long> population, List<Entry> entries) {

    public static final RuleBaseline EMPTY = new RuleBaseline(Map.of(), List.of());

    public RuleBaseline {
        population = Collections.unmodifiableMap(new TreeMap<>(population));
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::key));
        entries = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return population.isEmpty() && entries.isEmpty();
    }
}
