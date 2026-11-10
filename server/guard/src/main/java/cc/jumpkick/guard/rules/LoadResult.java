// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a load produced: the rules that loaded and every problem found, collected — one load reports
 * the whole file rather than the first mistake. A result with any error must not drive a lane.
 */
public record LoadResult(RuleSet rules, List<LoadError> problems) {

    public LoadResult {
        problems = List.copyOf(problems);
    }

    public boolean hasErrors() {
        for (LoadError e : problems) if (e.isError()) return true;
        return false;
    }

    public List<LoadError> errors() {
        return problems.stream().filter(LoadError::isError).toList();
    }

    public List<LoadError> warnings() {
        return problems.stream().filter(e -> !e.isError()).toList();
    }

    /**
     * Ids of the rules held back because they ask a newer jk ({@code requires-jk}). They are still
     * declared: their baseline entries are not orphans, and a newer jk judges them.
     */
    public Set<String> held() {
        Set<String> ids = new TreeSet<>();
        for (LoadError e : problems) {
            if (e.severity() == LoadError.Severity.HELD && e.ruleId() != null) ids.add(e.ruleId());
        }
        return ids;
    }
}
