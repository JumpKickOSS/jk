// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfilesTagMergeTest {

    @Test
    void resolve_child_replaces_parent_exclude_tags_when_child_sets_key() {
        Profile parent = new Profile(
                "dev", null, List.of(), List.of(), List.of(), List.of("slow"), false, true);
        Profile child = new Profile(
                "ci", "dev", List.of(), List.of(), List.of("smoke"), List.of("bench"), true, true);
        Profiles profiles = new Profiles(Map.of("dev", parent, "ci", child));
        Profile resolved = profiles.resolve("ci");
        assertThat(resolved.excludeTags()).containsExactly("bench");
        assertThat(resolved.includeTags()).containsExactly("smoke");
        assertThat(resolved.excludeTagsSet()).isTrue();
        assertThat(resolved.includeTagsSet()).isTrue();
    }

    @Test
    void resolve_inherits_parent_tags_when_child_omits_keys() {
        Profile parent = new Profile(
                "dev", null, List.of(), List.of(), List.of(), List.of("slow", "bench"), false, true);
        Profile child = new Profile("ci", "dev", List.of("-Werror"), List.of());
        Profiles profiles = new Profiles(Map.of("dev", parent, "ci", child));
        Profile resolved = profiles.resolve("ci");
        assertThat(resolved.excludeTags()).containsExactly("slow", "bench");
        assertThat(resolved.excludeTagsSet()).isTrue();
        assertThat(resolved.includeTagsSet()).isFalse();
        assertThat(resolved.javacArgs()).containsExactly("-Werror");
    }

    @Test
    void resolve_child_empty_exclude_clears_parent_excludes() {
        Profile parent = new Profile(
                "dev", null, List.of(), List.of(), List.of(), List.of("slow"), false, true);
        Profile child = new Profile("ci", "dev", List.of(), List.of(), List.of(), List.of(), false, true);
        Profiles profiles = new Profiles(Map.of("dev", parent, "ci", child));
        Profile resolved = profiles.resolve("ci");
        assertThat(resolved.excludeTags()).isEmpty();
        assertThat(resolved.excludeTagsSet()).isTrue();
    }
}
