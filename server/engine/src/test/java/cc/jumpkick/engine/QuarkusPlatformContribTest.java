// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [quarkus]} contributes {@code quarkus-bom} at parse time. Runs in the engine JVM and
 * registers the plugin from its self-describing jar — the same path {@code EngineMain} uses.
 */
class QuarkusPlatformContribTest {

    @BeforeAll
    static void installBuiltIns() {
        BuiltInPluginJars.install();
    }

    @Test
    void quarkus_table_contributes_platform_bom(@TempDir Path tmp) throws Exception {
        assertThat(PluginTableRegistry.byTable("quarkus"))
                .as(
                        "quarkus plugin jar was not located; set -D%s or [build] test-plugin-jars = [\"quarkus\"]",
                        PluginJar.QUARKUS.jarProperty())
                .isPresent();

        Files.writeString(tmp.resolve("jk.toml"), """
            name = "q"
            group = "g"
            version = "0.1.0"
            jdk = 25

            [quarkus]
            version = "3.38.0"

            [dependencies]
            quarkus-arc = { group = "io.quarkus", name = "quarkus-arc" }
            """);
        JkBuild b = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(b.pluginConfigs()).containsKey("quarkus");
        assertThat(b.dependencies().of(Scope.PLATFORM))
                .extracting(d -> d.module())
                .anyMatch(m -> m.contains("quarkus-bom"));
    }
}
