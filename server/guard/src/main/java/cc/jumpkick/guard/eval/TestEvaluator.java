// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code test}: a guard test's verdict, read from the report its suite's run appended. The run
 * itself is the lane's business (it forks the JUnit launcher before evaluating); this evaluator
 * only maps one line to an evaluation, which is also what {@code jk guard freeze} and
 * {@code explain} need without a JVM. A workspace-scoped guard's sites belong to whichever member
 * compiled the class, so its line is read with the workspace's class owners.
 */
final class TestEvaluator implements Evaluator {

    /** A suite report as of one modification time. */
    private record ReportKey(Path report, long stamp) {}

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        Path moduleDir = ctx.moduleDir() == null ? ctx.root() : ctx.moduleDir();
        Path report = GuardSuites.report(BuildLayout.moduleTargetDir(moduleDir));
        // One read of the report and one class-owner map per lane run, not one per @Guard: every
        // guard of a suite reads the same report, and a workspace guard the same owners.
        long stamp = Files.exists(report) ? Files.getLastModifiedTime(report).toMillis() : -1;
        Map<String, Object> lines;
        try {
            lines = ctx.memo(new ReportKey(report, stamp), c -> {
                try {
                    return GuardSuites.readReport(report);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        SiteOwners owners = null;
        if (Evaluators.laneOf(rule) == Lane.WORKSPACE) {
            try {
                owners = ctx.memo(SiteOwners.class, c -> {
                    try {
                        return SiteOwners.of(c.root(), WorkspaceModules.of(c.root()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        return GuardSuites.evaluate(rule, lines.get(rule.id()), ctx.module(), moduleDir, owners);
    }
}
