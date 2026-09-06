// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import java.util.List;

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
}
