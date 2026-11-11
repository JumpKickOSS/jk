// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.testing.Symlinks;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
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

        var libPlan = ModulePipeline.plan(libUnit, req, Set.of(lib, app), false);
        var appPlan = ModulePipeline.plan(appUnit, req, Set.of(lib, app), false);
        Set<String> libNames = libPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        Set<String> appNames = appPlan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(libNames).contains(TaskNames.PACKAGE_JAR);
        assertThat(libNames).doesNotContain(TaskNames.NATIVE_IMAGE);
        assertThat(appNames).contains(TaskNames.PACKAGE_JAR, TaskNames.NATIVE_IMAGE);
    }

    @Test
    void test_only_plans_still_package_consumed_prereqs() throws Exception {
        // : dependents compile against the prereq's sibling JAR; a testOnly plan that
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
        var libPlan = ModulePipeline.plan(libUnit, req, Set.of(lib, app), false, jarConsumed);
        var appPlan = ModulePipeline.plan(appUnit, req, Set.of(lib, app), false, jarConsumed);
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

    /**
     * A suite that spawns nested engines runs the workspace engine's fat jar, so {@code jk test}
     * packages it: the suite then exercises the checkout's engine and every library bundled in it,
     * never an installed one.
     */
    @Test
    void test_plans_package_the_engine_assembly_a_nested_engine_suite_launches() throws Exception {
        Path engine = module("jk-engine", "engine", """
                [application]
                main = "ex.Main"
                assembly = true
                """, "cc.jumpkick");
        Path cli = module("jk-cli", "cli", """
                [application]
                main = "cc.jumpkick.cli.Jk"

                [test-dependencies]
                jk-engine = { workspace = true }
                """, "cc.jumpkick");
        Path app = module("app", "app", """
                [test-dependencies]
                jk-engine = { workspace = true }
                """);
        JkBuild engineB = JkBuildParser.parse(engine.resolve("jk.toml"));
        var engineUnit = new BuildGraph.BuildUnit(engine, engineB, "ex:jk-engine", BuildGraph.Origin.MODULE);
        var cliUnit = new BuildGraph.BuildUnit(
                cli, JkBuildParser.parse(cli.resolve("jk.toml")), "ex:jk-cli", BuildGraph.Origin.MODULE);
        var appUnit = new BuildGraph.BuildUnit(
                app, JkBuildParser.parse(app.resolve("jk.toml")), "ex:app", BuildGraph.Origin.MODULE);

        var withCli = new BuildGraph.Result(
                List.of(engineUnit, cliUnit),
                Map.of(cli, Set.of(), engine, Set.of()),
                Map.of(cli, Set.of(engine)),
                List.of(),
                false);
        var withoutCli = new BuildGraph.Result(
                List.of(engineUnit, appUnit),
                Map.of(app, Set.of(), engine, Set.of()),
                Map.of(app, Set.of(engine)),
                List.of(),
                false);
        Set<Path> launched = WorkspacePreflightPhase.launchedAssemblies(withCli);
        assertThat(launched).containsExactly(BuildGraph.canonicalPath(engine));
        assertThat(WorkspacePreflightPhase.launchedAssemblies(withoutCli)).isEmpty();

        WorkspaceRequest test = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true)
                .withTestOnly(true);
        Set<Path> jarConsumed = Set.of(BuildGraph.canonicalPath(engine));
        Set<String> launchedPlan = ModulePipeline.plan(
                        engineUnit, test, Set.of(engine, cli), false, jarConsumed, launched, SiblingArtifacts.NONE)
                .steps()
                .stream()
                .map(s -> s.name())
                .collect(Collectors.toSet());
        assertThat(launchedPlan)
                .as("the nested-engine suite's engine jar is this run's")
                .contains(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY, TaskNames.RUN_TESTS);
        Set<String> consumedPlan =
                ModulePipeline.plan(engineUnit, test, Set.of(engine, app), false, jarConsumed).steps().stream()
                        .map(s -> s.name())
                        .collect(Collectors.toSet());
        assertThat(consumedPlan)
                .as("a sibling that only compiles against the jar needs no fat jar")
                .contains(TaskNames.PACKAGE_JAR)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY);
    }

    /**
     * A consumer compiles against its sibling's classes tree, so under {@code jk compile} the
     * sibling's jar is read by nothing; the test and build verbs still package it, because their
     * run and package steps do.
     */
    @Test
    void compile_plans_package_nothing_while_test_plans_still_package_a_consumed_prereq() throws Exception {
        Path lib = module("lib", "lib", "");
        Path app = module("app", "app", """
                [dependencies]
                lib = { workspace = true }
                """);
        JkBuild libB = JkBuildParser.parse(lib.resolve("jk.toml"));
        JkBuild appB = JkBuildParser.parse(app.resolve("jk.toml"));
        var libUnit = new BuildGraph.BuildUnit(lib, libB, "ex:lib", BuildGraph.Origin.MODULE);
        var appUnit = new BuildGraph.BuildUnit(app, appB, "ex:app", BuildGraph.Origin.MODULE);
        Set<Path> jarConsumed = Set.of(BuildGraph.canonicalPath(lib));

        WorkspaceRequest compile = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.compile(Set.of()));
        for (var unit : List.of(libUnit, appUnit)) {
            Set<String> names =
                    ModulePipeline.plan(unit, compile, Set.of(lib, app), false, jarConsumed).steps().stream()
                            .map(s -> s.name())
                            .collect(Collectors.toSet());
            assertThat(names)
                    .as(unit.coord() + " under jk compile")
                    .contains(TaskNames.COMPILE_JAVA, TaskNames.COPY_RESOURCES)
                    .doesNotContain(TaskNames.PACKAGE_JAR, TaskNames.RUN_TESTS, TaskNames.COMPILE_TEST);
        }

        WorkspaceRequest test = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true)
                .withTestOnly(true);
        Set<String> libUnderTest =
                ModulePipeline.plan(libUnit, test, Set.of(lib, app), false, jarConsumed).steps().stream()
                        .map(s -> s.name())
                        .collect(Collectors.toSet());
        assertThat(libUnderTest)
                .as("jk test runs app's tests against lib's jar")
                .contains(TaskNames.PACKAGE_JAR);
    }

    @Test
    void symlinked_seed_still_expands_prereqs() throws Exception {
        // Cone identity is the canonical path: a seed reached through a symlink must still match
        // its graph module and pull its prereqs into the cone.
        Path core = module("jk-core", "core", "");
        Path cli = module("jk-cli", "cli", "");
        var graph = new BuildGraph.Result(
                List.of(unit(core, "ex:jk-core"), unit(cli, "ex:jk-cli")),
                Map.of(BuildGraph.canonicalPath(cli), Set.of(BuildGraph.canonicalPath(core))),
                Map.of(),
                List.of(),
                false);

        Path link = tmp.resolve("cli-link");
        Symlinks.create(link, cli);

        assertThat(ModuleHints.withPrereqs(graph, Set.of(link), false))
                .containsExactlyInAnyOrder(BuildGraph.canonicalPath(core), BuildGraph.canonicalPath(cli));
    }

    /**
     * The selection cone is the graph's own edges: a prereq the graph holds that the manifest does
     * not name (a member reached through a locked published POM) is built under {@code -m}, and a
     * test-only edge is in the cone unless tests are skipped.
     */
    @Test
    void selection_cone_follows_the_graphs_edges_not_the_manifests() throws Exception {
        Path lib = module("lib", "lib", "");
        Path substituted = module("sub", "sub", "");
        Path fixtures = module("fixtures", "fixtures", "");
        Path app = module("app", "app", "");
        BuildGraph.BuildUnit libUnit = unit(lib, "ex:lib");
        BuildGraph.BuildUnit subUnit = unit(substituted, "ex:sub");
        BuildGraph.BuildUnit fixturesUnit = unit(fixtures, "ex:fixtures");
        BuildGraph.BuildUnit appUnit = unit(app, "ex:app");
        // app's manifest names none of them; the graph holds lib -> sub as a substituted prereq.
        var graph = new BuildGraph.Result(
                List.of(subUnit, libUnit, fixturesUnit, appUnit),
                Map.of(app, Set.of(lib), lib, Set.of(substituted), substituted, Set.of(), fixtures, Set.of()),
                Map.of(app, Set.of(fixtures)),
                List.of(),
                false);

        WorkspaceRequest withTests = selecting(app, false);
        assertThat(WorkspacePreflightPhase.applySelectionCone(graph, withTests).topoOrder())
                .extracting(BuildGraph.BuildUnit::dir)
                .containsExactlyInAnyOrder(app, lib, substituted, fixtures);

        WorkspaceRequest skipTests = selecting(app, true);
        assertThat(WorkspacePreflightPhase.applySelectionCone(graph, skipTests).topoOrder())
                .extracting(BuildGraph.BuildUnit::dir)
                .containsExactlyInAnyOrder(app, lib, substituted);
    }

    /** Explain keeps the cone the build runs: the same edges, test edges only when tests run. */
    @Test
    void explain_and_build_agree_on_the_cone() throws Exception {
        Path lib = module("lib", "lib", "");
        Path fixtures = module("fixtures", "fixtures", "");
        Path app = module("app", "app", "");
        var graph = new BuildGraph.Result(
                List.of(unit(lib, "ex:lib"), unit(fixtures, "ex:fixtures"), unit(app, "ex:app")),
                Map.of(app, Set.of(lib), lib, Set.of(), fixtures, Set.of()),
                Map.of(app, Set.of(fixtures)),
                List.of(),
                false);
        for (boolean skipTests : new boolean[] {false, true}) {
            Set<Path> built =
                    WorkspacePreflightPhase.applySelectionCone(graph, selecting(app, skipTests)).topoOrder().stream()
                            .map(BuildGraph.BuildUnit::dir)
                            .collect(Collectors.toSet());
            assertThat(ModuleHints.withPrereqs(graph, Set.of(app), skipTests))
                    .as("skipTests=%s", skipTests)
                    .containsExactlyInAnyOrderElementsOf(built);
        }
    }

    private BuildGraph.BuildUnit unit(Path dir, String coord) throws Exception {
        return new BuildGraph.BuildUnit(
                BuildGraph.canonicalPath(dir),
                JkBuildParser.parse(dir.resolve("jk.toml")),
                coord,
                BuildGraph.Origin.MODULE);
    }

    private WorkspaceRequest selecting(Path module, boolean skipTests) {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, skipTests, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.compile(Set.of(module)));
    }

    private Path module(String name, String dirName, String extra) throws Exception {
        return module(name, dirName, extra, "ex");
    }

    private Path module(String name, String dirName, String extra, String group) throws Exception {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; public class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "%s"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(group, name, extra));
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        return dir;
    }
}
