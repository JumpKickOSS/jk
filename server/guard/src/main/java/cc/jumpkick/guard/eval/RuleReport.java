// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.rules.Rule;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One rule after evaluation and reconciliation. {@code reconciliation} is present when the
 * evaluator produced a population (clean or violations); the other outcomes carry only a note.
 */
public record RuleReport(
        Rule rule,
        Outcome outcome,
        Evaluation evaluation,
        @Nullable Reconciliation reconciliation,
        String note) {

    public List<Observation> fresh() {
        return reconciliation == null ? List.of() : reconciliation.fresh();
    }

    public List<Observation> baselined() {
        return reconciliation == null ? List.of() : reconciliation.baselined();
    }

    public boolean red() {
        return outcome.red(!fresh().isEmpty());
    }

    public String id() {
        return rule.id();
    }
}
