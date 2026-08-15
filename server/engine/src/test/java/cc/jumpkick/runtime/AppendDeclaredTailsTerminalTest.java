// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression: coreBuilder terminals at package-jar; appendDeclaredTails must re-terminal so
 * package-assembly / native-image are not pruned away before build().
 */
class AppendDeclaredTailsTerminalTest {

    @TempDir
    Path tmp;

    @Test
    void assembly_tail_survives_terminal_prune() throws Exception {
        Path dir = tmp.resolve("app");
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "app"
                version = "1.0"
                java = 25

                [application]
                main = "ex.Main"
                assembly = true
                """);
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        Path lock = dir.resolve("jk-lock.toml");
        Files.writeString(lock, """
                schema = 1
                """);

        BuildPlanner.Inputs inputs = TaskForecaster.inputsFor(dir, cache, 1, null, null, true, false);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, true);
        // Simulate the historical bug: terminal already at package-jar.
        assertThat(b).isNotNull();
        BuildPlanner.appendDeclaredTails(b, inputs);
        BuildPlan plan = b.build();
        Set<String> names = plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(names)
                .as("package-assembly must survive terminal prune after appendDeclaredTails")
                .contains(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY);
    }
}
