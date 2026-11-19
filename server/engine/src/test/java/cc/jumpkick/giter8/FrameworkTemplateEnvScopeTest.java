// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A Spring Boot, Quarkus, Micronaut or Helidon project from {@code jk new} lets its tests see the
 * whole shell, as every project does, and carries the {@code [env] scope} that narrows it commented
 * out, with what it buys and what it costs: those frameworks read every variable, so their suites
 * are keyed on all of them until a scope says otherwise.
 */
class FrameworkTemplateEnvScopeTest {

    private static final Map<String, String> SCOPE = Map.of(
            "spring-boot", "# scope = [\"SPRING_*\", \"<YOURAPP>_*\"]",
            "quarkus", "# scope = [\"QUARKUS_*\", \"<YOURAPP>_*\"]",
            "micronaut", "# scope = [\"MICRONAUT_*\", \"<YOURAPP>_*\"]",
            "helidon", "# scope = [\"<YOURAPP>_*\"]");

    @Test
    void each_framework_template_carries_the_scope_commented_out() throws IOException {
        Path repo = RepoRoot.find(FrameworkTemplateEnvScopeTest.class);
        List<Path> roots = new ArrayList<>();
        roots.add(repo.resolve("templates"));
        for (String plugin : SCOPE.keySet())
            roots.add(repo.resolve("plugins/" + plugin + "/src/main/resources/templates"));
        int seen = 0;
        for (Path root : roots) {
            for (String lang : List.of("java", "kotlin")) {
                for (var e : SCOPE.entrySet()) {
                    Path framework = root.resolve(lang).resolve(e.getKey());
                    if (!Files.isDirectory(framework)) continue;
                    try (Stream<Path> trees = Files.list(framework)) {
                        for (Path tree :
                                trees.filter(p -> p.toString().endsWith(".g8")).toList()) {
                            String manifest = Files.readString(tree.resolve("src/main/g8/jk.toml"))
                                    .replace("\r\n", "\n");
                            assertThat(manifest)
                                    .as(repo.relativize(tree).toString())
                                    .contains("# [env]\n" + e.getValue() + "\n")
                                    .contains(
                                            "stay cached across unrelated variable changes and, with a cache key seed, can be shared")
                                    .contains("tests see no other variable")
                                    .doesNotContainPattern("(?m)^\\[env\\]");
                            seen++;
                        }
                    }
                }
            }
        }
        assertThat(seen).as("framework templates found").isGreaterThanOrEqualTo(16);
    }
}
