// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModuleSelectorsTest {

    @Test
    void any_selector_counts_the_wip_flag_alone() {
        // jk native --affected shipped ignoring the flag because a hand-rolled guard omitted
        // the wip term — the shared predicate is the regression fence.
        assertThat(ModuleSelectors.anySelector(null, null, true)).isTrue();
        assertThat(ModuleSelectors.anySelector(null, null, false)).isFalse();
        assertThat(ModuleSelectors.anySelector(" ", " ", false)).isFalse();
        assertThat(ModuleSelectors.anySelector("api", null, false)).isTrue();
        assertThat(ModuleSelectors.anySelector(null, "origin/main", false)).isTrue();
    }

    @Test
    void tokens_carry_the_wip_token_or_the_ref() {
        assertThat(ModuleSelectors.tokens(null, null, true)).containsExactly(ModuleSelectors.WIP_TOKEN);
        assertThat(ModuleSelectors.tokens("api, worker", "origin/main", false))
                .containsExactly("api", "worker", "affected:origin/main");
        assertThat(ModuleSelectors.tokens(null, null, false)).isEmpty();
    }

    @Test
    void both_selectors_is_a_config_error_only_when_both_are_set() {
        assertThat(ModuleSelectors.bothSelectors(true, "origin/main")).isTrue();
        assertThat(ModuleSelectors.bothSelectors(true, " ")).isFalse();
        assertThat(ModuleSelectors.bothSelectors(false, "origin/main")).isFalse();
    }
}
