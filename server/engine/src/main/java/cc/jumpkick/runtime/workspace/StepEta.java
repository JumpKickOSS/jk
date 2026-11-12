// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.TestEffort;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.RunShapes;
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
            int maxModuleConcurrency) {
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
        RunShapes.Shape shape = new RunShapes.Shape(
                profile == null ? "" : profile,
                distrust || StepLedger.fullRebuild(compiling, compilable),
                !SessionContext.current().testSelection().classes().isEmpty());
        StepLedger ledger = StepLedger.load(ProjectBuilds.projectHome(entryDir));
        List<EffortWeights.ModuleCost> costs = new ArrayList<>();
        Map<Path, List<StepCost>> steps = new LinkedHashMap<>();
        Map<String, int[]> curves = new LinkedHashMap<>();
        Map<Path, long[]> suiteWalls = new HashMap<>();
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        StepTimings timings = StepTimings.load(cache);
        List<String> projectDirs =
                plan.modules().stream().map(m -> m.dir().toString()).toList();
        for (TaskForecast.Module m : BuildEta.pricedModules(plan, distrust)) {
            Path dir = m.dir();
            Set<Path> prereqs = plan.edges().getOrDefault(dir, Set.of());
            List<StepCost> own = new ArrayList<>();
            for (TaskForecast.Task t : stepsOf(m, distrust, ledger, shape)) {
                Priced priced =
                        stepMs(ledger, shape, frontier, dir, t, distrust, prereqs, metrics, timings, projectDirs, m);
                if (priced.ms() <= 0) continue;
                StepCost.Kind kind = kindOf(t.name());
                int suiteWorkers = kind == StepCost.Kind.TEST && !shape.filtered()
                        ? ledger.workers(dir.toString(), t.name(), "SUCCESS", shape, frontier)
                        : 0;
                own.add(new StepCost(
                        t.name(),
                        weightOf(priced.ms()),
                        kind,
                        weightOf(priced.low()),
                        weightOf(priced.high()),
                        suiteWorkers));
            }
            for (StepCost c : own) {
                if (c.kind() != StepCost.Kind.TEST) continue;
                int[] curve = ledger.curve(dir.toString(), shape, frontier);
                if (curve != null) curves.put(dir.toString(), curve);
                if (!ledger.ownShape(dir.toString(), c.step(), "SUCCESS", shape, frontier))
                    suiteWalls.put(dir, ledger.classWalls(dir.toString(), c.step(), "SUCCESS", shape, frontier));
            }
            if (own.isEmpty()) {
                costs.add(EffortWeights.costOf(dir, prereqs, EffortWeights.TOKEN, 0));
                continue;
            }
            costs.add(moduleCost(dir, prereqs, own));
            steps.put(dir, List.copyOf(own));
        }
        int concurrency = BuildEta.etaConcurrency(maxModuleConcurrency);
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
        return new BuildService.EtaModel(ms, costs, concurrency, serialEta, ledger.runs() > 0, steps, frontier, curves);
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
            int share = requestedWorkers > 0 ? requestedWorkers : TestWorkers.liveShare(planShare, jobs, running);
            List<StepCost> repriced = new ArrayList<>(own.size());
            boolean moved = false;
            for (StepCost c : own) {
                if (c.kind() != StepCost.Kind.TEST || c.workers() <= 1 || c.workers() == share) {
                    repriced.add(c);
                    continue;
                }
                double f = StepCost.scale(c.workers(), share, walls[0], walls[1]);
                repriced.add(new StepCost(
                        c.step(),
                        (int) Math.max(1, Math.round(c.weight() * f)),
                        c.kind(),
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
    record Priced(long ms, long low, long high) {
        static Priced exact(long ms) {
            return new Priced(ms, ms, ms);
        }

        static Priced spread(long ms, double down, double up) {
            return new Priced(ms, Math.round(ms * down), Math.round(ms * up));
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
            TaskForecast.Module m) {
        String name = t.name();
        if (TaskNames.ORDER_CHECK.equals(name)
                || TaskNames.DEPENDENCY_CHECK.equals(name)
                || TaskNames.RESTORE_OUTPUTS.equals(name)) {
            return Priced.exact(EffortWeights.TOKEN * (long) EffortWeights.MS_PER_WEIGHT);
        }
        boolean runs = distrust || !t.cached();
        if (runs && shape.filtered() && TaskNames.isRunTests(name)) {
            OptionalLong narrowed = filteredSuiteMs(dir);
            if (narrowed.isPresent()) return Priced.spread(narrowed.getAsLong(), 0.7, 1.3);
        }
        String status = runs ? "SUCCESS" : "SKIPPED";
        var own = ledger.price(dir.toString(), name, status, shape, frontier);
        if (own.isPresent()) {
            long ms = own.getAsLong();
            long[] range =
                    ledger.range(dir.toString(), name, status, shape, frontier).orElse(new long[] {ms, ms});
            return new Priced(ms, range[0], range[1]);
        }
        if (!runs) return Priced.exact(0);
        if (name.startsWith("compile-") && t.status() != TaskForecast.Status.CACHED) {
            OptionalLong cold =
                    ledger.coldCompile(dir.toString(), name, status, shape, t.status() == TaskForecast.Status.PARTIAL);
            if (cold.isPresent()) return Priced.spread(cold.getAsLong(), 0.5, 2.0);
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
        return Priced.spread((long) alone.weight() * EffortWeights.MS_PER_WEIGHT, 0.5, 2.0);
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
