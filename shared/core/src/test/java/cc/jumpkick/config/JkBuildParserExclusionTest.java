// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import org.junit.jupiter.api.Test;

/** {@code exclude = [...]} in a dependency's inline table names the coordinates pruned from its subtree. */
class JkBuildParserExclusionTest {

    @Test
    void an_inline_table_exclude_lists_group_artifact_and_group_wildcard_entries() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [platform-dependencies]
                bom = { group = "org.demo", name = "bom", version = "1.0" }

                [dependencies]
                schema-json = { group = "io.apicurio", name = "apicurio-registry-schema-util-json", version = "2.6.13.Final", exclude = ["io.apicurio:apicurio-common-app-components-logging", "com.github.everit-org.json-schema:*"] }
                managed = { group = "org.demo", name = "managed", exclude = ["org.demo:noise"] }
                plain = { group = "org.demo", name = "plain", version = "1.0" }
                """);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps).extracting(Dependency::library).containsExactly("schema-json", "managed", "plain");
        assertThat(deps.get(0).exclusions())
                .containsExactly(
                        "io.apicurio:apicurio-common-app-components-logging", "com.github.everit-org.json-schema:*");
        assertThat(deps.get(1).isPlatformManaged()).isTrue();
        assertThat(deps.get(1).exclusions()).containsExactly("org.demo:noise");
        assertThat(deps.get(2).exclusions()).isEmpty();
    }

    @Test
    void a_workspace_edge_carries_its_exclusions_to_the_merge() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = { workspace = true, exclude = ["org.demo:noise"] }
                """);
        Dependency dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isWorkspace()).isTrue();
        assertThat(dep.exclusions()).containsExactly("org.demo:noise");
    }

    @Test
    void a_wildcard_group_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        lib = { group = "org.demo", version = "1.0", exclude = ["*:noise"] }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("dependencies.lib.exclude")
                .hasMessageContaining("wildcard");
    }

    @Test
    void an_exclusion_needs_group_and_artifact_and_nothing_more() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        lib = { group = "org.demo", version = "1.0", exclude = ["noise"] }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("group:artifact");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        lib = { group = "org.demo", version = "1.0", exclude = ["org.demo:noise:1.0"] }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("group:artifact");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        lib = { group = "org.demo", version = "1.0", exclude = "org.demo:noise" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("array");
    }

    @Test
    void a_git_source_has_no_subtree_to_prune() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        lib = { git = "https://github.com/acme/lib", tag = "v1", exclude = ["org.demo:noise"] }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("Maven coordinate");
    }
}
