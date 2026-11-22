// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repository's {@code docs-say-what-is} rule against the shapes it bans and the present tense it lets stand. */
class DocsSayWhatIsRuleTest {

    private static final String RULE = "docs-say-what-is";

    private static final List<String> BANNED = List.of(
            "The `usages` verb (was `callers`) lists every use.",
            "The verb was named callers.",
            "`jk snapshot` was this verb's name.",
            "The flag was renamed from `--fast`.",
            "The cache formerly lived in the home.",
            "Startup is paid once, previously on every run.",
            "It used to rebuild every module.",
            "The key used to be a string.",
            "The test JVMs saw the allow-list alone, as before 0.53.",
            "Under JumpKick 0.53.0 the test JVMs preferred IPv4.",
            "The first build after upgrading to 0.54 runs these steps once more.",
            "A tree from before this rule is never read.");

    private static final List<String> PRESENT = List.of(
            "Before 1.0 a release may move an SDK type.",
            "There is no compatibility contract before 1.0.0.",
            "The cache is used to skip work the action record proves done.",
            "A rename leaves the old name in prose, which is listed.",
            "A test that passed no longer counts once the release resets it.",
            "Run `jk build` (was it green?) and read the verdict.",
            "The verb `usages` lists every use (`jk code usages`).");

    @Test
    void every_banned_shape_bites_and_present_tense_prose_passes(@TempDir Path root) throws Exception {
        Files.copy(
                RepoRoot.file(DocsSayWhatIsRuleTest.class, GuardsPresence.RULES_FILE),
                root.resolve(GuardsPresence.RULES_FILE));
        StringBuilder page = new StringBuilder("# Page\n");
        for (String line : BANNED) page.append('\n').append(line).append('\n');
        for (String line : PRESENT) page.append('\n').append(line).append('\n');
        Files.createDirectories(root.resolve("docs/user"));
        Files.writeString(root.resolve("docs/user/page.md"), page);
        // The pages that state the rule name its shapes, so their allows match and are not stale.
        Files.createDirectories(root.resolve("docs/contributors"));
        Files.writeString(root.resolve("AGENTS.md"), "No notes such as (was `callers`).\n");
        Files.writeString(root.resolve("docs/contributors/comments.md"), "No notes such as (was `callers`).\n");

        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        List<Rule> rules = DocsCheck.rules(load.rules()).stream()
                .filter(r -> r.id().equals(RULE))
                .toList();
        assertThat(rules).as("the repository declares " + RULE).hasSize(1);
        EvalContext ctx = new EvalContext(
                        Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        RuleReport report =
                LaneRun.run(Lane.TREE, rules, ctx, Baseline.EMPTY).reports().getFirst();

        List<Integer> hit = report.fresh().stream()
                .filter(o -> "docs/user/page.md".equals(o.file()))
                .map(Observation::line)
                .distinct()
                .sorted()
                .toList();
        List<Integer> banned =
                IntStream.range(0, BANNED.size()).mapToObj(i -> 3 + 2 * i).toList();
        assertThat(hit).as(report.fresh().toString()).containsExactlyElementsOf(banned);
        assertThat(report.fresh()).as("the allowed pages that state the rule").allMatch(o -> "docs/user/page.md"
                .equals(o.file()));
    }
}
