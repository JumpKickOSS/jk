// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The one planner of a module's build, for every build-like goal. The goal's phases run from the
 * same {@link BuildPlanner.Inputs} and the same core up to its {@link WorkspaceTarget#through()}
 * phase; a goal that packages then gets the PACKAGE phase's declared deliveries and test join,
 * and finally its {@link Tail}, which can only extend the plan. Goals differ in their tail and in
 * {@link WorkspaceTarget#tests()}, never in the steps before the tail. See {@code
 * docs/contributors/lifecycle.md}.
 */
@NullMarked
public final class ModulePipeline {

    private ModulePipeline() {}

    static BuildPlan plan(
            BuildGraph.BuildUnit unit, WorkspaceRequest request, Set<Path> moduleDirs, boolean forceRebuild) {
        return plan(unit, request, moduleDirs, forceRebuild, Set.of());
    }

    static BuildPlan plan(
            BuildGraph.BuildUnit unit,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            boolean forceRebuild,
            Set<Path> jarConsumed) {
        return plan(unit, request, moduleDirs, forceRebuild, jarConsumed, Set.of(), SiblingArtifacts.NONE);
    }

    /**
     * {@code unit}'s plan for the request's goal. {@code jarConsumed} names the modules a sibling
     * compiles against, {@code launchedAssemblies} the ones a sibling's suite runs from their fat
     * jar, {@code siblings} this module's side of the schedule's artifact wait.
     */
    static BuildPlan plan(
            BuildGraph.BuildUnit unit,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            boolean forceRebuild,
            Set<Path> jarConsumed,
            Set<Path> launchedAssemblies,
            SiblingArtifacts.Gate siblings) {
        return plan(unit, request, moduleDirs, forceRebuild, jarConsumed, launchedAssemblies, siblings, null);
    }

    /** As above; a non-null {@code selectedTail} is the tail of the modules the request selects. */
    public static BuildPlan plan(
            BuildGraph.BuildUnit unit,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            boolean forceRebuild,
            Set<Path> jarConsumed,
            Set<Path> launchedAssemblies,
            SiblingArtifacts.Gate siblings,
            @Nullable Tail selectedTail) {
        Path dir = unit.dir();
        WorkspaceTarget target = request.target();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        if (target == WorkspaceTarget.RESHELVE) return reshelve(unit, request.cache(), spec);
        boolean nodeModule = NodeShape.kind(unit.manifest(), dir) == NodeShape.Kind.MODULE;
        boolean consumed = jarConsumed.contains(BuildGraph.canonicalPath(dir));
        // A test run packages the jar a sibling compiles against: its suite reads it.
        boolean packagesForTests = target == WorkspaceTarget.TEST && consumed && !nodeModule;
        BuildPlanner.Inputs in =
                inputs(dir, request, moduleDirs, target == WorkspaceTarget.TEST && !consumed, target, siblings);
        BuildPlan.Builder plan = BuildPlanner.coreBuilder(in, forceRebuild);
        if (!target.packages()) {
            if (packagesForTests) {
                PlannerTails.joinTests(plan, in, launchedAssemblies.contains(BuildGraph.canonicalPath(dir)));
            }
            return plan.build();
        }
        Tail tail = !selected(spec, dir)
                ? Tail.NONE
                : selectedTail != null ? selectedTail : tail(target, spec, unit, request.cache());
        PlannerTails.appendDeclaredTails(
                plan, in, GraalHomes.lookup(dir, spec.graalByDir()), !tail.buildsNativeImage());
        tail.append(plan, in);
        return plan.build();
    }

    /** The tail of a module the request selects, from the spec the client sent. */
    private static Tail tail(WorkspaceTarget target, WorkspaceSpec spec, BuildGraph.BuildUnit unit, Path cache) {
        return switch (target) {
            case INSTALL -> InstallPlans.tail(unit.manifest(), cache, m2Dir(spec));
            case NATIVE ->
                NativePlans.tail(
                        GraalHomes.lookup(unit.dir(), spec.graalByDir()), spec.nativeMain(), spec.nativeExtraArgs());
            case IMAGE ->
                ImagePlans.tail(
                        spec.imageMain(),
                        spec.imageRegistry(),
                        spec.imageTag(),
                        spec.imageTarball(),
                        spec.imageDocker());
            case PACKAGE, RUN -> Tail.NONE;
            case TRAIN, PUBLISH ->
                throw new IllegalStateException(target + " reaches the planner with its tail, never from the spec");
            case COMPILE, TEST, RESHELVE -> throw new IllegalStateException(target + " does not package");
        };
    }

    /**
     * {@code in}'s plan through PACKAGE with its declared deliveries and no tail, every step forced:
     * the shape an estimate prices when no forecast says what is cached.
     */
    static BuildPlan throughPackage(BuildPlanner.Inputs in) {
        BuildPlan.Builder plan = BuildPlanner.coreBuilder(in, true);
        PlannerTails.appendDeclaredTails(plan, in);
        return plan.build();
    }

    /**
     * A project built on its own: its one unit, planned exactly as a workspace module. A non-null
     * {@code tail} is the goal's tail; null takes it from the request's spec.
     */
    public static BuildPlan standalone(WorkspaceRequest request, @Nullable Tail tail) throws IOException {
        Path dir = request.entryDir();
        BuildGraph.Result graph = BuildGraph.resolve(dir, JkBuildParser.parse(ManifestPaths.manifestIn(dir)));
        Path canonical = BuildGraph.canonicalPath(dir);
        BuildGraph.BuildUnit unit = graph.topoOrder().stream()
                .filter(u -> BuildGraph.canonicalPath(u.dir()).equals(canonical))
                .findFirst()
                .orElseThrow(() -> new IOException("no build unit for " + dir));
        return plan(unit, request, Set.of(unit.dir()), false, Set.of(), Set.of(), SiblingArtifacts.NONE, tail);
    }

    /** Install's second pass: shelve the jars already on disk. A coordinator has none. */
    private static BuildPlan reshelve(BuildGraph.BuildUnit unit, Path cache, WorkspaceSpec spec) {
        if (CompileSupport.coordinatorOnly(unit.manifest(), unit.dir())) {
            return BuildPlan.builder("reshelve-skip").build();
        }
        return InstallPlans.reshelveBuildPlan(unit.dir(), cache, m2Dir(spec));
    }

    private static boolean selected(WorkspaceSpec spec, Path dir) {
        return !spec.hasSelection()
                || spec.selectedModules().stream()
                        .anyMatch(path -> BuildGraph.canonicalPath(path).equals(BuildGraph.canonicalPath(dir)));
    }

    private static Path m2Dir(WorkspaceSpec spec) {
        return spec.m2Dir() != null ? spec.m2Dir() : Path.of(System.getProperty("user.home", "."), ".m2");
    }

    /** The request-level knobs every module's {@link BuildPlanner.Inputs} carries. */
    static UnaryOperator<BuildPlanner.Inputs> requestKnobs(WorkspaceRequest request, Set<Path> moduleDirs) {
        return requestKnobs(request, moduleDirs, SiblingArtifacts.NONE);
    }

    /** As above, and the module's side of the schedule's artifact wait. */
    static UnaryOperator<BuildPlanner.Inputs> requestKnobs(
            WorkspaceRequest request, Set<Path> moduleDirs, SiblingArtifacts.Gate siblings) {
        return inputs -> inputs.withWorkerCount(Math.max(0, request.workers()))
                .withProfileName(request.profile())
                .withProjectModules(moduleDirs)
                .withVariant(request.variant(), request.clientEnv())
                .withEphemeralActions(request.ephemeralActions())
                .withSiblings(siblings);
    }

    /** The one {@link BuildPlanner.Inputs} a module's plan is built from. */
    static BuildPlanner.Inputs inputs(Path dir, WorkspaceRequest request, Set<Path> moduleDirs, boolean testOnly) {
        return inputs(dir, request, moduleDirs, testOnly, request.target(), SiblingArtifacts.NONE);
    }

    /** As above for {@code target}: a goal whose {@link WorkspaceTarget#tests()} is NOT_RUN skips them whatever the request says. */
    static BuildPlanner.Inputs inputs(
            Path dir,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            boolean testOnly,
            WorkspaceTarget target,
            SiblingArtifacts.Gate siblings) {
        boolean skipTests = target.tests() == WorkspaceTarget.Tests.NOT_RUN || request.skipTests();
        return requestKnobs(request, moduleDirs, siblings)
                .apply(TaskForecaster.inputsFor(
                        dir,
                        request.cache(),
                        Math.max(0, request.workers()),
                        request.jdksDir(),
                        request.profile(),
                        skipTests,
                        request.verbose(),
                        moduleDirs,
                        testOnly,
                        target.compileOnly()));
    }
}
