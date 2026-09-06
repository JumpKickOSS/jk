// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import java.util.List;
import java.util.Map;

/**
 * An evaluator that runs every rule of its kind in one pass — the text lane reads each file once
 * and applies every text rule to it, rather than reading the tree once per rule. Results are keyed
 * by rule id; a rule missing from the map is reported as {@code scanner-failed}.
 */
public interface BatchEvaluator extends Evaluator {

    Map<String, Evaluation> evaluateAll(List<Rule> rules, EvalContext ctx) throws Exception;

    @Override
    default Evaluation evaluate(Rule rule, EvalContext ctx) throws Exception {
        Evaluation e = evaluateAll(List.of(rule), ctx).get(rule.id());
        return e == null ? Evaluation.failed("no result for " + rule.id()) : e;
    }
}
