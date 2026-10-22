// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.PublishPlans;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lifecycle every build-like goal shares, held per module: a goal that packages plans exactly
 * {@code jk build}'s steps (without the suite when its policy runs no tests) plus its own tail's
 * steps, and a goal that stops earlier plans only steps {@code jk build} has. A new goal or a new
 * tail that drops or adds a step anywhere else fails here.
 */
class LifecycleParityTest {

    /** The steps each goal's tail may add past PACKAGE, on a module the goal selects. */
    private static final Map<WorkspaceTarget, Set<String>> TAIL_STEPS = Map.of(
            WorkspaceTarget.PACKAGE, Set.of(),
            WorkspaceTarget.RUN, Set.of(),
            WorkspaceTarget.INSTALL, Set.of(TaskNames.CACHE_INSTALL),
            WorkspaceTarget.NATIVE, Set.of(TaskNames.NATIVE_IMAGE),
            WorkspaceTarget.IMAGE, Set.of(TaskNames.IMAGE_PLAN, TaskNames.WRITE_IMAGE),
            WorkspaceTarget.TRAIN, Set.of(TaskNames.TRAIN),
            WorkspaceTarget.PUBLISH, Set.of(TaskNames.PUBLISH_CHECK, TaskNames.PUBLISH));

    @TempDir
    Path tmp;

    private Path graal;
    private List<BuildGraph.BuildUnit> units;
    private Set<Path> moduleDirs;

    @BeforeEach
    void workspace() throws Exception {
        graal = Files.createDirectories(tmp.resolve("graal"));
        Path root = tmp;
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = module("lib", "Lib", "", "");
        Path app = module("app", "App", """
                [application]
                main = "ex.App"
                assembly = true

                [dependencies]
                lib = { workspace = true }
                """, "public static void main(String[] a) {}");
        units = List.of(unit(root, "ex:ws"), unit(lib, "ex:lib"), unit(app, "ex:app"));
        moduleDirs = Set.of(root, lib, app);
    }

    @Test
    void every_goal_that_packages_is_the_build_plus_its_tail() throws Exception {
        for (WorkspaceTarget target : EnumSet.allOf(WorkspaceTarget.class)) {
            if (!target.packages()) continue;
            assertThat(TAIL_STEPS).as("a tail table row for " + target).containsKey(target);
            for (BuildGraph.BuildUnit unit : units) {
                boolean skipTests = target.tests() == WorkspaceTarget.Tests.NOT_RUN;
                Set<String> build = steps(unit, WorkspaceTarget.PACKAGE, skipTests);
                Set<String> goal = steps(unit, target, false);
                Set<String> expected = new TreeSet<>(build);
                if (selects(target, unit)) expected.addAll(TAIL_STEPS.get(target));
                assertThat(goal)
                        .as(target + " on " + unit.coord() + " is jk build's steps plus its tail")
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void a_goal_that_stops_early_plans_only_steps_the_build_has() throws Exception {
        for (BuildGraph.BuildUnit unit : units) {
            Set<String> build = steps(unit, WorkspaceTarget.PACKAGE, false);
            Set<String> test = steps(unit, WorkspaceTarget.TEST, false);
            Set<String> compile = steps(unit, WorkspaceTarget.COMPILE, false);
            assertThat(test).as("jk test on " + unit.coord()).isSubsetOf(build);
            compile.remove("compile-join"); // compile-only plans join their compiles under one terminal
            assertThat(compile).as("jk compile on " + unit.coord()).isSubsetOf(test);
        }
    }

    @Test
    void a_tail_runs_after_every_phase_it_shares() throws Exception {
        BuildGraph.BuildUnit app = units.getLast();
        for (WorkspaceTarget target : List.of(
                WorkspaceTarget.INSTALL, WorkspaceTarget.NATIVE, WorkspaceTarget.IMAGE, WorkspaceTarget.PUBLISH)) {
            var plan = plan(app, target, false);
            assertThat(plan.steps().stream().map(t -> t.name()))
                    .as(target + " keeps the suite")
                    .contains(TaskNames.RUN_TESTS);
            assertThat(TAIL_STEPS.get(target))
                    .as(target + " ends on its tail")
                    .contains(plan.steps().getLast().name());
        }
    }

    private boolean selects(WorkspaceTarget target, BuildGraph.BuildUnit unit) {
        if (unit.coord().equals("ex:ws")) return false; // the coordinator root has no tail
        return switch (target) {
            case NATIVE, IMAGE, TRAIN -> unit.coord().equals("ex:app");
            default -> true;
        };
    }

    private Set<String> steps(BuildGraph.BuildUnit unit, WorkspaceTarget target, boolean skipTests) throws Exception {
        return plan(unit, target, skipTests).steps().stream()
                .map(t -> t.name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private BuildPlan plan(BuildGraph.BuildUnit unit, WorkspaceTarget target, boolean skipTests) throws Exception {
        Path app = units.getLast().dir();
        WorkspaceSpec spec =
                switch (target) {
                    case NATIVE -> WorkspaceSpec.nativeImage(Set.of(app), Map.of(app, graal), null, List.of());
                    case IMAGE -> WorkspaceSpec.image(Set.of(app), null, null, null, "", null);
                    case INSTALL -> WorkspaceSpec.install(Set.of(), Map.of(), tmp.resolve("m2"));
                    case TRAIN ->
                        new WorkspaceSpec(
                                target, Set.of(app), Map.of(), null, List.of(), null, null, null, null, null, null);
                    default -> WorkspaceSpec.of(target);
                };
        WorkspaceRequest request = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, skipTests, false, 0, null, false, false)
                .withSpec(spec);
        return ModulePipeline.plan(
                unit, request, moduleDirs, false, Set.of(), Set.of(), SiblingArtifacts.NONE, tail(target, unit));
    }

    /** TRAIN and PUBLISH carry engine-side arguments, so their verbs hand the planner the tail. */
    private @Nullable Tail tail(WorkspaceTarget target, BuildGraph.BuildUnit unit) {
        return switch (target) {
            case TRAIN -> TrainPlans.tail(unit.manifest(), null, Path.of(System.getProperty("java.home")), null, false);
            case PUBLISH ->
                PublishTail.of(
                        new PublishPlans.Request(
                                null,
                                null,
                                null,
                                null,
                                false,
                                true,
                                null,
                                null,
                                false,
                                false,
                                false,
                                RepoCredential.ANONYMOUS,
                                false,
                                null),
                        tmp.resolve("publisher.jar"));
            default -> null;
        };
    }

    private Path module(String name, String type, String extra, String body) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(src.resolve(type + ".java"), "package ex; public class " + type + " { " + body + " }\n");
        Path test = Files.createDirectories(dir.resolve("src/test/java/ex"));
        Files.writeString(test.resolve(type + "Test.java"), "package ex; class " + type + "Test {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0.0"
                java = 25
                %s
                """.formatted(name, extra));
        return dir;
    }

    private static BuildGraph.BuildUnit unit(Path dir, String coord) throws Exception {
        return new BuildGraph.BuildUnit(
                dir, JkBuildParser.parse(dir.resolve("jk.toml")), coord, BuildGraph.Origin.MODULE);
    }
}
