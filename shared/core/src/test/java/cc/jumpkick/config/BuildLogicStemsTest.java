// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildLogicStemsTest {

    @Test
    void match_covers_exact_alias_and_suffix_forms() {
        assertThat(BuildLogicStems.match("before-compile")).contains("before-compile");
        assertThat(BuildLogicStems.match("before_compile")).contains("before-compile");
        assertThat(BuildLogicStems.match("Before-Compile ")).contains("before-compile");
        assertThat(BuildLogicStems.match("before-compile-collections")).contains("before-compile");
        assertThat(BuildLogicStems.match("gate-house")).contains("gate");
        assertThat(BuildLogicStems.match("gate-")).isEmpty();
        assertThat(BuildLogicStems.match("compile")).isEmpty();
        assertThat(BuildLogicStems.match("random")).isEmpty();
        assertThat(BuildLogicStems.match("")).isEmpty();
    }

    @Test
    void a_suffixed_stem_matches_the_longest_base_not_the_first_listed() {
        // No base is a prefix of another today; the contract must hold even if one ever becomes
        // one, so the answer cannot depend on table order.
        assertThat(BuildLogicStems.match("after-build-report")).contains("after-build");
    }

    @Test
    void closest_suggests_only_near_misses() {
        assertThat(BuildLogicStems.closest("befor-compile")).contains("before-compile");
        assertThat(BuildLogicStems.closest("after_resource")).contains("after-resources");
        assertThat(BuildLogicStems.closest("gaet")).contains("gate");
        assertThat(BuildLogicStems.closest("my-helpers")).isEmpty();
    }

    @Test
    void the_table_is_the_six_documented_stems() {
        assertThat(BuildLogicStems.ALL)
                .containsExactly(
                        "before-compile", "after-compile", "after-resources", "before-package", "after-build", "gate");
    }
}
