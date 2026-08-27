// SPDX-License-Identifier: Apache-2.0

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateKeysTest {

    @Test
    fun joins_a_module_task_path_to_its_results_directory_key() {
        assertThat(GateKeys.moduleTier(":toolchain-jdk:test")).isEqualTo("toolchain-jdk/test")
        assertThat(GateKeys.moduleTier(":cli:integrationTest")).isEqualTo("cli/integrationTest")
        assertThat(GateKeys.moduleTier(":core:test")).isEqualTo("core/test")
    }

    @Test
    fun a_root_level_task_keeps_its_own_path_rather_than_inventing_a_module() {
        // `:test` has no module segment. Returning it unchanged means the lookup misses and the
        // report says "no TEST-*.xml found" — the alternative is silently keying on some other
        // module's results, which is the bug this keying replaced.
        assertThat(GateKeys.moduleTier(":test")).isEqualTo(":test")
    }

    @Test
    fun a_nested_project_path_uses_the_leaf_module_not_the_top_of_the_tree() {
        assertThat(GateKeys.moduleTier(":plugins:publisher:test")).isEqualTo("publisher/test")
    }
}
