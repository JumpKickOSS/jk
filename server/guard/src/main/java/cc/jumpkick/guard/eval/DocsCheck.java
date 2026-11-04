// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk guard docs}: the text rules whose files include Markdown, run over the tree as the tree
 * lane runs them, against the same baseline, with no build. A docs-only change is checked in seconds;
 * the full guard still runs every rule, these included.
 */
public final class DocsCheck {

    /** A docs run's verdict: the rendered red rules, how many, and how many rules ran. */
    public record Result(String text, int failures, int rules) {}

    private DocsCheck() {}

    /** The text rules that read {@code .md} files. */
    public static List<Rule> rules(RuleSet set) {
        return LaneRun.rulesFor(Lane.TREE, set, "").stream()
                .filter(r -> r.kind() == Kind.TEXT)
                .filter(r -> TextEvaluator.matchesAny(ForbidEvaluator.strings(r.table(), "files"), "docs/x.md"))
                .toList();
    }

    public static Result check(Path root, RuleSet set, Baseline baseline) {
        List<Rule> rules = rules(set);
        EvalContext ctx = new EvalContext(
                        Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(set);
        LaneRun.Result result = LaneRun.run(Lane.TREE, rules, ctx, baseline);
        StringBuilder sb = new StringBuilder();
        int red = 0;
        for (RuleReport r : result.reports()) {
            if (!r.red()) continue;
            red++;
            sb.append(GuardMessages.render(r));
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        }
        sb.append(red == 0 ? "OK" : "FAIL")
                .append(" guard docs · ")
                .append(rules.size())
                .append(" rules");
        if (red > 0) sb.append(" · ").append(red).append(" red");
        return new Result(sb.toString(), red, rules.size());
    }
}
