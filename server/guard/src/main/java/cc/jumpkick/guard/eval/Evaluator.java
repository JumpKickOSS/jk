// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;

/** One kind's evaluation. May throw: the lane turns any throwable into {@code scanner-failed}. */
public interface Evaluator {
    Evaluation evaluate(Rule rule, EvalContext ctx) throws Exception;
}
