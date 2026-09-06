// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.model.GuardsConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every loaded rule, keyed by id, plus the source files and their digests (the part of every lane's
 * cache key that says "the rules changed").
 *
 * @param sourceDigests {@code file → sha256}, sorted by file
 */
public record RuleSet(Map<String, Rule> rules, Map<String, String> sourceDigests, GuardsConfig config) {

    public static final RuleSet EMPTY = new RuleSet(Map.of(), Map.of(), GuardsConfig.ABSENT);

    public RuleSet {
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
        sourceDigests = Collections.unmodifiableMap(new TreeMap<>(sourceDigests));
    }

    public Optional<Rule> rule(String id) {
        return Optional.ofNullable(rules.get(id));
    }

    public List<Rule> ofKind(Kind kind) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules.values()) if (r.kind() == kind) out.add(r);
        return out;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Sorted ids, the order every report and the catalog use. */
    public List<String> ids() {
        return new ArrayList<>(new TreeMap<>(rules).keySet());
    }
}
