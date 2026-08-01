// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.Pom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Quarkus BOMs pin maven-resolver-api/impl but often omit named-locks; without alignment, highest
 * wins pulls 2.x named-locks next to 1.9 api and {@code @QuarkusTest} discovery fails with
 * {@code NoSuchMethodError}.
 */
class MavenResolverFamilyAlignTest {

    @Test
    void fills_named_locks_from_managed_api_pin() {
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

        Map<String, String> constraints = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        // Simulate collectBomConstraints having already applied managed deps:
        constraints.put("org.apache.maven.resolver:maven-resolver-api", "1.9.24");
        constraints.put("org.apache.maven.resolver:maven-resolver-impl", "1.9.24");
        provenance.put("org.apache.maven.resolver:maven-resolver-api", "io.quarkus.platform:quarkus-bom:3.38.0");
        provenance.put("org.apache.maven.resolver:maven-resolver-impl", "io.quarkus.platform:quarkus-bom:3.38.0");

        LockOrchestrator.alignMavenResolverFamily(
                constraints, provenance, bom, "io.quarkus.platform:quarkus-bom:3.38.0");

        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-named-locks"))
                .isEqualTo("1.9.24");
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-transport-http"))
                .isEqualTo("1.9.24");
        // Existing pins are not overwritten
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-api"))
                .isEqualTo("1.9.24");
        assertThat(provenance.get("org.apache.maven.resolver:maven-resolver-named-locks"))
                .contains("maven-resolver family");
    }

    @Test
    void uses_maven_resolver_version_property_when_present() {
        EffectivePom bom = new EffectivePom(
                "io.quarkus",
                "quarkus-bootstrap-bom",
                "3.38.0",
                "pom",
                Map.of("maven-resolver.version", "1.9.22"),
                List.of(),
                List.of());

        Map<String, String> constraints = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        LockOrchestrator.alignMavenResolverFamily(
                constraints, provenance, bom, "io.quarkus:quarkus-bootstrap-bom:3.38.0");

        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-named-locks"))
                .isEqualTo("1.9.22");
        assertThat(constraints.get("org.apache.maven.resolver:maven-resolver-api"))
                .isEqualTo("1.9.22");
    }

    @Test
    void no_op_when_bom_has_no_resolver_signal() {
        EffectivePom bom = new EffectivePom("org.example", "plain-bom", "1.0", "pom", Map.of(), List.of(), List.of());
        Map<String, String> constraints = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        LockOrchestrator.alignMavenResolverFamily(constraints, provenance, bom, "org.example:plain-bom:1.0");
        assertThat(constraints).isEmpty();
    }

    private static Pom.Dep dep(String g, String a, String v) {
        return new Pom.Dep(g, a, v, "compile", false, null, "jar", List.of());
    }
}
