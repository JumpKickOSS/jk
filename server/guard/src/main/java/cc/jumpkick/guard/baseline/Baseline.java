// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Every rule's baseline, keyed by rule id. Immutable; every change is a new instance. */
public record Baseline(Map<String, RuleBaseline> rules) {

    public static final Baseline EMPTY = new Baseline(Map.of());

    public Baseline {
        Map<String, RuleBaseline> copy = new TreeMap<>();
        rules.forEach((k, v) -> {
            if (!v.isEmpty()) copy.put(k, v);
        });
        rules = Collections.unmodifiableMap(copy);
    }

    public RuleBaseline of(String ruleId) {
        return rules.getOrDefault(ruleId, RuleBaseline.EMPTY);
    }

    public Baseline with(String ruleId, RuleBaseline rb) {
        Map<String, RuleBaseline> copy = new TreeMap<>(rules);
        if (rb.isEmpty()) copy.remove(ruleId);
        else copy.put(ruleId, rb);
        return new Baseline(copy);
    }

    public Baseline without(String ruleId) {
        return with(ruleId, RuleBaseline.EMPTY);
    }

    /** Ids present here but not in {@code liveRuleIds}: {@code rule-removed} until retired. */
    public List<String> orphans(Set<String> liveRuleIds) {
        List<String> out = new ArrayList<>();
        for (String id : rules.keySet()) if (!liveRuleIds.contains(id)) out.add(id);
        return out;
    }

    public int entryCount() {
        int n = 0;
        for (RuleBaseline rb : rules.values()) n += rb.entries().size();
        return n;
    }
}
