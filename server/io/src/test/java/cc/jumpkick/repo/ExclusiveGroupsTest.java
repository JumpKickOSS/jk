// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExclusiveGroupsTest {

    @Test
    void exact_and_star_patterns() {
        assertThat(ExclusiveGroups.matches("com.acme", "com.acme")).isTrue();
        assertThat(ExclusiveGroups.matches("com.acme", "com.acme.util")).isFalse();
        // x.* claims subgroups only — the bare group needs its own entry (JK-1811).
        assertThat(ExclusiveGroups.matches("com.acme.*", "com.acme")).isFalse();
        assertThat(ExclusiveGroups.matches("com.acme.*", "com.acme.util")).isTrue();
        assertThat(ExclusiveGroups.matches("com.acme.*", "com.other")).isFalse();
        assertThat(ExclusiveGroups.matches("*", "anything")).isTrue();
    }

    @Test
    void claimant_indices_only_claiming_repos() {
        List<List<String>> patterns = List.of(
                List.of(), // central
                List.of("com.acme", "com.acme.*"), // internal
                List.of("org.example")); // other
        assertThat(ExclusiveGroups.claimantIndices(patterns, "com.acme.core")).containsExactly(1);
        assertThat(ExclusiveGroups.claimantIndices(patterns, "org.example")).containsExactly(2);
        assertThat(ExclusiveGroups.claimantIndices(patterns, "junit")).isEmpty();
    }

    @Test
    void any_binding() {
        assertThat(ExclusiveGroups.anyBinding(List.of(List.of(), List.of()))).isFalse();
        assertThat(ExclusiveGroups.anyBinding(List.of(List.of(), List.of("com.acme"))))
                .isTrue();
    }
}
