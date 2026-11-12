// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ProcessEnv;
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

/** The estimate priced step by step, beside {@link BuildEta}'s module-weight estimate. */
final class StepEta {

    private StepEta() {}

    /**
     * Whether the estimate is priced step by step from the project's {@link StepLedger}, with a
     * live remainder that counts each module down by its steps. {@code JK_ETA_STEPS=0} keeps the
     * module-weight model, so the two can be measured on one engine.
     */
    static boolean stepPriced() {
        return EnvValues.bool(ProcessEnv::get, "JK_ETA_STEPS").orElse(true);
    }

    /**
     * The estimate priced step by step: each step the forecast runs at its own recent wall from the
     * project's {@link StepLedger} under this run's status and shape (the same frontier first), a
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
            boolean workerWarm) {
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        RunShapes.Shape shape = new RunShapes.Shape(
                profile == null ? "" : profile,
                distrust,
                !SessionContext.current().testSelection().classes().isEmpty());
        Set<String> frontier = new TreeSet<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (m.steps().stream().anyMatch(t -> (distrust || !t.cached()) && BuildEta.isCompileStepName(t.name()))) {
                frontier.add(m.dir().toString());
            }
        }
        StepLedger ledger = StepLedger.load(ProjectBuilds.projectHome(entryDir));
        List<EffortWeights.ModuleCost> costs = new ArrayList<>();
        Map<Path, List<StepCost>> steps = new LinkedHashMap<>();
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        StepTimings timings = StepTimings.load(cache);
        List<String> projectDirs =
                plan.modules().stream().map(m -> m.dir().toString()).toList();
        for (TaskForecast.Module m : BuildEta.pricedModules(plan, distrust)) {
            Path dir = m.dir();
            Set<Path> prereqs = plan.edges().getOrDefault(dir, Set.of());
            List<StepCost> own = new ArrayList<>();
            for (TaskForecast.Task t : m.steps()) {
                Priced priced =
                        stepMs(ledger, shape, frontier, dir, t, distrust, prereqs, metrics, timings, projectDirs, m);
                if (priced.ms() <= 0) continue;
                StepCost.Kind kind = TaskNames.isRunTests(t.name())
                        ? StepCost.Kind.TEST
                        : TaskNames.PACKAGING_TAILS.contains(t.name()) ? StepCost.Kind.TAIL : StepCost.Kind.PRE;
                own.add(new StepCost(
                        t.name(), weightOf(priced.ms()), kind, weightOf(priced.low()), weightOf(priced.high())));
            }
            if (own.isEmpty()) {
                costs.add(EffortWeights.costOf(dir, prereqs, EffortWeights.TOKEN, 0));
                continue;
            }
            int pre = 0, test = 0, tail = 0, gate = 0;
            for (StepCost c : own) {
                switch (c.kind()) {
                    case PRE -> pre += c.weight();
                    case TEST -> test += c.weight();
                    case TAIL -> tail = Math.max(tail, c.weight());
                }
                if (c.kind() == StepCost.Kind.PRE && EffortWeights.gatesDependents(c.step())) gate += c.weight();
            }
            costs.add(new EffortWeights.ModuleCost(dir, prereqs, pre + test + tail, test, tail, gate, 0, 0));
            steps.put(dir, List.copyOf(own));
        }
        int concurrency = BuildEta.etaConcurrency(maxModuleConcurrency);
        boolean serialEta = concurrency <= 1;
        if (costs.isEmpty()) return new BuildService.EtaModel(0, costs, concurrency, serialEta);
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
                ms, costs, concurrency, serialEta, ms, ledger.runs() > 0, BuildEta.buildShape(plan), workerWarm, steps);
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
