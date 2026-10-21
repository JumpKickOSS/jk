// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The declared tails re-terminal the plan past package-jar, and a guard run declares none. */
class AppendDeclaredTailsTerminalTest {

    @TempDir
    Path tmp;

    @Test
    void assembly_tail_survives_terminal_prune() throws Exception {
        assertThat(plannedSteps())
                .as("package-assembly must survive terminal prune after appendDeclaredTails")
                .contains(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY);
    }

    @Test
    void a_guard_run_packages_the_jar_and_no_delivery_tail() throws Exception {
        Set<String> names = SessionContext.where(SessionContext.current().withLanesOnly(true), this::plannedSteps);
        assertThat(names).contains(TaskNames.PACKAGE_JAR).doesNotContain(TaskNames.PACKAGE_ASSEMBLY);
    }

    private Set<String> plannedSteps() throws Exception {
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
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        BuildPlanner.Inputs inputs = TaskForecaster.inputsFor(dir, cache, 1, null, null, true, false);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, true);
        PlannerTails.appendDeclaredTails(b, inputs);
        return b.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
    }
}
