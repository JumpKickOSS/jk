// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfilesTest {

    @Test
    void resolve_returns_profile_with_no_inheritance() {
        Profile dev = new Profile("dev", null, List.of("-g", "-parameters"), List.of());
        Profiles profiles = new Profiles(Map.of("dev", dev));
        Profile resolved = profiles.resolve("dev");
        assertThat(resolved.javacArgs()).containsExactly("-g", "-parameters");
    }

    @Test
    void inherits_merges_javac_args_with_parent_first() {
        Profile dev = new Profile("dev", null, List.of("-g", "-parameters"), List.of());
        Profile ci = new Profile("ci", "dev", List.of("-Werror"), List.of());
        Profiles profiles = new Profiles(Map.of("dev", dev, "ci", ci));

        Profile resolved = profiles.resolve("ci");
        assertThat(resolved.javacArgs()).containsExactly("-g", "-parameters", "-Werror");
    }

    @Test
    void unknown_profile_throws() {
        Profiles profiles = Profiles.empty();
        assertThatThrownBy(() -> profiles.resolve("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void cycle_in_inheritance_throws() {
        Profile a = new Profile("a", "b", List.of(), List.of());
        Profile b = new Profile("b", "a", List.of(), List.of());
        Profiles profiles = new Profiles(Map.of("a", a, "b", b));
        assertThatThrownBy(() -> profiles.resolve("a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void auto_select_picks_ci_when_ci_env_set() {
        assertThat(Profiles.autoSelect(Map.of("CI", "true"))).isEqualTo("ci");
        assertThat(Profiles.autoSelect(Map.of("GITHUB_ACTIONS", "true"))).isEqualTo("ci");
        assertThat(Profiles.autoSelect(Map.of("GITLAB_CI", "true"))).isEqualTo("ci");
        assertThat(Profiles.autoSelect(Map.of())).isNull();
    }

    /**
     * The whole jk truth set, not just {@code true} (JK-2419). Before the sweep this reader spelled
     * its own {@code equalsIgnoreCase("true")}, so a CI image exporting {@code CI=1} — the spelling
     * every other jk reader accepts — got the default profile and none of the {@code [profile.ci]}
     * settings, silently.
     */
    @Test
    void auto_select_accepts_every_spelling_in_the_truth_set() {
        for (String on : List.of("1", "true", "TRUE", "yes", "on", " true ")) {
            assertThat(Profiles.autoSelect(Map.of("CI", on))).as("CI=%s", on).isEqualTo("ci");
        }
        for (String off : List.of("0", "false", "no", "off", "")) {
            assertThat(Profiles.autoSelect(Map.of("CI", off))).as("CI=%s", off).isNull();
        }
    }
}
