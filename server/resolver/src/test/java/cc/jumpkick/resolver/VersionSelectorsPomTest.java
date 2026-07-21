// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolver.pubgrub.VersionSet;
import org.junit.jupiter.api.Test;

class VersionSelectorsPomTest {

    @Test
    void bare_pom_version_is_at_least() {
        VersionSet set = VersionSelectors.constraintFromPomVersion("1.2.3");
        assertThat(set.contains("1.2.3")).isTrue();
        assertThat(set.contains("2.0.0")).isTrue();
        assertThat(set.contains("1.2.2")).isFalse();
    }

    @Test
    void maven_bracket_range() {
        VersionSet set = VersionSelectors.constraintFromPomVersion("[1.0,2.0)");
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.9")).isTrue();
        assertThat(set.contains("2.0")).isFalse();
        assertThat(set.contains("0.9")).isFalse();
    }

    @Test
    void multi_maven_range_is_union() {
        VersionSet set = VersionSelectors.constraintFromPomVersion("[1.0,2.0),[3.0,4.0]");
        assertThat(set.contains("1.5")).isTrue();
        assertThat(set.contains("2.5")).isFalse();
        assertThat(set.contains("3.0")).isTrue();
        assertThat(set.contains("4.0")).isTrue();
        assertThat(set.contains("4.1")).isFalse();
    }

    @Test
    void comparator_list() {
        VersionSet set = VersionSelectors.constraintFromPomVersion(">=1.0, <2.0");
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.9")).isTrue();
        assertThat(set.contains("2.0")).isFalse();
    }

    @Test
    void looksLikeMavenRange() {
        assertThat(VersionSelectors.looksLikeMavenRange("[1.0,2.0)")).isTrue();
        assertThat(VersionSelectors.looksLikeMavenRange(">=1.0")).isTrue();
        assertThat(VersionSelectors.looksLikeMavenRange("1.2.3")).isFalse();
        assertThat(VersionSelectors.looksLikeMavenRange("1.2.3-SNAPSHOT")).isFalse();
    }
}
