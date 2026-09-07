// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitMessageCheckTest {

    private static RuleSet rules(Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-agent-trailers]
                kind            = "commit"
                forbid-trailers = ["Co-Authored-By: *claude*", "Co-Authored-By: *noreply@anthropic.com*", "Generated-By: *"]
                instead         = "author commits as a human contributor"
                why             = "attribution trailers are noise in blame"
                [guards.no-wip]
                kind    = "commit"
                pattern = '(?i)^wip\\b'
                instead = "say what the commit does"
                why     = "a WIP subject is a commit nobody can bisect to"
                [guards.needs-body]
                kind    = "commit"
                require = ['\\n\\n\\S']
                instead = "add a body line saying why"
                why     = "a subject alone is a title without a story"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules();
    }

    @Test
    void a_forbidden_trailer_is_refused_with_instead_and_a_clean_message_passes(@TempDir Path root) throws Exception {
        RuleSet rules = rules(root);
        CommitMessageCheck.Result bad = CommitMessageCheck.check(
                rules, "Fix the thing\n\nBecause it was broken.\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.rules()).isEqualTo(3);
        assertThat(bad.problems()).hasSize(2); // both trailer globs match the one line
        assertThat(bad.problems().get(0))
                .startsWith(
                        "commit message:5: trailer `Co-Authored-By: Claude <noreply@anthropic.com>` matches forbid-trailers")
                .contains("Instead:  author commits as a human contributor")
                .contains("Why:      attribution trailers are noise in blame")
                .contains("Explain:  jk guard explain no-agent-trailers");
        assertThat(bad.render()).contains("2 violations").contains("the commit was refused");
        CommitMessageCheck.Result ok = CommitMessageCheck.check(
                rules, "Fix the thing\n\nBecause it was broken.\n\nCo-Authored-By: A Human <a@example.com>\n");
        assertThat(ok.ok()).as(ok.render()).isTrue();
        assertThat(ok.render()).isEqualTo("jk guard commit-msg: 3 rules · clean");
    }

    @Test
    void a_pattern_hit_and_a_missing_require_fire(@TempDir Path root) throws Exception {
        RuleSet rules = rules(root);
        CommitMessageCheck.Result r = CommitMessageCheck.check(rules, "WIP: poking\n");
        assertThat(r.problems()).hasSize(2);
        assertThat(r.problems())
                .anySatisfy(p -> assertThat(p).startsWith("commit message:1: `WIP: poking` matches `(?i)^wip\\b`"))
                .anySatisfy(p -> assertThat(p)
                        .startsWith("commit message: the message does not match required `\\n\\n\\S`")
                        .contains("Instead:  add a body line saying why"));
    }

    @Test
    void no_commit_rules_is_said_plainly(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.a]
                kind = "split-package"
                why  = "w"
                """);
        RuleSet rules = GuardRules.load(root, GuardsConfig.ABSENT).rules();
        CommitMessageCheck.Result r = CommitMessageCheck.check(rules, "anything\n");
        assertThat(r.ok()).isTrue();
        assertThat(r.render()).isEqualTo("jk guard commit-msg: no commit rules in jk-guards.toml");
    }
}
