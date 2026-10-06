// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The merged root is the root as written with the folded dependencies and joined repositories:
 * every other table of the root manifest survives the merge, whatever it is.
 */
class WorkspaceMergeRootTablesTest {

    private static final Set<String> FOLDED = Set.of("dependencies", "repositories");

    @Test
    void every_root_table_but_dependencies_and_repositories_survives_a_merge() throws Exception {
        JkBuild root = JkBuildParser.parse("""
                group = "com.example"
                name = "root"
                version = "1.0.0"

                [workspace]
                modules = ["a"]

                [library]
                assembly = true

                [publish]
                url = "https://example.com/root"

                [image]
                registry = "ghcr.io/acme"
                ports = [8080]

                [format]
                java = "palantir"

                [install]
                product-lib = "jk-engine"

                [manifest]
                Built-By = "jk"
                """);
        JkBuild member = JkBuildParser.parse("""
                group = "com.example"
                name = "a"
                version = "1.0.0"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.4.8-jre" }
                """);
        assertThat(root.publish()).isNotNull();
        assertThat(root.installOpt()).isPresent();
        assertThat(root.manifest()).containsKey("Built-By");

        JkBuild merged = WorkspaceMerge.merge(root, List.of(member));

        assertThat(merged.dependencies().of(Scope.MAIN))
                .anyMatch(d -> d.module().equals("com.google.guava:guava"));
        for (RecordComponent c : JkBuild.class.getRecordComponents()) {
            if (FOLDED.contains(c.getName())) continue;
            assertThat(c.getAccessor().invoke(merged))
                    .as(c.getName())
                    .isEqualTo(c.getAccessor().invoke(root));
        }
    }
}
