// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfilesTagMergeTest {

    @Test
    void resolve_merges_parent_and_child_exclude_tags() {
        Profile parent = new Profile("dev", null, List.of(), List.of(), List.of(), List.of("slow"));
        Profile child = new Profile("ci", "dev", List.of(), List.of(), List.of("smoke"), List.of("bench"));
        Profiles profiles = new Profiles(Map.of("dev", parent, "ci", child));
        Profile resolved = profiles.resolve("ci");
        assertThat(resolved.excludeTags()).containsExactly("slow", "bench");
        assertThat(resolved.includeTags()).containsExactly("smoke");
    }
}
