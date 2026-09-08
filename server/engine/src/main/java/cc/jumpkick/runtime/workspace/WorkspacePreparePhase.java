// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Assembles plans for dirty modules and publishes the executable graph. */
@NullMarked
final class WorkspacePreparePhase {

    private WorkspacePreparePhase() {}

    sealed interface Outcome permits Completed, Ready {}

    record Completed(WorkspaceResult result) implements Outcome {}

    record Ready(Prepared prepared) implements Outcome {}

    /** Typed state handed from preparation to scheduling. */
    record Prepared(WorkspaceResourcePhase.Resources resources, Map<Path, ModulePlan> plans) {}

    static Outcome prepare(WorkspaceResourcePhase.Resources resources, WorkspaceBuildListener listener) {
        long started = Perf.start();
        int count = resources.dirtyUnits().size();
        listener.onPreflight("plan", 0, Math.max(count, 1), count == 0 ? "Nothing to prepare" : "Preparing modules…");
        Map<Path, ModulePlan> plans;
        try {
            plans = prepareModules(resources, listener, count);
        } catch (PrepareFailed failure) {
            ModuleOutcome outcome = new ModuleOutcome(failure.coord(), failure.dir(), false, 2, 0);
            listener.onModuleFinish(outcome);
            return new Completed(new WorkspaceResult(false, 2, List.of(outcome), List.of()));
        }
        if (count == 0) listener.onPreflight("plan", 1, 1, "Nothing to prepare");
        Perf.end(
                "ws-prepare-modules(dirty="
                        + count
                        + ",clean="
                        + resources.cleanUnits().size()
                        + ")",
                started);

        // A cancel during a parallel prepare leaves the map short of units; finish here rather
        // than announce a partial plan (List.copyOf rejects a null, and the verb's catch-all
        // was the only thing turning that NPE into a clean cancellation).
        if (SessionCancel.cancelled()) {
            return new Completed(new WorkspaceResult(false, 1, List.of(), List.of(), true));
        }
        listener.onPlan(List.copyOf(plans.values()));
        listener.onModuleGraph(resources.preflight().graph().edges());
        listener.onEtaEstimate(resources.etaMs());
        return new Ready(new Prepared(resources, Collections.unmodifiableMap(new LinkedHashMap<>(plans))));
    }

    private static Map<Path, ModulePlan> prepareModules(
            WorkspaceResourcePhase.Resources resources, WorkspaceBuildListener listener, int count) {
        if (resources.dirtyUnits().isEmpty()) return Map.of();
        if (count <= 1 || !prepareParallelEnabled()) {
            return prepareSerial(resources, listener, count);
        }
        return prepareParallel(resources, listener, count);
    }

    private static Map<Path, ModulePlan> prepareSerial(
            WorkspaceResourcePhase.Resources resources, WorkspaceBuildListener listener, int count) {
        Map<Path, ModulePlan> plans = new LinkedHashMap<>();
        int prepared = 0;
        for (BuildGraph.BuildUnit unit : resources.dirtyUnits()) {
            if (SessionCancel.cancelled()) break;
            ModulePlan plan = prepareModule(
                    unit,
                    resources.request(),
                    resources.preflight().moduleDirs(),
                    resources.preflight().jarConsumed(),
                    true);
            prepared++;
            listener.onPreflight(
                    "plan", prepared, count, "Preparing " + unit.coord() + " (" + prepared + "/" + count + ")");
            if (plan == null) throw new PrepareFailed(unit.coord(), unit.dir());
            attachTimings(plan, resources);
            plans.put(unit.dir(), plan);
        }
        return plans;
    }

