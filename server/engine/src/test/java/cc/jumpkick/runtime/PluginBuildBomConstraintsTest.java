// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.Pom;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool-closure BOM pins carry the owner's maven-resolver family alignment — both derivation
 * arms, not just the {@code maven-resolver.version} property. A BOM that manages api/impl without
 * the property must pin named-locks on the tool classpath exactly as it does on the lock path, or
 * a 2.x named-locks lands next to a 1.9 api and {@code NamedLockFactory.getLock} disappears.
 */
class PluginBuildBomConstraintsTest {

    @Test
    void managed_api_pin_without_the_property_pins_the_whole_family() throws IOException {
        EffectivePom bom = new EffectivePom(
                "io.quarkus.platform",
                "quarkus-bom",
                "3.38.0",
                "pom",
                Map.of(),
                List.of(),
                List.of(
                        dep("org.apache.maven.resolver", "maven-resolver-api", "1.9.24"),
                        dep("org.apache.maven.resolver", "maven-resolver-impl", "1.9.24")));

        Map<String, String> constraints = PluginBuild.bomConstraintsOf(bom, "io.quarkus.platform:quarkus-bom:3.38.0");

        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-named-locks"))
                .isEqualTo("1.9.24");
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-transport-http"))
                .isEqualTo("1.9.24");
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-api"))
                .isEqualTo("1.9.24");
    }

    @Test
    void the_property_arm_still_pins_the_family() throws IOException {
        EffectivePom bom = new EffectivePom(
                "io.quarkus",
                "quarkus-bootstrap-bom",
                "3.38.0",
                "pom",
                Map.of("maven-resolver.version", "1.9.22"),
                List.of(),
                List.of(dep("com.example", "anything", "1.0")));

        Map<String, String> constraints = PluginBuild.bomConstraintsOf(bom, "io.quarkus:quarkus-bootstrap-bom:3.38.0");

        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-named-locks"))
                .isEqualTo("1.9.22");
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-api"))
                .isEqualTo("1.9.22");
        assertThat(constraints.get("com.example:anything")).isEqualTo("1.0");
    }

    private static Pom.Dep dep(String g, String a, String v) {
        return new Pom.Dep(g, a, v, "compile", false, null, "jar", List.of());
    }
}
