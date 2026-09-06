// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BiteEvidenceTest {

    private static RuleSet rules(Path root) throws IOException {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-uuid]
                kind       = "forbid"
                signatures = ["java.util.UUID#randomUUID()"]
                instead    = "Ids.next()"
                why        = "one id source"

                [guards.no-todo]
                kind    = "text"
                pattern = "TODO"
                instead = "TODO(<owner>)"
                why     = "owned todos"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules();
    }

    private static void lane(Path root, String lane, String line) throws IOException {
        Path dir = Files.createDirectories(RuleSummaries.dir(root));
        Files.writeString(dir.resolve(lane + RuleSummaries.SUFFIX), line + "\n");
    }

    private static String summary(String code, String outcome, String bite) {
        return "{\"code\":\"" + code + "\",\"lane\":\"guard\",\"outcome\":\"" + outcome
                + "\",\"population\":\"classes=3\",\"fresh\":0,\"baselined\":0" + bite + ",\"ts\":1}";
    }

    @Test
    void a_module_rule_no_lane_found_evidence_for_is_missing(@TempDir Path root) throws Exception {
        RuleSet rules = rules(root);
        assertThat(BiteEvidence.missing(root, rules))
                .as("never evaluated: silence")
                .isEmpty();
        lane(root, "guard_a", summary("no-uuid", "clean", ",\"bite\":false"));
        lane(root, "guard_b", summary("no-uuid", "clean", ",\"bite\":false"));
        assertThat(BiteEvidence.missing(root, rules)).singleElement().satisfies(m -> {
            assertThat(m.rule().id()).isEqualTo("no-uuid");
            assertThat(m.note()).contains("add `owner`");
        });
        lane(root, "guard_c", summary("no-uuid", "clean", ",\"bite\":true"));
        assertThat(BiteEvidence.missing(root, rules))
                .as("one lane with evidence is enough")
                .isEmpty();
    }

    @Test
    void records_without_the_bite_field_and_red_rules_are_left_alone(@TempDir Path root) throws Exception {
        RuleSet rules = rules(root);
        lane(root, "guard_a", summary("no-uuid", "clean", ""));
        assertThat(BiteEvidence.missing(root, rules))
                .as("a record from before the bite field")
                .isEmpty();
        lane(root, "guard_a", summary("no-uuid", "owner-missing", ",\"bite\":false"));
        assertThat(BiteEvidence.missing(root, rules))
                .as("already red with its own diagnostic")
                .isEmpty();
        lane(root, "guard_a", summary("no-todo", "clean", ",\"bite\":false"));
        assertThat(BiteEvidence.missing(root, rules))
                .as("a tree-lane rule judges its own bite")
                .isEmpty();
    }
}
