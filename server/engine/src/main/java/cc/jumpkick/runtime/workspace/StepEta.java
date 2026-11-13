// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.StepEffort;
import cc.jumpkick.runtime.TestEffort;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.RungBias;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.runtime.base.SuiteSelection;
import cc.jumpkick.test.SuiteClassFilter;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** The build's estimate, priced step by step from the project's {@link StepLedger}. */
public final class StepEta {

    /**
     * Where {@code step} sits in its module's shape. The suite is TEST; the packaging tails and the
     * root's guard lanes (each a leaf of its own) run beside it as TAIL; the module's guard chain
     * runs beside it as one LANE (PlannerGuards: guard waits for the compiles, nothing waits for
     * it); the rest is the prefix.
     */
    public static StepCost.Kind kindOf(String step) {
        if (TaskNames.isRunTests(step)) return StepCost.Kind.TEST;
        if (TaskNames.PACKAGING_TAILS.contains(step)) return StepCost.Kind.TAIL;
        if (step.equals(TaskNames.GUARD) || step.equals(TaskNames.COMPILE_GUARD)) return StepCost.Kind.LANE;
        if (step.startsWith(TaskNames.GUARD + "-")) return StepCost.Kind.TAIL;
        return StepCost.Kind.PRE;
    }

    private StepEta() {}

