// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The exclusion grammar a {@link Dependency} accepts, and that every derived copy keeps the list. */
class DependencyExclusionTest {

    @Test
    void the_four_maven_spellings_are_accepted_and_a_partial_wildcard_is_not() {
        assertThat(Dependency.exclusion("org.demo:noise")).isEqualTo("org.demo:noise");
        assertThat(Dependency.exclusion("org.demo:*")).isEqualTo("org.demo:*");
        assertThat(Dependency.exclusion("*:noise")).isEqualTo("*:noise");
        assertThat(Dependency.exclusion("*:*")).isEqualTo("*:*");
        assertThatThrownBy(() -> Dependency.exclusion("org.*:noise")).hasMessageContaining("'*'");
        assertThatThrownBy(() -> Dependency.exclusion("org.demo:noise-*")).hasMessageContaining("'*'");
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
                        .withExclusions(List.of("org.*:noise")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
