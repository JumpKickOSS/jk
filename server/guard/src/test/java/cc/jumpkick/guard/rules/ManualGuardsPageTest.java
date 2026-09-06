// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.JkManual;
import cc.jumpkick.guard.schema.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The manual's Guards page cannot lag the code: every kind the loader knows and every {@code jk
 * guard} subcommand is named on it, so an agent reading only the playbook can author and act.
 */
class ManualGuardsPageTest {

    private static String guardsPage() {
        String md = JkManual.markdown();
        int start = md.indexOf("## Guards (house rules)");
        assertThat(start).as("the manual has a Guards page").isPositive();
        return md.substring(start, md.indexOf("## More documentation", start));
    }

    @Test
    void every_kind_is_named_on_the_page() {
        String page = guardsPage();
        for (Kind k : Kind.values()) {
            assertThat(page).as("kind " + k.id()).containsPattern("(?<![a-z-])" + k.id() + "(?![a-z-])");
        }
    }

    @Test
    void every_guard_subcommand_and_outcome_an_agent_must_stop_on_is_named() {
        String page = guardsPage();
        for (String sub : List.of("jk guard explain", "jk guard freeze"))
            assertThat(page).contains(sub);
        for (String outcome : List.of("blind", "owner-missing", "stale-allow", "no-bite", "scanner-failed")) {
            assertThat(page).contains("`" + outcome + "`");
        }
        assertThat(page)
                .contains("--schema <kind>")
                .contains("--schema guard-test")
                .contains("jk_run kind=guard");
    }
}
