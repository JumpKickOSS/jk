// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphRulesTest {

    @Test
    void aRuleReachesAFileInItsScopeUnlessAnAllowNamesIt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "a"
                name = "w"
                version = "1"
                java = 25
                """);
        Files.writeString(root.resolve("jk-guards.toml"), """
                [guards.no-system-out]
                kind = "forbid"
                signatures = ["@jdk-system-out"]
                instead = "the Logger"
                why = "stdout is not a log sink"

                [[guards.no-system-out.allow]]
                in = "app/src/main/java/a/Main.java"
                reason = "the entry point prints usage"

                [guards.lib-only]
                kind = "forbid"
                signatures = ["java.lang.Thread#sleep(**)"]
                instead = "a scheduled executor"
                why = "no sleeping in the library"
                scope = ["lib"]

                [guards.deps]
                kind = "depend"
                ban = ["log4j:log4j"]
                why = "log4j 1 is dead"
                """);
        List<String> prefixes = List.of("", "app/", "lib/");
        List<String> shop = GraphRules.rows(root, "app/src/main/java/a/Shop.java", prefixes);
        assertThat(shop).hasSize(1);
        assertThat(shop.getFirst()).contains("\"id\":\"no-system-out\"").contains("\"instead\":\"the Logger\"");
        assertThat(GraphRules.rows(root, "app/src/main/java/a/Main.java", prefixes))
                .isEmpty();
        assertThat(GraphRules.rows(root, "lib/src/main/java/a/Util.java", prefixes))
                .anyMatch(r -> r.contains("lib-only"))
                .noneMatch(r -> r.contains("\"deps\""));
    }

    @Test
    void aWorkspaceWithoutRulesHasNone(@TempDir Path root) {
        assertThat(GraphRules.rows(root, "a/B.java", List.of(""))).isEmpty();
        assertThat(GraphRules.module("lib/src/X.java", List.of("", "lib/"))).isEqualTo("lib");
        assertThat(GraphRules.module("README.md", List.of("", "lib/"))).isEmpty();
    }
}
