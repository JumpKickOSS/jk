// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.release.ReleaseVersion.Segment;
import org.junit.jupiter.api.Test;

class ReleaseVersionTest {

    @Test
    void each_segment_moves_and_resets_the_ones_below() {
        assertThat(ReleaseVersion.next("1.4.7", Segment.PATCH)).isEqualTo("1.4.8");
        assertThat(ReleaseVersion.next("1.4.7", Segment.MINOR)).isEqualTo("1.5.0");
        assertThat(ReleaseVersion.next("1.4.7", Segment.MAJOR)).isEqualTo("2.0.0");
    }

    @Test
    void a_snapshot_is_refused_with_the_release_it_stands_for() {
        assertThatThrownBy(() -> ReleaseVersion.requireReleasable("1.4.7-SNAPSHOT"))
                .hasMessageContaining("is a snapshot")
                .hasMessageContaining("write `1.4.7`");
    }

    @Test
    void a_pre_release_or_a_short_version_is_refused() {
        assertThatThrownBy(() -> ReleaseVersion.next("1.4.7-rc1", Segment.PATCH))
                .hasMessageContaining("MAJOR.MINOR.PATCH");
        assertThatThrownBy(() -> ReleaseVersion.next("1.4", Segment.PATCH)).hasMessageContaining("MAJOR.MINOR.PATCH");
    }

    @Test
    void the_tag_template_names_the_version() {
        assertThat(ReleaseTag.render(ReleaseTag.DEFAULT_TEMPLATE, "1.4.7")).isEqualTo("v1.4.7");
        assertThat(ReleaseTag.render("widget-{version}", "1.4.7")).isEqualTo("widget-1.4.7");
    }

    @Test
    void the_rewrite_keeps_comments_spacing_and_key_order() {
        String manifest = """
                # the widget
                name    = "widget"
                version = "1.4.7"   # next release
                group   = "com.example"

                [dependencies]
                version = "9.9.9"
                """;

        assertThat(ReleaseVersion.declared(manifest)).contains("1.4.7");
        assertThat(ReleaseVersion.rewrite(manifest, "1.4.8"))
                .isEqualTo(manifest.replace(
                        "version = \"1.4.7\"   # next release", "version = \"1.4.8\"   # next release"));
    }

    @Test
    void an_inherited_version_has_nothing_to_rewrite() {
        String manifest = "name = \"widget\"\nversion.workspace = true\n";

        assertThat(ReleaseVersion.declared(manifest)).isEmpty();
        assertThatThrownBy(() -> ReleaseVersion.rewrite(manifest, "1.0.1")).hasMessageContaining("no `version");
    }
}
