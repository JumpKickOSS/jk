// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusPlatformContribTest {

    @Test
    void quarkus_table_contributes_platform_bom(@TempDir Path tmp) throws Exception {
        assertThat(PluginTableRegistry.manifests().stream().map(m -> m.id()).toList())
                .as("built-in manifests must include quarkus")
                .contains("quarkus");

        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                name = "q"
                group = "g"
                version = "0.1.0"
                jdk = 25

                [quarkus]
                version = "3.28.5"

                [dependencies]
                quarkus-arc = { group = "io.quarkus", name = "quarkus-arc" }
                """);
        JkBuild b = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(b.pluginConfigs()).containsKey("quarkus");
        assertThat(b.dependencies().of(Scope.PLATFORM))
                .extracting(d -> d.module())
                .anyMatch(m -> m.contains("quarkus-bom"));
        System.out.println("platform deps: " + b.dependencies().of(Scope.PLATFORM));
        System.out.println("main deps: " + b.dependencies().of(Scope.MAIN));
    }
}
