// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *: built-ins install lazily — an unowned table gives the engine-registered fetcher one
 * chance to fetch + install the owner before the parse error, and a fetch failure surfaces its
 * cause in that error.
 */
class LazyBuiltInFetchTest {

    private static final String TABLE = "zz-lazy-fetch";

    private static final String MANIFEST = """
            [plugin]
            id      = "%s"
            table   = "%s"
            version = "1.0.0"

            [schema]
            enabled = { type = "bool", default = true }
            """.formatted(TABLE, TABLE);

    @AfterEach
    void resetFetcher() {
        PluginTableRegistry.missingBuiltInFetcher(null);
    }

    @Test
    void unowned_table_installs_via_the_registered_fetcher(@TempDir Path tmp) throws Exception {
        PluginTableRegistry.missingBuiltInFetcher(table -> {
            if (TABLE.equals(table)) {
                PluginTableRegistry.putBuiltIn(PluginDescriptors.parse(MANIFEST, "lazy-test"), null);
            }
            return null;
        });
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [%s]
                enabled = false
                """.formatted(TABLE));

        var build = JkBuildParser.parse(toml);

        assertThat(build.pluginConfig(TABLE)).isPresent();
        assertThat(build.pluginConfig(TABLE).orElseThrow().bool("enabled", true))
                .isFalse();
    }

    @Test
    void fetch_failure_detail_lands_in_the_unowned_table_error(@TempDir Path tmp) throws Exception {
        PluginTableRegistry.missingBuiltInFetcher(table -> "official repo unreachable (test)");
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [zz-never-installed]
                enabled = false
                """);

        assertThatThrownBy(() -> JkBuildParser.parse(toml))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[zz-never-installed] is not owned by any installed plugin")
                .hasMessageContaining("official repo unreachable (test)");
    }
}
