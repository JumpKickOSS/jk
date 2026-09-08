// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.NativePlans;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk native} plans: native-image terminal only when the module is native-eligible; prereq
 * and library modules stay at package (or declared tails) so the workspace cascade does not fail
 * with "terminal task 'native-image' is not in the BuildPlan".
 */
class NativePlansTest {

    @TempDir
    Path tmp;

    @Test
    void module_with_graal_home_terminals_at_native_image() throws Exception {
        Path dir = module("app", true);
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        BuildPlan plan =
                NativePlans.moduleBuildPlan(dir, parse(dir), cache(), null, graal, null, List.of(), true, false, true);
        Set<String> names = plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(names).contains(TaskNames.PACKAGE_JAR, TaskNames.NATIVE_IMAGE);
        assertThat(names).doesNotContain(TaskNames.RUN_TESTS); // skipTests=true
    }

    @Test
    void prereq_module_without_native_stays_at_package_jar() throws Exception {
        Path dir = module("lib", false);
        // allowNative=false (workspace prereq) even if a graal home is present.
        Path graal = Files.createDirectories(tmp.resolve("graal2"));
        assertThatCode(() -> NativePlans.moduleBuildPlan(
                        dir, parse(dir), cache(), null, graal, null, List.of(), true, false, false))
                .doesNotThrowAnyException();
        BuildPlan plan =
                NativePlans.moduleBuildPlan(dir, parse(dir), cache(), null, graal, null, List.of(), true, false, false);
        Set<String> names = plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(names).contains(TaskNames.PACKAGE_JAR);
        assertThat(names).doesNotContain(TaskNames.NATIVE_IMAGE);
    }

    @Test
    void library_without_graal_home_does_not_demand_native_image() throws Exception {
        Path dir = module("core", false);
        // Workspace cascade: allowNative=true for all, but client only supplies graal for modules
        // with a unique main — graalHome null must not set terminal to native-image.
        assertThatCode(() -> NativePlans.moduleBuildPlan(
                        dir, parse(dir), cache(), null, null, null, List.of(), true, false, true))
                .doesNotThrowAnyException();
        BuildPlan plan =
                NativePlans.moduleBuildPlan(dir, parse(dir), cache(), null, null, null, List.of(), true, false, true);
        Set<String> names = plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(names).contains(TaskNames.PACKAGE_JAR);
        assertThat(names).doesNotContain(TaskNames.NATIVE_IMAGE);
    }

    private Path cache() throws Exception {
        Path c = tmp.resolve("cache-" + System.nanoTime());
        Files.createDirectories(c);
        return c;
    }

    private static JkBuild parse(Path dir) throws Exception {
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private Path module(String name, boolean withMain) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        if (withMain) {
            Files.writeString(
                    dir.resolve("src/main/java/ex/Main.java"),
                    "package ex; public class Main { public static void main(String[] a) {} }\n");
        } else {
            Files.writeString(dir.resolve("src/main/java/ex/Lib.java"), "package ex; public class Lib {}\n");
        }
        String app = withMain ? """

                [application]
                main = "ex.Main"
                """ : "";
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(name, app));
        Files.writeString(dir.resolve("jk-lock.toml"), """
                schema = 1
                """);
        return dir;
    }

    @Test
    void failure_exit_code_maps_native_main_misconfig_to_usage() {
        var plan =
                BuildPlan.builder("native").stateKeys(BuildPlanner.TEST_RESULT).build();
        var usage = new BuildPlanResult(
                "native",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("native-image", "native", "main class no.Such.Class not found")),
                false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, usage))
                .isEqualTo(Exit.USAGE);
        var plain = new BuildPlanResult("native", false, Duration.ZERO, List.of(), List.of(), List.of(), false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, plain))
                .isEqualTo(1);
    }

    @Test
    void failure_exit_code_maps_image_no_main_to_usage() {
        var plan = BuildPlan.builder("image").build();
        var noMain = new BuildPlanResult(
                "image",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("image-plan", "no-main", "no main class - pass --main")),
                false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, noMain))
                .isEqualTo(Exit.USAGE);
    }
}
