// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import org.junit.jupiter.api.Test;

/**
 * Engine <em>tests</em> still bake plugin fixtures on the test classpath so unit tests
 * can parse {@code [spring-boot]} without locating worker jars. Production engine jars
 * do not contain the flattened catalog ({@code BuiltInPluginJars} reads each plugin zip).
 */
class BuiltInManifestClasspathTest {

    @Test
    void engine_test_classpath_has_built_in_fixtures() {
        assertThat(PluginTableRegistry.manifests().stream().map(m -> m.id()).toList())
                .as("built-in fixtures must ride the engine test classpath")
                .contains("spring-boot", "grails", "quarkus", "android", "protobuf", "minified", "micronaut");
    }
}
