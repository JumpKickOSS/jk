// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code test}: a guard test's verdict, read from the report its suite's run appended. The run
 * itself is the lane's business (it forks the JUnit launcher before evaluating); this evaluator
 * only maps one line to an evaluation, which is also what {@code jk guard freeze} and
 * {@code explain} need without a JVM.
 */
final class TestEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        Path moduleDir = ctx.moduleDir() == null ? ctx.root() : ctx.moduleDir();
        Path report = GuardSuites.report(BuildLayout.moduleTargetDir(ctx.root(), moduleDir));
        Map<String, Object> lines = GuardSuites.readReport(report);
        return GuardSuites.evaluate(rule, lines.get(rule.id()), ctx.module(), moduleDir);
    }
}