    /**
     * The estimate priced step by step: each step the forecast runs at its own recent wall from the
     * project's {@link StepLedger} under this run's status and shape (the same work set first), a
     * step with no history at the module-weight price, and the schedule in ms at {@link
     * WorkModel#MS_PER_WEIGHT} with no learned scale on top: the steps' own walls already carry
     * what the scale stood in for.
     */
    static BuildService.EtaModel model(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency,
            BuildService.@Nullable EtaModel seed) {
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        Set<String> frontier = new TreeSet<>();
        int compilable = 0;
        int compiling = 0;
        for (TaskForecast.Module m : plan.modules()) {
            if (m.steps().stream().anyMatch(t -> StepLedger.isMainCompile(t.name()))) compilable++;
            if (m.steps().stream().anyMatch(t -> (distrust || !t.cached()) && StepLedger.isMainCompile(t.name()))) {
                compiling++;
            }
            // The run's work set: what else runs beside a step decides its wall as much as the
            // step does (the engine's suite took 43 s beside a build's packaging and 56 s beside
            // the CLI's suite), and the ledger records each run under the set it was priced with.
            if (distrust || m.steps().stream().anyMatch(t -> !t.cached()))
                frontier.add(m.dir().toString());
        }
        // A build that compiles most of its modules is a full rebuild whether or not it was forced:
        // the ledger records it as one (StepLedger.fullRebuild), so it is priced as one.
        boolean fullRebuild = distrust || StepLedger.fullRebuild(compiling, compilable);
        if (seed != null) {
            // A re-pricing keeps the seed's work set and shape: the compiles that have finished
            // since read as cached, and the run is still recorded under what it was priced with.
            frontier = new TreeSet<>(seed.frontier());
            fullRebuild = seed.fullRebuild();
        }
        RunShapes.Shape shape = new RunShapes.Shape(
                profile == null ? "" : profile,
                fullRebuild,
                !SessionContext.current().testSelection().classes().isEmpty());
        StepLedger ledger = StepLedger.load(ProjectBuilds.projectHome(entryDir));
        List<EffortWeights.ModuleCost> costs = new ArrayList<>();
        Map<Path, List<StepCost>> steps = new LinkedHashMap<>();
        Map<String, int[]> curves = new LinkedHashMap<>();
        Map<Path, long[]> suiteWalls = new HashMap<>();
        BuildMetrics metrics = BuildMetrics.current();
        StepTimings timings = StepTimings.load(cache);
        Calibration calibration = Calibration.load();
        StepEffort effort = StepEffort.load();
        RungBias bias = RungBias.load();
        List<String> projectDirs =
                plan.modules().stream().map(m -> m.dir().toString()).toList();
        // The javac worker this build compiles on: fresh after an engine start or once its linger
        // ran out. Compiles replay walls from the same kind of worker (CompilerWarmUp).
        boolean freshCompiler = CompilerWarmUp.warmth() <= 0;
        CompilerWarmUp warmUp = new CompilerWarmUp(calibration.effortScale());
        for (TaskForecast.Module m : pricedModules(plan, distrust)) {
            Path dir = m.dir();
            Set<Path> prereqs = plan.edges().getOrDefault(dir, Set.of());
            List<StepCost> own = new ArrayList<>();
            for (TaskForecast.Task t : stepsOf(m, distrust, ledger, shape)) {
                Priced priced = stepMs(
                        ledger,
                        shape,
                        frontier,
                        dir,
                        t,
                        distrust,
                        prereqs,
                        metrics,
                        timings,
                        projectDirs,
                        m,
                        calibration,
                        effort,
                        freshCompiler);
                priced = priced.corrected(bias.factor(priced.rung(), t.name()));
                if (priced.ms() <= 0) continue;
                StepCost.Kind kind = kindOf(t.name());
                // A cold suite is priced for one JVM and re-priced at its dispatch share below.
                int suiteWorkers = kind != StepCost.Kind.TEST || shape.filtered()
                        ? 0
                        : priced.cold() ? 1 : ledger.workers(dir.toString(), t.name(), "SUCCESS", shape, frontier);
                own.add(new StepCost(
                        t.name(),
                        weightOf(priced.ms()),
                        kind,
                        weightOf(priced.low()),
                        weightOf(priced.high()),
                        suiteWorkers,
                        priced.rung()));
            }
            for (StepCost c : own) {
                if (c.kind() != StepCost.Kind.TEST) continue;
                int[] curve = ledger.curve(dir.toString(), shape, frontier);
                if (curve != null) curves.put(dir.toString(), curve);
                if (!ledger.ownShape(dir.toString(), c.step(), "SUCCESS", shape, frontier))
                    suiteWalls.put(dir, ledger.classWalls(dir.toString(), c.step(), "SUCCESS", shape, frontier));
            }
            own = warmUp.apply(own, c -> warmUpDirection(c, ledger, dir, freshCompiler, distrust, m));
            if (own.isEmpty()) {
                costs.add(EffortWeights.costOf(dir, prereqs, EffortWeights.TOKEN, 0));
                continue;
            }
            costs.add(moduleCost(dir, prereqs, own));
            steps.put(dir, List.copyOf(own));
        }
        int concurrency = etaConcurrency(maxModuleConcurrency);
        boolean serialEta = concurrency <= 1;
        if (costs.isEmpty()) return new BuildService.EtaModel(0, costs, concurrency, serialEta);
        if (!serialEta && !shape.filtered()) atDispatchShares(costs, steps, suiteWalls, concurrency, workers);
        List<ModuleWorkCost> work = new ArrayList<>();
        for (EffortWeights.ModuleCost c : costs) work.add(c.toWorkCost());
        long ms = WorkSchedule.schedule(work, concurrency, serialEta, parallelTests) * WorkModel.MS_PER_WEIGHT;
        Perf.note(
                "eta-steps",
                "modules",
                costs.size(),
                "ms",
                ms,
                "ledgerRuns",
                ledger.runs(),
                "frontier",
                frontier.size());
        return new BuildService.EtaModel(
                ms, costs, concurrency, serialEta, ledger.runs() > 0, steps, frontier, curves, fullRebuild);
    }

    /**
     * How a javac compile's price is carried to this build's worker: a price from its own walls by
     * the worker they were measured on ({@link StepLedger#compilerBorrowed}). A prior is left as it
     * is: the cold compile prior was fitted to a first build, on a fresh worker, so it already
     * carries the warm-up; adding it again put the first project on a fresh engine at +76% for +1%.
     * A touch or a cached step is not a compile that runs.
     */
    private static CompilerWarmUp.Direction warmUpDirection(
            StepCost c, StepLedger ledger, Path dir, boolean freshCompiler, boolean distrust, TaskForecast.Module m) {
        if (c.rung() != StepCost.Rung.REPLAY) return CompilerWarmUp.Direction.NONE;
        return switch (ledger.compilerBorrowed(dir.toString(), c.step(), "SUCCESS", freshCompiler)) {
            case WARM_WALLS -> CompilerWarmUp.Direction.ADD;
            case FRESH_WALLS -> CompilerWarmUp.Direction.REMOVE;
            case NONE -> CompilerWarmUp.Direction.NONE;
        };
    }

