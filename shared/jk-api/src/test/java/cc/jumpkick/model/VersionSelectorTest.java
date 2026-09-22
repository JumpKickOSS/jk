// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionSelectorTest {

    @Test
    void bare_version_is_exact() {
        VersionSelector s = VersionSelector.parse("2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void caret_prefix_is_caret() {
        VersionSelector s = VersionSelector.parse("^2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void equals_prefix_is_the_same_exact() {
        VersionSelector s = VersionSelector.parse("=2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void tilde_prefix_is_tilde() {
        VersionSelector s = VersionSelector.parse("~2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Tilde.class);
        assertThat(((VersionSelector.Tilde) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void range_with_comparators_is_range() {
        VersionSelector s = VersionSelector.parse(">=2.18, <3");
        assertThat(s).isInstanceOf(VersionSelector.Range.class);
        assertThat(s.raw()).isEqualTo(">=2.18, <3");
    }

    @Test
    void latest_keyword() {
        VersionSelector s = VersionSelector.parse("latest");
        assertThat(s).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void snapshot_keyword() {
        // The deliberate opt-in for pre-releases; every other floating selector is
        // stable-only.
        assertThat(VersionSelector.parse("snapshot")).isInstanceOf(VersionSelector.Snapshot.class);
        assertThat(VersionSelector.parse("SNAPSHOT")).isInstanceOf(VersionSelector.Snapshot.class);
        assertThat(VersionSelector.parse("snapshot").raw()).isEqualTo("snapshot");
    }

    @Test
    void a_version_that_merely_contains_snapshot_is_not_the_keyword() {
        // `1.0-SNAPSHOT` is a Maven version, not the selector.
        assertThat(VersionSelector.parse("1.0-SNAPSHOT")).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void a_bare_major_is_exact_not_a_floor() {
        // A floor is written `^4`; "4" is the release literally called 4.
        VersionSelector s = VersionSelector.parse("4");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("4");
        assertThat(VersionSelector.parse("^4")).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void surrounding_whitespace_is_trimmed_and_raw_is_kept() {
        VersionSelector s = VersionSelector.parse(" 2.18.2 ");
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("2.18.2");
        assertThat(s.raw()).isEqualTo(" 2.18.2 ");
    }

    @Test
    void a_maven_exact_bracket_is_an_exact_version() {
        VersionSelector exact = VersionSelector.parse("[1.2.3]");
        assertThat(exact).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) exact).version()).isEqualTo("1.2.3");
        assertThat(VersionSelector.parse("[1.0,2.0)")).isInstanceOf(VersionSelector.Range.class);
    }

    @Test
    void caret_or_tilde_of_latest_is_latest() {
        // Plugin packager coords are `^${config.version}`; when the table is `latest` that
        // interpolates to `^latest`, which must mean latest, not a caret of the word.
        assertThat(VersionSelector.parse("^latest")).isInstanceOf(VersionSelector.Latest.class);
        assertThat(VersionSelector.parse("~latest")).isInstanceOf(VersionSelector.Latest.class);
        assertThat(VersionSelector.parse("^snapshot")).isInstanceOf(VersionSelector.Snapshot.class);
    }
}
