// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.guard.schema.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The skill's guards topic cannot lag the code: every kind the loader knows, and every command an
 * agent must run or stop on, is named there.
 */
class ManualGuardsPageTest {

    @Test
    void every_kind_is_named_on_the_topic() {
        String page = Objects.requireNonNull(JkSkill.topic("guards"));
        for (Kind k : Kind.values()) {
            assertThat(page).as("kind " + k.id()).containsPattern("(?<![a-z-])" + k.id() + "(?![a-z-])");
        }
    }

    @Test
    void every_guard_subcommand_and_outcome_an_agent_must_stop_on_is_named() {
        String page = Objects.requireNonNull(JkSkill.topic("guards"));
        for (String sub : List.of("jk guard explain", "jk guard freeze"))
            assertThat(page).contains(sub);
        for (String outcome : List.of("blind", "owner-missing", "stale-allow", "no-bite", "scanner-failed")) {
            assertThat(page).contains("`" + outcome + "`");
        }
        assertThat(page)
                .contains("--schema <kind>")
                .contains("--schema guard-test")
                .contains("run(kind=guard)");
        assertThat(page.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1_200);
    }
}
