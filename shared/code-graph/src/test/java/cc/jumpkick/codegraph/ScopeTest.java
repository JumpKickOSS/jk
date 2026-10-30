// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeTest {

    private static final Modules MODULES =
            new Modules(List.of(new Modules.Module("", false), new Modules.Module("server/engine/", false)));

    private static Scope scope(String globs) {
        return Scope.of(globs, List.of(), null, MODULES);
    }

    @Test
    void globsMatchWholePathsAndExclusionsWin() {
        Scope s = scope("server/**/*.java;!**/generated/**");
        assertThat(s.containsPath("server/engine/src/main/java/A.java")).isTrue();
        assertThat(s.containsPath("server/engine/target/generated/B.java")).isFalse();
        assertThat(s.containsPath("server/engine/src/main/resources/a.yml")).isFalse();
        assertThat(scope("*.md").containsPath("README.md")).isTrue();
        assertThat(scope("*.md").containsPath("docs/notes.md")).isFalse();
        assertThat(scope("**/*.{kt,java}").containsPath("a/B.kt")).isTrue();
    }

    @Test
    void aLiteralPathMeansEverythingUnderIt() {
        assertThat(scope("server/engine").containsPath("server/engine/jk.toml")).isTrue();
        assertThat(scope("server/engine/").containsPath("server/engine/src/main/java/A.java"))
                .isTrue();
        assertThat(scope("server/engine").containsPath("server/engineering/x")).isFalse();
    }

    @Test
    void modulesExcludeTheirNestedModulesAndSourceSetsSplitMainFromTest() {
        Scope root = Scope.of(null, List.of(""), null, MODULES);
        assertThat(root.containsPath("src/main/java/A.java")).isTrue();
        assertThat(root.containsPath("server/engine/src/main/java/A.java")).isFalse();
        Scope tests = Scope.of(null, List.of(), "test", MODULES);
        assertThat(tests.containsPath("server/engine/src/test/java/ATest.java")).isTrue();
        assertThat(tests.containsPath("server/engine/src/main/java/A.java")).isFalse();
        assertThatThrownBy(() -> Scope.of(null, List.of(), "docs", MODULES)).hasMessageContaining("main or test");
    }

    @Test
    void nothingNarrowingIsEverything() {
        assertThat(Scope.of(" ", List.of(), null, MODULES).all()).isTrue();
        assertThat(Scope.ALL.contains(new GraphNode("dep", "dep", "concept", "", "")))
                .isTrue();
        assertThat(scope("src/**").contains(new GraphNode("dep", "dep", "concept", "", "")))
                .isFalse();
    }

    @Test
    void aScopedQuerySeedsAndWalksOnlyInsideIt() throws IOException {
        CodeGraph g = ShopFixture.graph();
        String kotlin = new GraphQuery(g, scope("src/main/kotlin/**"), Map.of())
                .query("price calculator", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(kotlin.lines().skip(2).takeWhile(l -> !l.contains(" -")))
                .isNotEmpty()
                .allMatch(l -> l.contains("  src/main/kotlin/"));
        String javaOnly = new GraphQuery(g, scope("src/main/java/**"), Map.of())
                .query("what does OrderService use", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(javaOnly)
                .contains("\nOrderService  src/main/java/com/acme/shop/OrderService.java:")
                .doesNotContain("README.md")
                .doesNotContain("PriceCalculator");
    }
}
