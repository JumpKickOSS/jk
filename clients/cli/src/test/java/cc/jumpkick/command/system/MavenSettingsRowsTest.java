// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk doctor}'s mirror rows: both sources in lookup order, each naming the file that declared it. */
class MavenSettingsRowsTest {

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void a_config_toml_mirror_and_a_refused_settings_xml_mirror_each_name_their_file() {
        MavenSettingsRows.Rows rows = MavenSettingsRows.decode(CacheInventoryAck.m2Settings(List.of(
                "mirror|corp|*,!jumpkick|https://artifactory.acme/maven/|central,google||/home/me/.jk/config.toml",
                "mirror|nexus|*|http://nexus.acme/m2/|jumpkick,central,google|it uses plain http"
                        + "|/home/me/.m2/settings.xml")));

        List<String> lines = MavenSettingsRows.render(rows, Theme.active()).stream()
                .map(MavenSettingsRowsTest::strip)
                .toList();

        assertThat(rows.mirrors())
                .extracting(MavenSettingsRows.Mirror::source)
                .containsExactly("/home/me/.jk/config.toml", "/home/me/.m2/settings.xml");
        assertThat(lines.get(0))
                .contains("central, google")
                .contains("https://artifactory.acme/maven/")
                .contains("mirror `corp`, mirrorOf `*,!jumpkick`, /home/me/.jk/config.toml");
        assertThat(lines.get(1))
                .startsWith("warn:")
                .contains("mirror `nexus`")
                .contains("refused, so what it mirrors fails to resolve: it uses plain http");
    }
}
