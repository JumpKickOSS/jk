// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The exclusion grammar a {@link Dependency} accepts, and that every derived copy keeps the list. */
class DependencyExclusionTest {

    @Test
    void group_artifact_and_group_wildcard_are_the_two_spellings() {
        assertThat(Dependency.exclusion("org.demo:noise")).isEqualTo("org.demo:noise");
        assertThat(Dependency.exclusion("org.demo:*")).isEqualTo("org.demo:*");
        assertThatThrownBy(() -> Dependency.exclusion("*:noise")).hasMessageContaining("wildcard");
        assertThatThrownBy(() -> Dependency.exclusion("*:*")).hasMessageContaining("wildcard");
        assertThatThrownBy(() -> Dependency.exclusion("noise")).hasMessageContaining("group:artifact");
        assertThatThrownBy(() -> Dependency.exclusion("org.demo:")).hasMessageContaining("group:artifact");
        assertThatThrownBy(() -> Dependency.exclusion("org.demo:noise:1.0")).hasMessageContaining("group:artifact");
    }

    @Test
    void derived_copies_keep_the_exclusions() {
        Dependency d = Dependency.of("lib", "org.demo:lib", VersionSelector.parse("1.0"))
                .withExclusions(List.of("org.demo:noise", "org.acme:*"));
        assertThat(d.exclusions()).containsExactly("org.demo:noise", "org.acme:*");
        assertThat(d.withOptional(true).exclusions()).isEqualTo(d.exclusions());
        assertThat(d.withKind(DependencyKind.TESTS).exclusions()).isEqualTo(d.exclusions());
        assertThat(d.withClassifier("linux").exclusions()).isEqualTo(d.exclusions());
        assertThat(d.withFixtures(true).exclusions()).isEqualTo(d.exclusions());
        assertThat(d.withFeatures(List.of("x"), false).exclusions()).isEqualTo(d.exclusions());
        assertThat(Dependency.of("lib", "org.demo:lib", VersionSelector.parse("1.0"))
                        .exclusions())
                .isEmpty();
    }

    @Test
    void the_record_refuses_an_entry_the_grammar_refuses() {
        assertThatThrownBy(() -> Dependency.of("lib", "org.demo:lib", VersionSelector.parse("1.0"))
                        .withExclusions(List.of("*:noise")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
