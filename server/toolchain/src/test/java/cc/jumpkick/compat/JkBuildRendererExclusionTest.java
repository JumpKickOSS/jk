// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A dependency's exclusions render as {@code exclude = [...]} on its inline table and parse back. */
class JkBuildRendererExclusionTest {

    @Test
    void exclude_round_trips_on_versioned_managed_and_workspace_entries() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency(
                                        "io.apicurio:apicurio-registry-schema-util-json",
                                        VersionSelector.parse("2.6.13.Final"))
                                .withExclusions(List.of(
                                        "io.apicurio:apicurio-common-app-components-logging",
                                        "com.github.everit-org.json-schema:*")),
                        Dependency.platformManaged("managed", "org.demo:managed")
                                .withExclusions(List.of("org.demo:noise")),
                        Dependency.workspace("shared").withExclusions(List.of("org.demo:noise"))));
        JkBuild model = JkBuild.builder(
                        Project.builder("com.example", "widget", "1.0.0").build())
                .dependencies(new JkBuild.Dependencies(byScope))
                .build();

        String out = JkBuildRenderer.render(model);
        assertThat(out)
                .contains("apicurio-registry-schema-util-json = { group = \"io.apicurio\", version = \"2.6.13.Final\","
                        + " exclude = [\"io.apicurio:apicurio-common-app-components-logging\","
                        + " \"com.github.everit-org.json-schema:*\"] }")
                .contains("managed = { group = \"org.demo\", exclude = [\"org.demo:noise\"] }")
                .contains("shared = { workspace = true, exclude = [\"org.demo:noise\"] }");

        List<Dependency> reparsed = JkBuildParser.parse(out).dependencies().of(Scope.MAIN);
        assertThat(reparsed)
                .extracting(Dependency::exclusions)
                .containsExactly(
                        List.of(
                                "io.apicurio:apicurio-common-app-components-logging",
                                "com.github.everit-org.json-schema:*"),
                        List.of("org.demo:noise"),
                        List.of("org.demo:noise"));
    }
}
