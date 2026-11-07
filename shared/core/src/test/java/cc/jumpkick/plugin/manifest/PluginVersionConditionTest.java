// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A {@code when} version condition chooses between contributions by the table's version key. */
class PluginVersionConditionTest {

    /**
     * A version condition picks the contribution a tool's release line needs: Avro's IDL reader is
     * its own artifact from 1.12, and inside the compiler before that.
     */
    @Test
    void a_version_condition_picks_the_step_dependency_for_the_release_line() {
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id = "versioned-fixture"
                table = "versioned-fixture"

                [schema]
                version = { type = "string", default = "1.12.2" }

                [[contribute.step-dependency]]
                artifact   = "compiler"
                coordinate = "org.acme:compiler:${config.version}"
                with       = ["org.acme:idl:${config.version}"]
                transitive = true
                when       = { config = "version", at-least = "1.12" }

                [[contribute.step-dependency]]
                artifact   = "compiler"
                coordinate = "org.acme:compiler:${config.version}"
                when       = { config = "version", below = "1.12" }
                """, "versioned-fixture.toml");
        PluginTableRegistry.putBuiltIn(manifest, null);

        JkBuild current = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"

                [versioned-fixture]
                """);
        JkBuild older = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"

                [versioned-fixture]
                version = "1.11.5"
                """);

        assertThat(PluginContributions.stepDependencies(current, null, Map.of()))
                .singleElement()
                .satisfies(dep -> assertThat(dep.with()).containsExactly("org.acme:idl:1.12.2"));
        assertThat(PluginContributions.stepDependencies(older, null, Map.of()))
                .singleElement()
                .satisfies(dep -> {
                    assertThat(dep.coordinateSpec()).isEqualTo("org.acme:compiler:1.11.5");
                    assertThat(dep.with()).isEmpty();
                });
    }
}