    private static Map<Path, ModulePlan> prepareParallel(
            WorkspaceResourcePhase.Resources resources, WorkspaceBuildListener listener, int count) {
        AtomicInteger prepared = new AtomicInteger();
        Object preflightLock = new Object();
        Map<Path, ModulePlan> plans = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures =
                new ArrayList<>(resources.dirtyUnits().size());
        for (BuildGraph.BuildUnit unit : resources.dirtyUnits()) {
            if (SessionCancel.cancelled()) break;
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        if (SessionCancel.cancelled()) return;
                        ModulePlan plan = prepareModule(
                                unit,
                                resources.request(),
                                resources.preflight().moduleDirs(),
                                resources.preflight().jarConsumed(),
                                true);
                        if (plan == null) throw new PrepareFailed(unit.coord(), unit.dir());
                        attachTimings(plan, resources);
                        plans.put(unit.dir(), plan);
                        int complete = prepared.incrementAndGet();
                        synchronized (preflightLock) {
                            listener.onPreflight(
                                    "plan",
                                    complete,
                                    count,
                                    "Preparing " + unit.coord() + " (" + complete + "/" + count + ")");
                        }
                    },
                    JkThreads.io()));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof PrepareFailed failure) throw failure;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause);
        }
        return orderLikeUnits(resources.dirtyUnits(), plans);
    }

    /**
     * The prepared plans in unit order. A unit with no plan — a cancel stopped its task before it
     * ran — is left out, never carried as a null the plan list would choke on.
     */
    static Map<Path, ModulePlan> orderLikeUnits(List<BuildGraph.BuildUnit> units, Map<Path, ModulePlan> plans) {
        Map<Path, ModulePlan> ordered = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit unit : units) {
            ModulePlan plan = plans.get(unit.dir());
            if (plan != null) ordered.put(unit.dir(), plan);
        }
        return ordered;
    }

    private static void attachTimings(ModulePlan plan, WorkspaceResourcePhase.Resources resources) {
        plan.plan()
                .addListener(new StepTimingsRecorder(
                        plan.dir().toString(),
                        resources.timingSamples(),
                        () -> plan.plan().get(BuildPlanner.TEST_RESULT).orElse(null),
                        resources.hostSamples()));
    }

    /** Assemble one dirty module and preserve its live over-reserved work weight. */
    private static @Nullable ModulePlan prepareModule(
            BuildGraph.BuildUnit unit,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            Set<Path> jarConsumed,
            boolean forceRebuild) {
        Path dir = unit.dir();
        if (!Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return null;
        BuildPlan plan = assemblePlan(unit, request, moduleDirs, forceRebuild, jarConsumed);
        int weight = forceRebuild
                ? EffortWeights.withOverReserveTails(plan::estimatedTotalWeight)
                : plan.estimatedTotalWeight();
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        if (!distrust && !forceRebuild) {
            var shape = PreflightMemo.tryLoadShape(request.entryDir(), dir, request.skipTests());
            if (shape.isPresent()) {
                weight = shape.orElseThrow().weight();
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] shape-memo hit " + unit.coord() + " weight=" + weight);
                }
            } else {
                PreflightMemo.storeShape(
                        request.entryDir(), dir, request.skipTests(), PreflightMemo.shapeOf(plan, weight));
            }
        } else if (!distrust) {
            PreflightMemo.storeShape(request.entryDir(), dir, request.skipTests(), PreflightMemo.shapeOf(plan, weight));
        }
        return new ModulePlan(unit.dir(), unit.coord(), plan, weight, false, request.cache());
    }

    static BuildPlan assemblePlan(
            BuildGraph.BuildUnit unit, WorkspaceRequest request, Set<Path> moduleDirs, boolean forceRebuild) {
        return assemblePlan(unit, request, moduleDirs, forceRebuild, Set.of());
    }

    static BuildPlan assemblePlan(
            BuildGraph.BuildUnit unit,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            boolean forceRebuild,
            Set<Path> jarConsumed) {
        Path dir = unit.dir();
        WorkspaceTarget target = request.target();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        boolean selected = !spec.hasSelection()
                || spec.selectedModules().stream()
                        .anyMatch(path -> BuildGraph.canonicalPath(path).equals(BuildGraph.canonicalPath(dir)));
        UnaryOperator<BuildPlanner.Inputs> decorate = requestKnobs(request, moduleDirs);
        if (target == WorkspaceTarget.NATIVE) {
            Path graal = GraalHomes.lookup(dir, spec.graalByDir());
            return NativePlans.moduleBuildPlan(
                    dir,
                    unit.manifest(),
                    request.cache(),
                    request.jdksDir(),
                    graal,
                    spec.nativeMain(),
                    spec.nativeExtraArgs(),
                    request.skipTests(),
                    request.verbose(),
                    selected && graal != null,
                    decorate);
        }
        if (target == WorkspaceTarget.IMAGE && selected) {
            return ImagePlans.imageBuildPlan(
                    dir,
                    request.cache(),
                    request.jdksDir(),
                    request.skipTests(),
                    request.verbose(),
                    spec.imageMain(),
                    spec.imageRegistry(),
                    spec.imageTag(),
                    spec.imageTarball(),
                    spec.imageDocker(),
                    decorate);
        }
        boolean consumed = jarConsumed.contains(BuildGraph.canonicalPath(dir));
        // Compile-only is the tail of the cone. A module another module in this run compiles
        // against is resolved through its jar, so it packages even when it is in the selection —
        // the same rule test-only plans follow below, and packaging has already compiled it.
        if (target == WorkspaceTarget.COMPILE && selected && !consumed) {
            return CompilePlans.compileBuildPlan(dir, request.cache(), request.profile(), request.verbose(), decorate);
        }
        if (target == WorkspaceTarget.INSTALL) {
            Path graal = GraalHomes.lookup(dir, spec.graalByDir());
            BuildPlanner.Inputs inputs = moduleInputs(dir, request, moduleDirs, false);
            BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs, forceRebuild);
            PlannerTails.appendDeclaredTails(builder, inputs, graal, true);
            if (!CompileSupport.coordinatorOnly(unit.manifest(), dir)) {
                Path m2 = spec.m2Dir() != null ? spec.m2Dir() : Path.of(System.getProperty("user.home", "."), ".m2");
                InstallPlans.appendCacheInstall(builder, unit.manifest(), request.cache(), m2);
            }
            return builder.build();
        }
        boolean testOnly = (target.testOnly() || request.testOnly()) && !consumed;
        BuildPlanner.Inputs inputs = moduleInputs(dir, request, moduleDirs, testOnly);
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs, forceRebuild);
        if (!testOnly) {
            PlannerTails.appendDeclaredTails(builder, inputs, GraalHomes.lookup(dir, spec.graalByDir()), true);
        }
        return builder.build();
    }

    /** Apply request-wide planning knobs to every module input. */
    static UnaryOperator<BuildPlanner.Inputs> requestKnobs(WorkspaceRequest request, Set<Path> moduleDirs) {
        return inputs -> inputs.withWorkerCount(Math.max(0, request.workers()))
                .withProfileName(request.profile())
                .withProjectModules(moduleDirs)
                .withVariant(request.variant(), request.clientEnv())
                .withEphemeralActions(request.ephemeralActions());
    }

    static BuildPlanner.Inputs moduleInputs(
            Path dir, WorkspaceRequest request, Set<Path> moduleDirs, boolean testOnly) {
        return requestKnobs(request, moduleDirs)
                .apply(TaskForecaster.inputsFor(
                        dir,
                        request.cache(),
                        Math.max(0, request.workers()),
                        request.jdksDir(),
                        request.profile(),
                        request.skipTests(),
                        request.verbose(),
                        moduleDirs,
                        testOnly));
    }

    private static boolean prepareParallelEnabled() {
        return EnvValues.bool(System::getenv, "JK_PREPARE_PARALLEL").orElse(true);
    }

    private static final class PrepareFailed extends RuntimeException {
        private final String coord;
        private final Path dir;

        private PrepareFailed(String coord, Path dir) {
            super("prepare failed: " + coord);
            this.coord = coord;
            this.dir = dir;
        }

        private String coord() {
            return coord;
        }

        private Path dir() {
            return dir;
        }
    }
}
