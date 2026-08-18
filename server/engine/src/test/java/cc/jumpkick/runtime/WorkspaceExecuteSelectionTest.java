// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.WorkspaceCone;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceExecuteSelectionTest {

    @TempDir
    Path tmp;

    @Test
    void cone_includes_test_deps_when_tests_on_and_not_when_skip_tests() throws Exception {
        Path core = module("jk-core", "core", "");
        Path engine = module("jk-engine", "engine", """
                [dependencies]
                jk-core = { workspace = true }
                """);
        Path cli = module("jk-cli", "cli", """
                [application]
                main = "ex.Main"

                [dependencies]
                jk-core = { workspace = true }

                [test-dependencies]
                jk-engine = { workspace = true }

                [native]
                enabled = true
                """);

        Map<Path, JkBuild> mods = new LinkedHashMap<>();
        mods.put(core, JkBuildParser.parse(core.resolve("jk.toml")));
        mods.put(engine, JkBuildParser.parse(engine.resolve("jk.toml")));
        mods.put(cli, JkBuildParser.parse(cli.resolve("jk.toml")));

        Set<Path> withTests = WorkspaceCone.expand(mods, List.of(cli), List.of(Scope.values()));
        assertThat(withTests).contains(core, engine, cli);

        Set<Path> skip = WorkspaceCone.expand(mods, List.of(cli), ModuleOrder.PRODUCTION_SCOPES);
        assertThat(skip).contains(core, cli);
        assertThat(skip).doesNotContain(engine);
    }

    @Test
    void assemble_plan_native_terminals_only_selected_module() throws Exception {
        Path lib = module("lib", "lib", "");
        Path app = module("app", "app", """
                [application]
                main = "ex.Main"

                [native]
                enabled = true
                """);
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        JkBuild libB = JkBuildParser.parse(lib.resolve("jk.toml"));
        JkBuild appB = JkBuildParser.parse(app.resolve("jk.toml"));
        var libUnit = new BuildGraph.BuildUnit(lib, libB, "ex:lib", BuildGraph.Origin.MODULE);
        var appUnit = new BuildGraph.BuildUnit(app, appB, "ex:app", BuildGraph.Origin.MODULE);
        WorkspaceRequest req = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.nativeImage(Set.of(app), Map.of(app, graal), null, List.of()));

        var libPlan = WorkspaceExecute.assemblePlan(libUnit, req, Set.of(lib, app), false);
        var appPlan = WorkspaceExecute.assemblePlan(appUnit, req, Set.of(lib, app), false);
        Set<String> libNames = libPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        Set<String> appNames = appPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(libNames).contains(TaskNames.PACKAGE_JAR);
        assertThat(libNames).doesNotContain(TaskNames.NATIVE_IMAGE);
        assertThat(appNames).contains(TaskNames.PACKAGE_JAR, TaskNames.NATIVE_IMAGE);
    }

    @Test
    void test_only_plans_still_package_consumed_prereqs() throws Exception {
        // JK-2177: dependents compile against the prereq's sibling JAR; a testOnly plan that
        // recompiled classes but never repackaged left dependents building — and green-running
        // tests — against stale code.
        Path lib = module("lib", "lib", "");
        Path app = module("app", "app", """
                [dependencies]
                lib = { workspace = true }
                """);
        JkBuild libB = JkBuildParser.parse(lib.resolve("jk.toml"));
        JkBuild appB = JkBuildParser.parse(app.resolve("jk.toml"));
        var libUnit = new BuildGraph.BuildUnit(lib, libB, "ex:lib", BuildGraph.Origin.MODULE);
        var appUnit = new BuildGraph.BuildUnit(app, appB, "ex:app", BuildGraph.Origin.MODULE);
        WorkspaceRequest req = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true)
                .withTestOnly(true);

        Set<Path> jarConsumed = Set.of(BuildGraph.canonicalPath(lib));
        var libPlan = WorkspaceExecute.assemblePlan(libUnit, req, Set.of(lib, app), false, jarConsumed);
        var appPlan = WorkspaceExecute.assemblePlan(appUnit, req, Set.of(lib, app), false, jarConsumed);
        Set<String> libNames = libPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        Set<String> appNames = appPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(libNames)
                .as("consumed prereq must package on the test path")
                .contains(TaskNames.PACKAGE_JAR)
                .contains(TaskNames.RUN_TESTS);
        assertThat(appNames)
                .as("leaf keeps the jk test shape (no packaging)")
                .contains(TaskNames.RUN_TESTS)
                .doesNotContain(TaskNames.PACKAGE_JAR);
    }

    @Test
    void symlinked_seed_still_expands_prereqs() throws Exception {
        // JK-2101: cone identity is the canonical path — a seed reached through a symlink must
        // still match its graph module and pull dirty prereqs into the cone.
        Path core = module("jk-core", "core", "");
        Path cli = module("jk-cli", "cli", """
                [application]
                main = "ex.Main"

                [dependencies]
                jk-core = { workspace = true }
                """);
        Map<Path, JkBuild> mods = new LinkedHashMap<>();
        mods.put(core, JkBuildParser.parse(core.resolve("jk.toml")));
        mods.put(cli, JkBuildParser.parse(cli.resolve("jk.toml")));

        Path link = tmp.resolve("cli-link");
        try {
            Files.createSymbolicLink(link, cli);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unsupported here: " + e);
        }

        Set<Path> cone = WorkspaceCone.expand(mods, List.of(link), List.of(Scope.values()));
        assertThat(cone).contains(core, cli);
    }

    private Path module(String name, String dirName, String extra) throws Exception {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; public class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(name, extra));
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        return dir;
    }
}
