// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The suite runs after every plugin step that changes what its JVM loads, and no other. */
class RunTestsRequiresTest {

    private static TaskDecl decl(
            String name,
            List<String> inputs,
            List<String> classes,
            List<String> resources,
            @Nullable String transforms) {
        return new TaskDecl(
                name,
                List.of(),
                inputs,
                List.of("out"),
                classes,
                resources,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                transforms,
                null,
                false,
                null);
    }

    @Test
    void a_classes_transform_or_contribution_orders_the_suite_after_it() {
        PluginDeclarations decls = new PluginDeclarations(
                List.of(
                        decl("enhance", List.of("classes"), List.of(), List.of(), "out"),
                        decl("weave", List.of(), List.of("out"), List.of(), null),
                        decl("bundle", List.of(), List.of(), List.of("out"), null),
                        decl("report", List.of("classes"), List.of(), List.of(), null)),
                null,
                List.of());
        assertThat(PlannerTest.runTestsRequires(decls, List.of()))
                .contains("plugin-enhance", "plugin-weave", "plugin-bundle")
                .doesNotContain("plugin-report");
    }
}
