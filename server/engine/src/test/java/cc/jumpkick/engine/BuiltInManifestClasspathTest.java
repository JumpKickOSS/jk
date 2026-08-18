// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import org.junit.jupiter.api.Test;

/**
 * The engine classpath must carry every built-in plugin manifest (JK-2149 moved them off
 * :core main, so a baking regression in {@code server/engine/build.gradle.kts} or the
 * worker conventions would otherwise degrade SILENTLY — {@code loadBuiltIns} treats
 * all-absent as the intentional native-CLI state and returns an empty registry).
 */
class BuiltInManifestClasspathTest {

    @Test
    void engine_classpath_bakes_every_built_in_manifest() {
        assertThat(PluginTableRegistry.manifests().stream().map(m -> m.id()).toList())
                .as("built-in manifests must ride the engine classpath (JK-2149)")
                .contains("spring-boot", "grails", "quarkus", "android", "protobuf", "minified", "micronaut");
    }
}