    /** The modules an estimate prices: every dirty one, or all of them on a forced run. */
    static List<TaskForecast.Module> pricedModules(ExplainPlan plan, boolean distrust) {
        List<TaskForecast.Module> priced = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (distrust || m.dirty()) priced.add(m);
        }
        return priced;
    }

    /**
     * Module-concurrency budget for the ETA schedule: the executor's own cap, so explain and the
     * live countdown clamp the same way: the request's {@code -j} when it carries one, else the
     * engine's resolved jobs (every core).
     */
    static int etaConcurrency(int maxModuleConcurrency) {
        return Math.max(1, TestWorkers.jobsBudget(maxModuleConcurrency));
    }

    /** A module's cost from its steps: {@code prefix + max(suite, tail, lane)}, the gate inside the prefix. */
    private static EffortWeights.ModuleCost moduleCost(Path dir, Set<Path> prereqs, List<StepCost> own) {
        int pre = 0, test = 0, tail = 0, lane = 0, gate = 0;
        for (StepCost c : own) {
            switch (c.kind()) {
                case PRE -> pre += c.weight();
                case TEST -> test += c.weight();
                case TAIL -> tail = Math.max(tail, c.weight());
                case LANE -> lane += c.weight();
            }
            if (c.kind() == StepCost.Kind.PRE && EffortWeights.gatesDependents(c.step())) gate += c.weight();
        }
        tail = Math.max(tail, lane);
        return new EffortWeights.ModuleCost(dir, prereqs, pre + test + tail, test, tail, gate, 0, 0);
    }

    /**
     * Each suite re-priced at the test JVMs the build will give it: the executor picks them as the
     * suite dispatches, the jobs budget over the modules in flight then ({@code TestWorkers.liveShare},
     * floored at the plan's share), or the {@code -w} the user gave. The ledger's price is for the
     * JVMs its newest sample ran on: the engine's suite took 80 s on 2 in a first build, beside
     * every module, and the next body edit priced it at 80 s for a suite that got 8 and took 41 s.
     * The schedule says how many modules are in flight at each suite's dispatch; a suite is read at
     * that share through its class walls ({@link StepCost#scale}), and the share becomes the JVMs it
     * is priced for, which the live model re-prices from once the suite starts on its own count. A
     * suite whose sample ran on one JVM is left alone: it may be serial-tagged, and one JVM is what
     * it will get again. So is a suite priced from runs of this very shape and frontier
     * ({@link StepLedger#ownShape}): the same work beside it gave it the same JVMs each time.
     */
    private static void atDispatchShares(
            List<EffortWeights.ModuleCost> costs,
            Map<Path, List<StepCost>> steps,
            Map<Path, long[]> suiteWalls,
            int jobs,
            int requestedWorkers) {
        List<ModuleWorkCost> work = new ArrayList<>();
        for (EffortWeights.ModuleCost c : costs) work.add(c.toWorkCost());
        Map<Path, Integer> inFlight = WorkSchedule.inFlightAtDispatch(work, jobs);
        int widest =
                inFlight.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int planShare = TestWorkers.autoShare(jobs, widest);
        for (int i = 0; i < costs.size(); i++) {
            Path dir = costs.get(i).dir();
            List<StepCost> own = steps.get(dir);
            Integer running = inFlight.get(dir);
            long[] walls = suiteWalls.get(dir);
            if (own == null || running == null || walls == null) continue;
            // The JVMs the launcher will start: the live share, capped as autoCount caps it by the
            // suite's class walls. The engine's suite got 5 of a share of 8 (its walls over its
            // longest class), and priced at 8 it read 44 s for 70 (JK-EQVYV).
            int share = requestedWorkers > 0
                    ? requestedWorkers
                    : TestWorkers.autoCount(TestWorkers.liveShare(planShare, jobs, running), dir, List.of(), 0);
            List<StepCost> repriced = new ArrayList<>(own.size());
            boolean moved = false;
            for (StepCost c : own) {
                if (c.kind() != StepCost.Kind.TEST || c.workers() <= 1 || c.workers() == share) {
                    repriced.add(c);
                    continue;
                }
                double f = StepCost.scale(c.workers(), share, walls[0], walls[1]);
                repriced.add(c.repriced(
                        (int) Math.max(1, Math.round(c.weight() * f)),
                        (int) Math.round(c.low() * f),
                        (int) Math.round(c.high() * f),
                        share));
                moved = true;
            }
            if (!moved) continue;
            steps.put(dir, List.copyOf(repriced));
            costs.set(i, moduleCost(dir, costs.get(i).prereqs(), repriced));
        }
    }

    /**
     * One forecast step's price in ms: its own recent wall under the status the forecast expects (a
     * cache hit is a restore, not a run) and this run's shape, else the module-weight price of that
     * step alone; a forecast-only verdict (an order or dependency check) is a touch.
     */
    /**
     * A step's price in ms and its likely range: the ledger's own spread for a step with history;
     * a third either side for a filtered suite priced from its classes' walls; half to twice for a
     * step priced from the module weights with no history of its own, which is a guess.
     */
    record Priced(long ms, long low, long high, boolean cold, StepCost.Rung rung) {
        static Priced exact(long ms, StepCost.Rung rung) {
            return new Priced(ms, ms, ms, false, rung);
        }

        static Priced spread(long ms, double down, double up, StepCost.Rung rung) {
            return new Priced(ms, Math.round(ms * down), Math.round(ms * up), false, rung);
        }

        /**
         * This price corrected by its rung's bias on this host ({@link RungBias}): how far that layer
         * has missed for this kind of step in the runs since it was first used here.
         */
        Priced corrected(double factor) {
            if (factor == 1) return this;
            return new Priced(Math.round(ms * factor), Math.round(low * factor), Math.round(high * factor), cold, rung);
        }

        /** A first build's price from the host's calibration: half to twice, a suite for one JVM. */
        static Priced cold(long ms) {
            return new Priced(ms, Math.round(ms * 0.5), Math.round(ms * 2.0), true, StepCost.Rung.CALIBRATION);
        }
    }

    /**
     * The steps {@code m} runs: its forecast's, or, for a forced build's module the forecast lists
     * with none (a clean module reads as cached, and a forced build runs it all again), the steps
     * the last run of this shape ran there. Priced as a token before: on jk's own tree a forced
     * build left 36 of its 61 modules (about 400 s of compiles and suites) out of the estimate.
     */
    static List<TaskForecast.Task> stepsOf(
            TaskForecast.Module m, boolean distrust, StepLedger ledger, RunShapes.Shape shape) {
        if (!distrust || !m.steps().isEmpty()) return m.steps();
        List<TaskForecast.Task> out = new ArrayList<>();
        for (String step : ledger.stepsRun(m.dir().toString(), shape)) {
            out.add(new TaskForecast.Task(step, TaskForecast.Status.RUN, "", null));
        }
        return out;
    }

    /** A step's ms in schedule weight units, rounded up so a short step still weighs one. */
    private static int weightOf(long ms) {
        return (int) Math.max(1, (ms + WorkModel.MS_PER_WEIGHT - 1) / WorkModel.MS_PER_WEIGHT);
    }

    private static Priced stepMs(
            StepLedger ledger,
            RunShapes.Shape shape,
            Set<String> frontier,
            Path dir,
            TaskForecast.Task t,
            boolean distrust,
            Set<Path> prereqs,
            BuildMetrics metrics,
            StepTimings timings,
            List<String> projectDirs,
            TaskForecast.Module m,
            Calibration calibration,
            StepEffort effort,
            boolean freshCompiler) {
        String name = t.name();
        if (TaskNames.ORDER_CHECK.equals(name)
                || TaskNames.DEPENDENCY_CHECK.equals(name)
                || TaskNames.RESTORE_OUTPUTS.equals(name)) {
            return Priced.exact(EffortWeights.TOKEN * (long) EffortWeights.MS_PER_WEIGHT, StepCost.Rung.TOKEN);
        }
        boolean runs = distrust || !t.cached();
        if (runs && shape.filtered() && TaskNames.isRunTests(name)) {
            OptionalLong narrowed = filteredSuiteMs(dir);
            if (narrowed.isPresent()) return Priced.spread(narrowed.getAsLong(), 0.7, 1.3, StepCost.Rung.MODULE);
        }
        String status = runs ? "SUCCESS" : "SKIPPED";
        // A node step or plugin goal measured only in the project's first build replays a download
        // into an empty cache: its effort price is the better guess until a second run measures it.
        boolean firstOnly = runs && StepEffort.prices(name) && ledger.firstRunOnly(dir.toString(), name, status);
        var own = firstOnly
                ? OptionalLong.empty()
                : ledger.price(dir.toString(), name, status, shape, frontier, freshCompiler);
        if (own.isPresent()) {
            long ms = own.getAsLong();
            long[] range = ledger.range(dir.toString(), name, status, shape, frontier, freshCompiler)
                    .orElse(new long[] {ms, ms});
            return new Priced(ms, range[0], range[1], false, StepCost.Rung.REPLAY);
        }
        if (!runs) return Priced.exact(0, StepCost.Rung.TOKEN);
        // A node step or plugin goal this module has no walls of: its kind on this host at its
        // measure (StepEffort), or the cold set's price for it. Priced as a touch before, a 6 s
        // node install and a 3.5 s AOT run each at 150 ms.
        if (StepEffort.prices(name)) {
            StepEffort.Price p = effort.price(name, StepEffort.measure(name, dir), calibration.effortScale());
            if (p != null)
                return Priced.spread(p.ms(), 0.5, 2.0, p.learned() ? StepCost.Rung.HOST : StepCost.Rung.CALIBRATION);
        }
        if (name.startsWith("compile-") && t.status() != TaskForecast.Status.CACHED) {
            OptionalLong cold =
                    ledger.coldCompile(dir.toString(), name, status, shape, t.status() == TaskForecast.Status.PARTIAL);
            // A full compile is read from this module's own full rebuild; a partial one from the
            // project's other modules' (StepLedger.coldCompile).
            boolean partial = t.status() == TaskForecast.Status.PARTIAL;
            if (cold.isPresent())
                return Priced.spread(
                        cold.getAsLong(), 0.5, 2.0, partial ? StepCost.Rung.PROJECT : StepCost.Rung.MODULE);
            // Nothing of the step in the ledger: a first build's compile, from the host's calibration.
            // The test compiles' sources are not counted; a test class holds about five methods.
            int sources =
                    switch (name) {
                        case TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY ->
                            m.sourceCount();
                        case TaskNames.COMPILE_TEST -> m.testCount() / 5;
                        default -> 1;
                    };
            return Priced.cold(calibration.coldCompileMs(sources));
        }
        if (TaskNames.RUN_TESTS.equals(name) && m.testCount() > 0) {
            return Priced.cold(calibration.coldSuiteMs(m.testCount()));
        }
        Map<String, Integer> counts = new HashMap<>();
        if (m.testCount() > 0) counts.put(TaskNames.RUN_TESTS, m.testCount());
        if (m.sourceCount() > 0) {
            counts.put(TaskNames.COMPILE_JAVA, m.sourceCount());
            counts.put(TaskNames.COMPILE_TEST, m.sourceCount());
        }
        String metricsName = TaskNames.RUN_TESTS.equals(name) ? SuiteSelection.current() : name;
        EffortWeights.ModuleCost alone = EffortWeights.costFromRunningSteps(
                dir, prereqs, List.of(metricsName), metrics, timings, projectDirs, counts);
        return Priced.spread((long) alone.weight() * EffortWeights.MS_PER_WEIGHT, 0.5, 2.0, StepCost.Rung.HOST);
    }

    /**
     * A {@code --class} run's suite in {@code dir}: its test JVM and the recorded walls of the
     * classes the patterns select, or a touch when they select none of its classes (the module skips
     * without a JVM). Empty when the module has no class walls to judge by. The suite's own wall is
     * no price here: it is every class, and the run is a handful.
     */
    private static OptionalLong filteredSuiteMs(Path dir) {
        Map<String, Long> walls = EffortWeights.loadClassWalls(dir.toString(), SuiteSelection.current());
        if (walls.isEmpty()) return OptionalLong.empty();
        long ms = TestEffort.selectedClassesMillis(
                walls,
                SuiteClassFilter.selects(
                        SessionContext.current().testSelection().classes()));
        return OptionalLong.of(ms > 0 ? ms : EffortWeights.TOKEN * (long) EffortWeights.MS_PER_WEIGHT);
    }
}
