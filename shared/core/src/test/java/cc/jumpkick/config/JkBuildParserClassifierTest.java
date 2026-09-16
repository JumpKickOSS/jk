// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import org.junit.jupiter.api.Test;

/** {@code classifier = "…"} in a dependency's inline table selects the classified jar of the module. */
class JkBuildParserClassifierTest {

    @Test
    void an_inline_table_classifier_is_the_edge_the_solver_keys_by() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lwjgl = { group = "org.lwjgl", version = "3.3.6" }
                lwjgl-natives-linux = { group = "org.lwjgl", name = "lwjgl", version = "3.3.6", classifier = "natives-linux" }
                bom-managed = { group = "org.demo", name = "natives", classifier = "linux-x86_64" }
                """);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps).extracting(Dependency::library).containsExactly("lwjgl", "lwjgl-natives-linux", "bom-managed");
        assertThat(deps.get(0).classifier()).isNull();
        assertThat(deps.get(0).packageKey()).isEqualTo("org.lwjgl:lwjgl:jar:");
        assertThat(deps.get(1).classifier()).isEqualTo("natives-linux");
        assertThat(deps.get(1).module()).isEqualTo("org.lwjgl:lwjgl");
        assertThat(deps.get(1).packageKey()).isEqualTo("org.lwjgl:lwjgl:jar:natives-linux");
        assertThat(deps.get(2).isPlatformManaged()).isTrue();
        assertThat(deps.get(2).packageKey()).isEqualTo("org.demo:natives:jar:linux-x86_64");
    }

    @Test
    void a_classifier_needs_a_maven_coordinate_and_excludes_the_tests_kind() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [test-dependencies]
                        helpers = { group = "com.acme", version = "1.0", kind = "tests", classifier = "x" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("test-dependencies.helpers.classifier")
                .hasMessageContaining("kind = \"tests\"");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        sibling = { workspace = true, classifier = "x" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("dependencies.sibling.classifier");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        blank = { group = "com.acme", version = "1.0", classifier = " " }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("non-blank");
    }
}
