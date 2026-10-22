// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfilesEnvTest {

    @Test
    void a_child_profiles_env_replaces_its_parents_entry_of_the_same_name() {
        Profile parent = new Profile(
                "integration",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                Map.of("A", "1", "B", "1"));
        Profile child = new Profile(
                "nightly", "integration", List.of(), List.of(), List.of(), List.of(), false, false, Map.of("B", "2"));
        Profile merged = new Profiles(Map.of("integration", parent, "nightly", child)).resolve("nightly");
        assertThat(merged.env()).containsExactlyInAnyOrderEntriesOf(Map.of("A", "1", "B", "2"));
    }
}
