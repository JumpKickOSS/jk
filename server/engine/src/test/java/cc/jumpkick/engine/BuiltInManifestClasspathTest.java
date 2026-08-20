// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import org.junit.jupiter.api.Test;

/**
 * Production engine jars do not contain a flattened plugin catalog. The engine installs
 * first-party tables from located worker zips ({@link BuiltInPluginJars}).
 */
class BuiltInManifestClasspathTest {

    @Test
    void install_registers_table_plugins_from_located_jars() {
        BuiltInPluginJars.install();
        assertThat(BuiltInPluginJars.locatedTablePlugins())
                .as("test-plugin-jars / -Djk.*.plugin.jar must locate table plugins")
                .isNotEmpty();
        assertThat(PluginTableRegistry.manifests().stream().map(m -> m.id()).toList())
                .as("BuiltInPluginJars.install must register located table plugins")
                .contains("spring-boot", "grails", "quarkus", "android", "protobuf", "minified");
    }
}
