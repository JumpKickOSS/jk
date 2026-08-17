// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static cc.jumpkick.model.JkBuild parse(Path dir) throws Exception {
        return cc.jumpkick.config.JkBuildParser.parse(dir.resolve("jk.toml"));
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

    @org.junit.jupiter.api.Test
    void failure_exit_code_maps_native_main_misconfig_to_usage() {
        // JK-2099: the workspace path dropped the old NativeVerb's Exit.USAGE mapping and
        // left failureExitCode dead — jk native --main no.Such.Class exited 1 instead of 64.
        var plan = cc.jumpkick.run.BuildPlan.builder("native").build();
        var usage = new cc.jumpkick.run.BuildPlanResult(
                "native",
                false,
                java.time.Duration.ZERO,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(new cc.jumpkick.run.BuildPlanResult.Diagnostic(
                        "native-image", "native", "main class no.Such.Class not found")),
                false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, usage))
                .isEqualTo(cc.jumpkick.model.command.Exit.USAGE);
        var plain = new cc.jumpkick.run.BuildPlanResult(
                "native", false, java.time.Duration.ZERO,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, plain))
                .isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void failure_exit_code_maps_image_no_main_to_usage() {
        // JK-2100: workspace jk image lost the single path's no-main USAGE exit.
        var plan = cc.jumpkick.run.BuildPlan.builder("image").build();
        var noMain = new cc.jumpkick.run.BuildPlanResult(
                "image", false, java.time.Duration.ZERO,
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(new cc.jumpkick.run.BuildPlanResult.Diagnostic(
                        "image-plan", "no-main", "no main class - pass --main")),
                false);
        org.assertj.core.api.Assertions.assertThat(NativePlans.failureExitCode(plan, noMain))
                .isEqualTo(cc.jumpkick.model.command.Exit.USAGE);
    }
}
