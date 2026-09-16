// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
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
                .contains(
                        "spring-boot", "grails", "quarkus", "android", "protobuf", "generator", "openapi", "minified");
    }

    /**
     * Every shelved worker jar that carries a descriptor carries its own: a jar whose root
     * {@code jk-plugin.toml} names another plugin's worker is a mis-assembled jar, and a build
     * that shelved one is red here whichever build produced it.
     */
    @Test
    void every_located_worker_jar_carries_its_own_descriptor() {
        for (BuiltInPluginJars.Located located : BuiltInPluginJars.locatedTablePlugins()) {
            PluginDescriptor descriptor = BuiltInPluginJars.describe(located, false);
            assertThat("jk-" + descriptor.id())
                    .as(located.path() + " describes plugin " + descriptor.id())
                    .isEqualTo(located.plugin().artifactId());
        }
    }
}
