// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * JK-1722: packaging can declare an app-dir/app-jar tree so images ship the packager layout
 * (Quarkus {@code quarkus-app/} + {@code quarkus-run.jar}) instead of a lock-derived classpath.
 */
class QuarkusPackagingAppTreeTest {

    @Test
    void packaging_app_dir_and_app_jar_parse() {
        String toml = """
                [plugin]
                id = "quarkus"
                table = "quarkus"
                version = "1.0.0"

                [packaging]
                packager = "quarkus-fast-jar"
                app-dir = "quarkus-app"
                app-jar = "quarkus-run.jar"
                exec-mode = "jar"
                self-contained = true
                """;
        PluginDescriptor d = PluginDescriptors.parse(toml, "test-quarkus.toml");
        assertThat(d.packaging()).isNotNull();
        assertThat(d.packaging().appDir()).isEqualTo("quarkus-app");
        assertThat(d.packaging().appJar()).isEqualTo("quarkus-run.jar");
        assertThat(d.packaging().selfContained()).isTrue();
    }

    @Test
    void built_in_quarkus_manifest_declares_the_same_tree() {
        PluginDescriptor d = PluginTableRegistry.byTable("quarkus").orElseThrow();
        assertThat(d.packaging().appDir()).isEqualTo("quarkus-app");
        assertThat(d.packaging().appJar()).isEqualTo("quarkus-run.jar");
    }
}
