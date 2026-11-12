// SPDX-License-Identifier: Apache-2.0
//
// Replay recorded runs through live remaining-work models, offline.
//
//   java -cp <dist>/lib/jk-engine-<v>.jar bench/eta/Replay.java <run dir>... [--series] [--models V0,V2a]
//
// A run directory holds eta-model.jsonl (the model the countdown was seeded from, EtaModels) and
// details.jsonl (every module's plan progress and finish). Replay seeds each model from the
// recorded seed and feeds it the recorded events in order, exactly as the engine's SsePublisher
// feeds RemainingWork, then reports:
//
//   - fidelity (V0 only): the replayed remaining against the remaining the engine emitted. V0 is
//     RemainingWork from the jar, so a gap means the replay misses an input.
//   - accuracy, per model: the remaining it would have shown against the true remaining (the
//     run's end minus now), and the bar it would have drawn (elapsed / (elapsed + R), peak-held,
//     capped at 99%) against the elapsed share, at 10/25/50/75/90% of the wall.
//
// The models other than V0 are candidate live models (plan P2), tried here before any of them
// goes into the engine. Each is a pure function of the recorded seed and events.

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.SuiteCurve;
import cc.jumpkick.runtime.workspace.StepEta;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class Replay {

    static final double[] CHECKPOINTS = {0.10, 0.25, 0.50, 0.75, 0.90};
    static final double[] SCORED = {0.25, 0.50, 0.75};
    static final int MS_PER_WEIGHT = 150;
    /** A running step past its priced wall still keeps this share of it as remaining. */
    static final double CLOCK_FLOOR = 0.05;
    /** A suite's fork and engine start-up, priced on a filtered run beside its classes' walls. */
    static final long SUITE_STARTUP_MS = 1500;
    /** Once a module runs past its estimate, at least this share of its elapsed time is assumed to remain. */
    static final double OVERRUN_SHARE = 0.25;

    /** What every model sees: the seed, module progress and finishes, and the clock. */
    static final class State {
        List<ModuleWorkCost> costs = List.of();
        long r0;
        int concurrency;
        boolean serial;
        boolean parallelTests;
        long seededAt;
        double weightToMs = MS_PER_WEIGHT;
        long ideal0;
        final Map<Path, Double> frac = new HashMap<>();
        final Map<Path, Long> started = new HashMap<>();
        final Set<Path> done = new LinkedHashSet<>();
        /** Per module, per step: {ticks the step declared, ticks it has reported}. */
        final Map<Path, Map<String, long[]>> steps = new HashMap<>();
        /** Per module: the plan's total ticks, as the last progress event stated it. */
        final Map<Path, Long> den = new HashMap<>();
        /** Per module: steps started and finished (any step but the suite and the tails). */
        final Map<Path, Set<String>> preStarted = new HashMap<>();

        final Map<Path, Set<String>> preFinished = new HashMap<>();
        /** Per module: the suite's tick total, read when it first reports (the plan's ticks not yet spent). */
        final Map<Path, Long> suiteTicks = new HashMap<>();

        final Map<Path, Long> tailTicks = new HashMap<>();
        final Map<Path, ModuleWorkCost> costOf = new HashMap<>();
        final Map<Path, Long> reported = new HashMap<>();
        /** Per module: the ticks resolve-deps reported (its whole weight, reported at once). */
        final Map<Path, Long> resolveTicks = new HashMap<>();
        /** Per module: when its suite and its tail started (task-start). */
        final Map<Path, Long> suiteStart = new HashMap<>();

        final Map<Path, Long> tailStart = new HashMap<>();
        /** Per module: when each step started, and the steps finished. */
        final Map<Path, Map<String, Long>> stepStart = new HashMap<>();

        final Map<Path, Set<String>> stepDone = new HashMap<>();
        /** Per module: test classes finished so far, with their walls. */
        final Map<Path, Map<String, Long>> classesDone = new HashMap<>();

        void seed(List<ModuleWorkCost> c, long r0, int conc, boolean serial, boolean pt, long at) {
            this.costs = c;
            this.r0 = r0;
            this.concurrency = conc;
            this.serial = serial;
            this.parallelTests = pt;
            this.seededAt = at;
            this.ideal0 = WorkSchedule.schedule(c, conc, serial, pt);
            for (ModuleWorkCost m : c) costOf.put(m.dir(), m);
            this.weightToMs = ideal0 > 0 ? (double) r0 / ideal0 : MS_PER_WEIGHT;
        }
    }

    interface Model {
        String name();

        long remaining(State s, long now);
    }

    /**
     * Today's engine model fed the module fraction with resolve-deps' weight taken out of both
     * sides: what the engine would show once that step stops reserving a fetch per locked artifact.
     */
    static final class V6 implements Model {
        RemainingWork rw;

        public String name() {
            return "V6 engine, no resolve-deps fetch weight";
        }

        public long remaining(State s, long now) {
            return rw == null ? -1 : rw.remaining();
        }
    }

    /** Today's engine model, from the jar. Driven alongside the state so fidelity is checked. */
    static final class V0 implements Model {
        RemainingWork rw;

        public String name() {
            return "V0 engine (global scale, tick fraction)";
        }

        public long remaining(State s, long now) {
            return rw == null ? -1 : rw.remaining();
        }
    }

    /**
     * The residual schedule of pending modules, each module's remaining share chosen by {@code
     * left}, in ms by {@code scale}.
     */
    abstract static class Residual implements Model {
        abstract double scaleMs(State s);

        abstract double left(State s, ModuleWorkCost c, long now, double scaleMs);

        long overhead(State s, long now) {
            return 0;
        }

        /** The priced modules this model schedules: the recorded seed's, unless a model prices its own. */
        List<ModuleWorkCost> costs(State s) {
            return s.costs;
        }

        public long remaining(State s, long now) {
            List<ModuleWorkCost> costs = costs(s);
            if (costs.isEmpty()) return -1;
            double scale = scaleMs(s);
            List<ModuleWorkCost> residual = new ArrayList<>();
            for (ModuleWorkCost c : costs) {
                if (s.done.contains(c.dir())) continue;
                double left = left(s, c, now, scale);
                ModuleWorkCost r = left >= 1.0 ? c : scaleCost(c, left);
                if (r.weight() > 0 || r.testWeight() > 0) residual.add(r);
            }
            long ideal =
                    residual.isEmpty() ? 0 : WorkSchedule.schedule(residual, s.concurrency, s.serial, s.parallelTests);
            return Math.round(ideal * scale) + overhead(s, now);
        }
    }

    /** No global scale: weights in their own unit. */
    static final class V1 extends Residual {
        public String name() {
            return "V1 no global scale, tick fraction";
        }

        double scaleMs(State s) {
            return MS_PER_WEIGHT;
        }

        double left(State s, ModuleWorkCost c, long now, double scale) {
            return 1.0 - s.frac.getOrDefault(c.dir(), 0.0);
        }
    }

    /**
     * A running module's remaining share is the larger of what its plan ticks and its clock say;
     * once it runs past its estimate, a share of its elapsed time is assumed to remain.
     */
    static class V2 extends Residual {
        final boolean globalScale;

        V2(boolean globalScale) {
            this.globalScale = globalScale;
        }

        public String name() {
            return globalScale ? "V2a time-aware, global scale" : "V2b time-aware, no global scale";
        }

        double scaleMs(State s) {
            return globalScale ? s.weightToMs : MS_PER_WEIGHT;
        }

        double left(State s, ModuleWorkCost c, long now, double scale) {
            double ticks = 1.0 - s.frac.getOrDefault(c.dir(), 0.0);
            Long start = s.started.get(c.dir());
            if (start == null || c.weight() <= 0) return ticks;
            double estMs = c.weight() * scale;
            double elapsed = Math.max(0, now - start);
            double clock = Math.max(0, 1.0 - elapsed / estMs);
            double overrun = OVERRUN_SHARE * elapsed / estMs;
            return Math.max(ticks, Math.max(clock, overrun));
        }
    }

    /**
     * V2b plus the seed's gap over the raw schedule (R0 minus the schedule at weight scale) as an
     * overhead that runs down with the clock instead of leaving with the modules.
     */
    static final class V3 extends V2 {
        V3() {
            super(false);
        }

        @Override
        public String name() {
            return "V3 time-aware, seed gap as clock overhead";
        }

        @Override
        long overhead(State s, long now) {
            long raw = s.ideal0 * MS_PER_WEIGHT;
            long gap = Math.max(0, s.r0 - raw);
            if (gap == 0 || s.r0 <= 0) return 0;
            double used = Math.min(1.0, Math.max(0, now - s.seededAt) / (double) s.r0);
            return Math.round(gap * (1.0 - used));
        }
    }

    /**
     * Ticks are spread evenly over a module's steps, which take very different times (a 6 s
     * compile can carry 42% of a module's ticks ahead of its 80 s suite). This model reads each
     * step's progress inside its own share of the module's price: the suite's ticks move the test
     * weight, a packaging tail's the tail weight, every other step's the rest. A step not yet
     * started counts as none done; the pre-suite part's tick total is the plan's, less the suite's
     * and the tail's once they declare theirs.
     */
    static class V4 extends Residual {
        final boolean globalScale;
        final boolean timeAware;

        V4(boolean globalScale, boolean timeAware) {
            this.globalScale = globalScale;
            this.timeAware = timeAware;
        }

        public String name() {
            return (timeAware ? "V4" + (globalScale ? "c" : "d") : "V4" + (globalScale ? "a" : "b")) + " step-weighted"
                    + (timeAware ? ", time floor" : "") + (globalScale ? ", global scale" : ", no global scale");
        }

        double scaleMs(State s) {
            return globalScale ? s.weightToMs : MS_PER_WEIGHT;
        }

        double left(State s, ModuleWorkCost c, long now, double scale) {
            int weight = c.weight();
            if (weight <= 0) return 1.0 - s.frac.getOrDefault(c.dir(), 0.0);
            Map<String, long[]> steps = s.steps.getOrDefault(c.dir(), Map.of());
            long testTotal = 0, testDone = 0, tailTotal = 0, tailDone = 0, preDone = 0;
            for (var e : steps.entrySet()) {
                long[] t = e.getValue();
                if (TaskNames.isRunTests(e.getKey())) {
                    testTotal += t[0];
                    testDone += t[1];
                } else if (TaskNames.PACKAGING_TAILS.contains(e.getKey())) {
                    tailTotal = Math.max(tailTotal, t[0]);
                    tailDone = Math.max(tailDone, t[1]);
                } else preDone += t[1];
            }
            long den = s.den.getOrDefault(c.dir(), 0L);
            long preTotal = Math.max(preDone, den - testTotal - tailTotal);
            int test = c.testWeight();
            int tail = c.tailWeight();
            int pre = Math.max(0, weight - test - tail);
            double preLeft = preTotal <= 0 ? 1 : 1.0 - Math.min(1.0, preDone / (double) preTotal);
            double testLeft = testTotal <= 0 ? 1 : 1.0 - Math.min(1.0, testDone / (double) testTotal);
            double tailLeft = tailTotal <= 0 ? 1 : 1.0 - Math.min(1.0, tailDone / (double) tailTotal);
            double leftW = pre * preLeft + test * testLeft + tail * tailLeft;
            double share = leftW / weight;
            if (!timeAware) return share;
            Long start = s.started.get(c.dir());
            if (start == null) return share;
            double estMs = weight * scale;
            double elapsed = Math.max(0, now - start);
            double overrun = OVERRUN_SHARE * elapsed / estMs;
            return Math.max(
                    share,
                    Math.min(
                            overrun, 1.0 - Math.min(1.0, (preDone + testDone + tailDone) / (double) Math.max(1, den))));
        }
    }

    /**
     * Step history across every replayed run: module dir -> step -> status -> (run, wall ms). The
     * pricing candidates read it leave-one-out, so a run is never priced from itself.
     */
    static final Map<String, Map<String, Map<String, List<long[]>>>> HISTORY = new HashMap<>();

    /** Class walls: module dir -> suite under its shape -> class -> (run, wall ms). */
    static final Map<String, Map<String, Map<String, List<long[]>>>> CLASS_HISTORY = new HashMap<>();

    /**
     * A filtered run's selected classes, by module: the selection is resolved before the suite
     * starts, so the pricer knows it; the replay reads it from the classes the run finished.
     */
    static final Map<Path, Map<String, Set<String>>> SELECTED = new HashMap<>();

    /** Median wall of a class in the module's history, leave-one-out; -1 when none. */
    static long classMs(String dir, String cls, long runId) {
        long[] walls = CLASS_HISTORY.getOrDefault(dir, Map.of()).values().stream()
                .flatMap(m -> m.getOrDefault(cls, List.of()).stream())
                .filter(r -> r[0] != runId)
                .mapToLong(r -> r[1])
                .sorted()
                .toArray();
        return walls.length == 0 ? -1 : walls[walls.length / 2];
    }

    /** Each run's shape signature: profile, forced, filtered, and the frontier (modules that compile). */
    static final Map<Long, String> SHAPE = new HashMap<>();

    /** Each run's test profile ("" for the default tier). */
    static final Map<Path, String> PROFILE = new HashMap<>();

    /** The steps every run reported, by run: module dir -> (step, status, wall ms) in finish order. */
    static final Map<Path, Map<String, List<String[]>>> RAN = new HashMap<>();

    static void collectHistory(Path run, long runId) throws Exception {
        Path details = run.resolve("details.jsonl");
        if (!Files.isRegularFile(details)) return;
        Map<String, String> dirOfCoord = new HashMap<>();
        Map<String, List<String[]>> ran = new LinkedHashMap<>();
        String profile = "";
        boolean forced = false;
        boolean filtered = false;
        Map<String, Set<String>> classesRan = new HashMap<>();
        for (String line : Files.readAllLines(details)) {
            if (line.isBlank()) continue;
            if (!(MiniJson.parseRelaxed(line) instanceof Map<?, ?> e)) continue;
            String type = String.valueOf(e.get("type"));
            if (type.equals("session-start")) {
                List<?> argv = e.get("argv") instanceof List<?> l ? l : List.of();
                for (int i = 0; i < argv.size(); i++) {
                    String a = String.valueOf(argv.get(i));
                    if ((a.equals("-p") || a.equals("--profile")) && i + 1 < argv.size())
                        profile = String.valueOf(argv.get(i + 1));
                    if (a.equals("-F") || a.equals("--force") || a.equals("--rebuild")) forced = true;
                    if (a.startsWith("--class") || a.startsWith("--method") || a.startsWith("--tag")) filtered = true;
                }
            } else if (type.equals("module-start") && e.get("dir") != null && e.get("coord") != null) {
                dirOfCoord.put(e.get("coord").toString(), e.get("dir").toString());
            } else if (type.equals("class-finish")) {
                String dir = dirOf(e, dirOfCoord);
                if (dir == null || e.get("testClass") == null || e.get("task") == null) continue;
                classesRan
                        .computeIfAbsent(dir, k -> new LinkedHashSet<>())
                        .add(e.get("testClass").toString());
                CLASS_HISTORY
                        .computeIfAbsent(dir, k -> new HashMap<>())
                        .computeIfAbsent(shapeKey(e.get("task").toString(), profile, forced), k -> new HashMap<>())
                        .computeIfAbsent(e.get("testClass").toString(), k -> new ArrayList<>())
                        .add(new long[] {runId, Math.max(0, num(e, "millis"))});
            } else if (type.equals("task-finish")) {
                String dir = dirOf(e, dirOfCoord);
                if (dir == null || e.get("task") == null) continue;
                String step = shapeKey(e.get("task").toString(), profile, forced);
                // A filtered suite is not the suite: it never enters the suite's own history.
                if (filtered && TaskNames.isRunTests(e.get("task").toString())) step = step + "?filtered";
                String status = String.valueOf(e.get("status"));
                long ms = Math.max(0, num(e, "millis"));
                ran.computeIfAbsent(dir, k -> new ArrayList<>()).add(new String[] {step, status, Long.toString(ms)});
                HISTORY.computeIfAbsent(dir, k -> new HashMap<>())
                        .computeIfAbsent(step, k -> new HashMap<>())
                        .computeIfAbsent(status, k -> new ArrayList<>())
                        .add(new long[] {runId, ms});
            }
        }
        RAN.put(run, ran);
        PROFILE.put(run, profile);
        // The frontier: the modules whose main compile ran (the forecast names them before the seed).
        Set<String> frontier = new TreeSet<>();
        for (var e : ran.entrySet()) {
            for (String[] r : e.getValue()) {
                if ((r[0].startsWith(TaskNames.COMPILE_JAVA) || r[0].startsWith("compile-kotlin"))
                        && r[1].equals("SUCCESS")) frontier.add(e.getKey());
            }
        }
        String sig = profile + "|" + forced + "|" + filtered + "|" + String.join(",", frontier);
        SHAPE.put(runId, sig);
        // The work set, as the engine keys a suite's curve by it first: the modules with a step that ran.
        Set<String> work = new TreeSet<>();
        for (var e : ran.entrySet()) {
            for (String[] r : e.getValue()) if (r[1].equals("SUCCESS")) work.add(e.getKey());
        }
        WORK_SET.put(run, profile + "|" + forced + "|" + filtered + "|" + String.join(",", work));
        if (filtered) SELECTED.put(run, classesRan);
    }

    /**
     * The step under the build's shape, as the pricer's key must name it: a suite under its test
     * profile (the integration tier is not the default tier), a compile or native image as forced
     * or incremental (a from-scratch link is not an incremental one). REPLAY_SHAPES=0 turns it off.
     */
    static String shapeKey(String step, String profile, boolean forced) {
        if ("0".equals(System.getenv("REPLAY_SHAPES"))) return step;
        if (TaskNames.isRunTests(step) && !profile.isEmpty()) return step + "@" + profile;
        if ((step.startsWith("compile-") || step.equals(TaskNames.NATIVE_IMAGE)) && forced) return step + "!F";
        return step;
    }

    /** Median wall of {@code step} in {@code dir} under {@code status} in runs other than {@code runId}; -1 when none. */
    static long historyMs(String dir, String step, String status, long runId) {
        List<long[]> rows =
                HISTORY.getOrDefault(dir, Map.of()).getOrDefault(step, Map.of()).getOrDefault(status, List.of());
        if (!"0".equals(System.getenv("REPLAY_FRONTIER"))) {
            // First rung: runs of the same shape (same frontier, profile and flags).
            String sig = SHAPE.get(runId);
            long[] same = rows.stream()
                    .filter(r -> r[0] != runId && sig != null && sig.equals(SHAPE.get(r[0])))
                    .mapToLong(r -> r[1])
                    .sorted()
                    .toArray();
            if (same.length > 0) return same[same.length / 2];
        }
        long[] walls = rows.stream()
                .filter(r -> r[0] != runId)
                .mapToLong(r -> r[1])
                .sorted()
                .toArray();
        return walls.length == 0 ? -1 : walls[walls.length / 2];
    }

    static boolean gates(String step) {
        return step.equals(TaskNames.PARSE_BUILD)
                || step.equals(TaskNames.RESOLVE_DEPS)
                || step.equals(TaskNames.COPY_RESOURCES)
                || step.equals(TaskNames.COMPILE_JAVA)
                || step.equals("compile-kotlin")
                || step.equals("compile-groovy")
                || step.equals("assemble-classes")
                || step.equals("ksp");
    }

    /**
     * The ceiling of own-history pricing: each module priced in ms from the steps this run really
     * ran (oracle decisions), each step at its own median wall under the same status in the other
     * runs (leave-one-out), falling back to this module's median for the step under any status,
     * then nothing. Progress as V5b, no global scale.
     */
    static final class V7 extends V5 {
        final List<ModuleWorkCost> priced = new ArrayList<>();
        /** Per module: each step this run runs, bare name -> priced ms. */
        final Map<Path, Map<String, Long>> stepMs = new HashMap<>();

        final boolean clock;

        V7(boolean clock) {
            super(false);
            this.clock = clock;
        }

        @Override
        public String name() {
            return clock
                    ? "V7b oracle steps, own-history ms, V5b progress + clock"
                    : "V7 oracle steps, own-history ms, V5b progress";
        }

        @Override
        boolean clock() {
            return clock;
        }

        @Override
        List<ModuleWorkCost> costs(State s) {
            return priced.isEmpty() ? s.costs : priced;
        }

        void price(Path run, long runId, List<ModuleWorkCost> seed) {
            priced.clear();
            stepMs.clear();
            Map<String, List<String[]>> ran = RAN.getOrDefault(run, Map.of());
            for (ModuleWorkCost c : seed) {
                String dir = c.dir().toString();
                long pre = 0, test = 0, tail = 0, gate = 0;
                for (String[] r : ran.getOrDefault(dir, List.of())) {
                    long ms = historyMs(dir, r[0], r[1], runId);
                    Map<String, Set<String>> selected = SELECTED.get(run);
                    if (selected != null && TaskNames.isRunTests(r[0].replaceAll("[@!?].*$", ""))) {
                        // A filtered suite: its selected classes' walls, plus the suite's start-up.
                        long sum = 0;
                        for (String cls : selected.getOrDefault(dir, Set.of()))
                            sum += Math.max(0, classMs(dir, cls, runId));
                        ms = sum + SUITE_STARTUP_MS;
                    }
                    if (ms < 0) {
                        long any = -1;
                        for (String status : HISTORY.getOrDefault(dir, Map.of())
                                .getOrDefault(r[0], Map.of())
                                .keySet()) {
                            any = Math.max(any, historyMs(dir, r[0], status, runId));
                        }
                        ms = Math.max(0, any);
                    }
                    String bare = r[0].replaceAll("[@!?].*$", "");
                    stepMs.computeIfAbsent(c.dir(), k -> new LinkedHashMap<>()).merge(bare, ms, Long::sum);
                    if (TaskNames.isRunTests(bare)) test += ms;
                    else if (TaskNames.PACKAGING_TAILS.contains(bare)) tail = Math.max(tail, ms);
                    else pre += ms;
                    if (gates(bare)) gate += ms;
                }
                int u = MS_PER_WEIGHT;
                priced.add(new ModuleWorkCost(
                        c.dir(),
                        c.prereqs(),
                        (int) ((pre + test + tail) / u),
                        (int) (test / u),
                        (int) (tail / u),
                        (int) Math.max(1, gate / u),
                        0,
                        0));
            }
        }
    }

    /**
     * V7 with the suite counted down by class: its progress is the share of its expected class
     * walls already finished (each class's median wall in the other runs of the same suite and
     * profile, leave-one-out; a class with no history counts at its own wall). Once a quarter of it
     * is done, the remainder follows the suite's observed rate instead of its priced wall, so a
     * suite running slower than its history keeps a real remainder.
     */
    static final class V8 extends V5 {
        final V7 pricing = new V7(false);
        Path run;
        long runId;

        V8() {
            super(false);
        }

        @Override
        public String name() {
            return "V8 oracle steps, own-history ms, suites by class walls";
        }

        @Override
        List<ModuleWorkCost> costs(State s) {
            return pricing.costs(s);
        }

        void price(Path run, long runId, List<ModuleWorkCost> seed) {
            this.run = run;
            this.runId = runId;
            pricing.price(run, runId, seed);
        }

        /**
         * The module's remaining ms is the sum over its steps: a finished one nothing, a running
         * one its price less its elapsed time (held at a floor once it overruns; the suite by its
         * classes), one not yet started its full price. As a share of the module's priced weight.
         */
        @Override
        double left(State s, ModuleWorkCost c, long now, double scale) {
            Map<String, Long> prices = pricing.stepMs.getOrDefault(c.dir(), Map.of());
            double whole = c.weight() * scale;
            if (prices.isEmpty() || whole <= 0) return super.left(s, c, now, scale);
            Set<String> done = s.stepDone.getOrDefault(c.dir(), Set.of());
            Map<String, Long> started = s.stepStart.getOrDefault(c.dir(), Map.of());
            double pre = 0, test = 0, tail = 0;
            for (var e : prices.entrySet()) {
                String step = e.getKey();
                double price = e.getValue();
                double left;
                if (done.contains(step)) left = 0;
                else if (!started.containsKey(step)) left = price;
                else if (TaskNames.isRunTests(step) && price > 0) left = price * suiteLeft(s, c, now, scale, 1.0);
                else left = Math.max(CLOCK_FLOOR * price, price - (now - started.get(step)));
                if (TaskNames.isRunTests(step)) test += left;
                else if (TaskNames.PACKAGING_TAILS.contains(step)) tail = Math.max(tail, left);
                else pre += left;
            }
            return Math.min(1.0, (pre + test + tail) / whole);
        }

        /** {total, finished} class walls of the module's suite this run (see {@link #suiteLeft}). */
        double[] classTotals(State s, Path dirPath) {
            String step = shapeKey(TaskNames.RUN_TESTS, PROFILE.getOrDefault(run, ""), false);
            Map<String, List<long[]>> hist =
                    CLASS_HISTORY.getOrDefault(dirPath.toString(), Map.of()).getOrDefault(step, Map.of());
            Map<String, Set<String>> selected = SELECTED.get(run);
            if (selected != null) {
                Map<String, List<long[]>> only = new HashMap<>();
                for (String cls : selected.getOrDefault(dirPath.toString(), Set.of())) {
                    long ms = classMs(dirPath.toString(), cls, runId);
                    if (ms >= 0) only.put(cls, List.of(new long[] {-1, ms}));
                }
                hist = only;
            }
            Map<String, Long> done = s.classesDone.getOrDefault(dirPath, Map.of());
            double total = 0, finished = 0;
            for (var e : hist.entrySet()) {
                long[] walls = e.getValue().stream()
                        .filter(r -> r[0] != runId)
                        .mapToLong(r -> r[1])
                        .sorted()
                        .toArray();
                if (walls.length == 0) continue;
                double w = walls[walls.length / 2];
                total += w;
                if (done.containsKey(e.getKey())) finished += w;
            }
            for (var e : done.entrySet()) {
                boolean known = hist.containsKey(e.getKey())
                        && hist.get(e.getKey()).stream().anyMatch(r -> r[0] != runId);
                if (!known) {
                    total += e.getValue();
                    finished += e.getValue();
                }
            }
            return new double[] {total, finished};
        }

        /** Whether the module's suite has any class recorded in the other runs (leave-one-out). */
        boolean hasClassHistory(State s, Path dirPath) {
            String step = shapeKey(TaskNames.RUN_TESTS, PROFILE.getOrDefault(run, ""), false);
            Map<String, List<long[]>> hist =
                    CLASS_HISTORY.getOrDefault(dirPath.toString(), Map.of()).getOrDefault(step, Map.of());
            return hist.values().stream().flatMap(List::stream).anyMatch(r -> r[0] != runId);
        }

        /** The module's running suite's share of class walls done, as {@link #suiteLeft} reads it. */
        double classShare(State s, Path dir) {
            double[] tf = classTotals(s, dir);
            return tf[0] <= 0 ? 0 : Math.min(1.0, tf[1] / tf[0]);
        }

        @Override
        double suiteLeft(State s, ModuleWorkCost c, long now, double scale, double tickLeft) {
            Long start = s.suiteStart.get(c.dir());
            if (start == null || c.testWeight() <= 0) return tickLeft;
            double[] tf = classTotals(s, c.dir());
            double total = tf[0], finished = tf[1];
            if (total <= 0) return tickLeft;
            double f = Math.min(1.0, finished / total);
            double priced = c.testWeight() * scale;
            double elapsed = Math.max(0, now - start);
            double remainingMs = f >= 0.25 ? elapsed * (1 - f) / f : priced * (1 - f);
            return Math.max(f >= 1.0 ? 0 : CLOCK_FLOOR * (1 - f), Math.min(1.0, remainingMs / priced));
        }
    }

    static boolean pre(String task) {
        return !TaskNames.isRunTests(task) && !TaskNames.PACKAGING_TAILS.contains(task);
    }

    /**
     * V4 without the plan's tick budgets, which do not match what the steps report (parse-build
     * declares 391 ticks and resolve-deps reports them; the suite declares methods and reports
     * classes): the pre-suite part by the share of its started steps that finished, the suite by its
     * reported ticks over the plan's ticks still unspent when it first reported, a tail by its own.
     */
    static class V5 extends Residual {
        final boolean globalScale;

        V5(boolean globalScale) {
            this.globalScale = globalScale;
        }

        public String name() {
            return globalScale
                    ? "V5a step-finished + suite ticks, global scale"
                    : "V5b step-finished + suite ticks, no global scale";
        }

        double scaleMs(State s) {
            return globalScale ? s.weightToMs : MS_PER_WEIGHT;
        }

        /** Whether a running suite or tail also counts down by the clock against its priced wall. */
        boolean clock() {
            return false;
        }

        /** The running suite's remaining share; the ticks' answer unless a model reads more. */
        double suiteLeft(State s, ModuleWorkCost c, long now, double scale, double tickLeft) {
            return tickLeft;
        }

        double left(State s, ModuleWorkCost c, long now, double scale) {
            int weight = c.weight();
            if (weight <= 0) return 1.0 - s.frac.getOrDefault(c.dir(), 0.0);
            int test = c.testWeight();
            int tail = c.tailWeight();
            int pre = Math.max(0, weight - test - tail);
            int started = s.preStarted.getOrDefault(c.dir(), Set.of()).size();
            int finished = s.preFinished.getOrDefault(c.dir(), Set.of()).size();
            // Steps not yet started are unknown: the share is held below 1 until the suite or a tail starts.
            Map<String, long[]> steps = s.steps.getOrDefault(c.dir(), Map.of());
            long testDone = 0, tailTotal = 0, tailDone = 0;
            boolean suiteStarted = false;
            for (var e : steps.entrySet()) {
                if (TaskNames.isRunTests(e.getKey())) {
                    testDone += e.getValue()[1];
                    suiteStarted = true;
                } else if (TaskNames.PACKAGING_TAILS.contains(e.getKey())) {
                    tailTotal = Math.max(tailTotal, e.getValue()[0]);
                    tailDone = Math.max(tailDone, e.getValue()[1]);
                }
            }
            double preFrac = started == 0 ? 0 : (double) finished / started;
            if (!suiteStarted && test > 0) preFrac = Math.min(preFrac, 0.9);
            long suite = s.suiteTicks.getOrDefault(c.dir(), 0L);
            double testLeft = suite <= 0 ? 1 : 1.0 - Math.min(1.0, testDone / (double) suite);
            long tailT = s.tailTicks.getOrDefault(c.dir(), 0L);
            double tailLeft = tailT <= 0 ? 1 : 1.0 - Math.min(1.0, tailDone / (double) tailT);
            testLeft = suiteLeft(s, c, now, scale, testLeft);
            if (clock()) {
                // A running suite or tail also counts down by the clock against its priced wall,
                // held at a small floor once it overruns.
                Long ss = s.suiteStart.get(c.dir());
                if (test > 0 && ss != null) {
                    double clockLeft = 1.0 - (now - ss) / (test * scale);
                    // Within its priced wall the clock bounds it; past it, its own progress decides.
                    if (clockLeft > 0) testLeft = Math.min(testLeft, Math.max(CLOCK_FLOOR, clockLeft));
                }
                Long ts0 = s.tailStart.get(c.dir());
                if (tail > 0 && ts0 != null) {
                    double clockLeft = 1.0 - (now - ts0) / (tail * scale);
                    if (clockLeft > 0) tailLeft = Math.min(tailLeft, Math.max(CLOCK_FLOOR, clockLeft));
                }
            }
            return (pre * (1 - preFrac) + test * testLeft + tail * tailLeft) / weight;
        }
    }

    static ModuleWorkCost scaleCost(ModuleWorkCost c, double left) {
        left = Math.max(0, left);
        return new ModuleWorkCost(
                c.dir(),
                c.prereqs(),
                sc(c.weight(), left),
                sc(c.testWeight(), left),
                sc(c.tailWeight(), left),
                c.gateWeight() == ModuleWorkCost.UNKNOWN_GATE ? ModuleWorkCost.UNKNOWN_GATE : sc(c.gateWeight(), left),
                sc(c.suiteWall1(), left),
                c.suiteClasses());
    }

    static int sc(int w, double left) {
        if (w <= 0 || left <= 0) return 0;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(w * left)));
    }

    public static void main(String[] args) throws Exception {
        boolean series = false;
        Set<String> wanted = null;
        List<Path> runs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--series")) series = true;
            else if (a.equals("--models")) wanted = new LinkedHashSet<>(Arrays.asList(args[++i].split(",")));
            else runs.add(Path.of(a));
        }
        if (runs.isEmpty()) {
            System.err.println("usage: Replay <run dir>... [--series] [--models V0,V1,V2a,V2b,V3]");
            System.exit(2);
        }
        Map<String, List<double[]>> byModel = new TreeMap<>();
        long id = 0;
        for (Path run : runs) collectHistory(run, id++);
        id = 0;
        for (Path run : runs) {
            CURRENT_RUN_ID = id++;
            replay(run, series, wanted, byModel);
        }
        if (runs.size() > 1) {
            System.out.println(
                    "\nall runs: median |ETA err| and median bar err (pts) at 25/50/75%, share of runs inside ±20% / ±10 pts");
            for (var e : byModel.entrySet()) {
                List<double[]> rows = e.getValue();
                double[] etaAbs = rows.stream().mapToDouble(r -> r[0]).sorted().toArray();
                double[] barAbs = rows.stream().mapToDouble(r -> r[1]).sorted().toArray();
                long etaOk = rows.stream().filter(r -> r[2] > 0).count();
                long barOk = rows.stream().filter(r -> r[3] > 0).count();
                System.out.printf(
                        "  %-44s n=%-3d ETA %5.0f%%  bar %4.0f pts   in band: ETA %3.0f%%  bar %3.0f%%%n",
                        e.getKey(),
                        rows.size(),
                        100 * etaAbs[etaAbs.length / 2],
                        barAbs[barAbs.length / 2],
                        100.0 * etaOk / rows.size(),
                        100.0 * barOk / rows.size());
            }
        }
    }

    static long CURRENT_RUN_ID;

    /** Per suite and profile, the curve its latest replayed run measured ({@link SuiteCurve}). */
    static final Map<String, int[]> LAST_CURVE = new HashMap<>();

    /** The running replay's class shares per suite dir, each {@code {share, ts}}. */
    static final Map<String, List<double[]>> CURVE_POINTS = new HashMap<>();

    static String curveKey(Path run, String dir) {
        return PROFILE.getOrDefault(run, "") + '\n' + dir;
    }

    static final Map<Path, String> WORK_SET = new HashMap<>();

    /** The curve the engine would read: the same shape and work set's latest, else the profile's. */
    static int[] curveFor(Path run, String dir) {
        int[] own = LAST_CURVE.get(WORK_SET.getOrDefault(run, "") + '\n' + dir);
        return own != null ? own : LAST_CURVE.get(curveKey(run, dir));
    }

    static void replay(Path run, boolean series, Set<String> wanted, Map<String, List<double[]>> byModel)
            throws Exception {
        CURVE_POINTS.clear();
        Path modelFile = run.resolve("eta-model.jsonl");
        Path details = run.resolve("details.jsonl");
        if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(details)) {
            System.out.printf("%-6s (no eta-model.jsonl or details.jsonl)%n", run.getFileName());
            return;
        }
        List<Map<?, ?>> seeds = new ArrayList<>();
        for (String line : Files.readAllLines(modelFile)) {
            if (!line.isBlank()) seeds.add((Map<?, ?>) MiniJson.parse(line));
        }
        List<Map<?, ?>> events = new ArrayList<>();
        for (String line : Files.readAllLines(details)) {
            if (line.isBlank()) continue;
            Object o = MiniJson.parseRelaxed(line);
            if (o instanceof Map<?, ?> m) events.add(m);
        }
        if (events.isEmpty()) return;
        String argv = String.valueOf(events.get(0).get("argv"));
        long t0 = num(events.get(0), "ts");
        long end = t0;
        for (Map<?, ?> e : events) {
            if ("workspace-finish".equals(e.get("type"))) end = num(e, "ts");
            else end = Math.max(end, num(e, "ts"));
        }
        long wall = end - t0;

        V0 v0 = new V0();
        V6 v6 = new V6();
        V7 v7 = new V7(false);
        V7 v7b = new V7(true);
        V8 v8 = new V8();
        List<Model> models = new ArrayList<>(List.of(
                v0,
                new V1(),
                new V2(true),
                new V2(false),
                new V3(),
                new V4(true, false),
                new V4(false, false),
                new V4(true, true),
                new V4(false, true),
                new V5(true),
                new V5(false),
                v6,
                v7,
                v7b,
                v8));
        if (wanted != null) models.removeIf(m -> !wanted.contains(m.name().split(" ")[0]));

        State st = new State();
        Map<String, String> dirOfCoord = new HashMap<>();
        int nextSeed = 0;
        List<Double> fidelity = new ArrayList<>();
        Map<Model, List<long[]>> points = new LinkedHashMap<>();
        for (Model m : models) points.put(m, new ArrayList<>());
        for (Map<?, ?> e : events) {
            long ts = num(e, "ts");
            REPLAY_NOW[0] = ts;
            while (nextSeed < seeds.size() && num(seeds.get(nextSeed), "ts") <= ts) {
                Map<?, ?> s = seeds.get(nextSeed++);
                List<ModuleWorkCost> costs = costs(s);
                st.seed(
                        costs,
                        num(s, "R0"),
                        (int) num(s, "concurrency"),
                        Boolean.TRUE.equals(s.get("serial")),
                        Boolean.TRUE.equals(s.get("parallelTests")),
                        num(s, "ts"));
                st.frac.clear();
                Map<Path, List<StepCost>> seedSteps = steps(s);
                v0.rw = seedSteps.isEmpty()
                        ? RemainingWork.seed(costs, st.r0, st.concurrency, st.serial, st.parallelTests)
                        : RemainingWork.seedSteps(costs, seedSteps, st.concurrency, st.serial, st.parallelTests)
                                .withClock(REPLAY_CLOCK);
                // As the engine's JobSession: steps that started or finished before the model was
                // priced are replayed into it, each from when it really started.
                if (!seedSteps.isEmpty()) {
                    st.stepStart.forEach((dir, starts) -> starts.forEach((step, at) -> v0.rw.stepStart(dir, step, at)));
                    st.stepDone.forEach((dir, done) -> done.forEach(step -> v0.rw.stepFinish(dir, step)));
                }
                v6.rw = RemainingWork.seed(costs, st.r0, st.concurrency, st.serial, st.parallelTests);
                v7.price(run, CURRENT_RUN_ID, costs);
                v7b.price(run, CURRENT_RUN_ID, costs);
                v8.price(run, CURRENT_RUN_ID, costs);
            }
            String type = String.valueOf(e.get("type"));
            switch (type) {
                case "module-start" -> {
                    Object dir = e.get("dir");
                    Object coord = e.get("coord");
                    if (dir != null && coord != null) dirOfCoord.put(coord.toString(), dir.toString());
                    if (dir != null) st.started.putIfAbsent(Path.of(dir.toString()), ts);
                }
                case "task-start" -> {
                    String dir = dirOf(e, dirOfCoord);
                    Object task = e.get("task");
                    if (dir == null || task == null) break;
                    long ticks = Math.max(0, num(e, "ticks"));
                    st.steps.computeIfAbsent(Path.of(dir), k -> new LinkedHashMap<>())
                            .computeIfAbsent(task.toString(), k -> new long[2])[0] = ticks;
                    if (pre(task.toString()))
                        st.preStarted
                                .computeIfAbsent(Path.of(dir), k -> new LinkedHashSet<>())
                                .add(task.toString());
                    if (TaskNames.isRunTests(task.toString())) st.suiteStart.putIfAbsent(Path.of(dir), ts);
                    st.stepStart
                            .computeIfAbsent(Path.of(dir), k -> new HashMap<>())
                            .putIfAbsent(task.toString(), ts);
                    if (TaskNames.PACKAGING_TAILS.contains(task.toString())) st.tailStart.putIfAbsent(Path.of(dir), ts);
                    if (v0.rw != null) v0.rw.stepStart(Path.of(dir), task.toString());
                }
                case "class-finish" -> {
                    String dir = dirOf(e, dirOfCoord);
                    Object cls = e.get("testClass");
                    if (dir != null && cls != null) {
                        st.classesDone
                                .computeIfAbsent(Path.of(dir), k -> new HashMap<>())
                                .put(cls.toString(), Math.max(0, num(e, "millis")));
                        // The engine's suite share reads the session's class walls; the replay's
                        // closest stand-in is the other runs' (leave-one-out) through V8's reading.
                        // A suite with no recorded class has no measure (SuiteClassProgress.UNKNOWN):
                        // the engine leaves it on the clock rather than reading it as done.
                        if (v0.rw != null && v0.rw.steps(Path.of(dir)) && v8.hasClassHistory(st, Path.of(dir))) {
                            double share = v8.classShare(st, Path.of(dir));
                            CURVE_POINTS
                                    .computeIfAbsent(dir, k -> new ArrayList<>())
                                    .add(new double[] {share, ts});
                            // The engine reads the share as time through the suite's last curve.
                            int[] curve = curveFor(run, dir);
                            v0.rw.suiteProgress(Path.of(dir), SuiteCurve.timeShare(curve, share));
                        }
                    }
                }
                case "task-finish" -> {
                    String dir = dirOf(e, dirOfCoord);
                    Object task = e.get("task");
                    if (dir == null || task == null) break;
                    st.stepDone
                            .computeIfAbsent(Path.of(dir), k -> new HashSet<>())
                            .add(task.toString());
                    if (TaskNames.isRunTests(task.toString()) && st.suiteStart.containsKey(Path.of(dir))) {
                        long start = st.suiteStart.get(Path.of(dir));
                        double suiteWall = Math.max(1, ts - start);
                        List<double[]> shares = new ArrayList<>();
                        for (double[] p : CURVE_POINTS.getOrDefault(dir, List.of())) {
                            shares.add(new double[] {p[0], (p[1] - start) / suiteWall});
                        }
                        int[] curve = SuiteCurve.of(shares);
                        // A filtered suite's curve is its selection's, not the suite's.
                        if (curve != null && SELECTED.get(run) == null) {
                            LAST_CURVE.put(curveKey(run, dir), curve);
                            LAST_CURVE.put(WORK_SET.getOrDefault(run, "") + '\n' + dir, curve);
                        }
                    }
                    if (v0.rw != null) v0.rw.stepFinish(Path.of(dir), task.toString());
                    if (pre(task.toString()))
                        st.preFinished
                                .computeIfAbsent(Path.of(dir), k -> new LinkedHashSet<>())
                                .add(task.toString());
                }
                case "progress", "buildplan-start" -> {
                    String dir = dirOf(e, dirOfCoord);
                    long den = num(e, "denominator");
                    if (dir == null || den <= 0) break;
                    double f = Math.min(1.0, Math.max(0.0, (double) num(e, "numerator") / den));
                    Path p = Path.of(dir);
                    if (v0.rw != null) v0.rw.moduleProgress(p, f);
                    st.frac.put(p, f);
                    st.den.put(p, den);
                    Object task = e.get("task");
                    long delta = num(e, "delta");
                    if (task != null) {
                        boolean suite = TaskNames.isRunTests(task.toString());
                        boolean tail = TaskNames.PACKAGING_TAILS.contains(task.toString());
                        Map<Path, Long> mine = suite ? st.suiteTicks : tail ? st.tailTicks : null;
                        Map<Path, Long> other = suite ? st.tailTicks : st.suiteTicks;
                        if (mine != null && !mine.containsKey(p)) {
                            long unspent =
                                    Math.max(1, den - st.reported.getOrDefault(p, 0L) - other.getOrDefault(p, 0L));
                            ModuleWorkCost c = st.costOf.get(p);
                            // A suite and a tail run beside each other: until the other reports, it
                            // keeps its weight's share of the unspent ticks.
                            if (c != null && !other.containsKey(p)) {
                                int self = suite ? c.testWeight() : c.tailWeight();
                                int rest = suite ? c.tailWeight() : c.testWeight();
                                if (self + rest > 0 && rest > 0) unspent = Math.max(1, unspent * self / (self + rest));
                            }
                            mine.put(p, unspent);
                        }
                    }
                    if (delta > 0) st.reported.merge(p, delta, Long::sum);
                    if (task != null && TaskNames.RESOLVE_DEPS.equals(task.toString()) && delta > 0) {
                        st.resolveTicks.merge(p, delta, Long::sum);
                    }
                    if (v6.rw != null) {
                        long rt = st.resolveTicks.getOrDefault(p, 0L);
                        long d6 = den - rt;
                        double f6 =
                                d6 <= 0 ? f : Math.min(1.0, Math.max(0.0, (num(e, "numerator") - rt) / (double) d6));
                        v6.rw.moduleProgress(p, f6);
                    }
                    if (task != null && delta > 0) {
                        st.steps.computeIfAbsent(p, k -> new LinkedHashMap<>())
                                .computeIfAbsent(task.toString(), k -> new long[2])[1] += delta;
                    }
                    st.started.putIfAbsent(p, ts);
                }
                case "module-finish" -> {
                    String dir = dirOf(e, dirOfCoord);
                    if (dir == null) break;
                    Path p = Path.of(dir);
                    if (v0.rw != null) v0.rw.moduleComplete(p);
                    if (v6.rw != null) v6.rw.moduleComplete(p);
                    st.done.add(p);
                }
                case "workspace-progress" -> {
                    if (st.costs.isEmpty() && v0.rw == null) break;
                    for (Model m : models) {
                        long r = m.remaining(st, ts);
                        if (r >= 0) points.get(m).add(new long[] {ts, r});
                    }
                    long recorded = num(e, "remainingMs");
                    if (v0.rw != null && recorded >= 0 && !"preflight".equals(e.get("phase"))) {
                        long replayed = v0.rw.remaining();
                        fidelity.add(Math.abs(replayed - recorded) / (double) Math.max(1000, recorded));
                        if (series) {
                            System.out.printf(
                                    "  t=%5.0fs recorded=%7.1fs replayed=%7.1fs true=%7.1fs%n",
                                    (ts - t0) / 1000.0, recorded / 1000.0, replayed / 1000.0, (end - ts) / 1000.0);
                        }
                    }
                }
                default -> {}
            }
        }

        fidelity.sort(Double::compare);
        String fid = fidelity.isEmpty()
                ? "-"
                : String.format(
                        "%.1f%% median, %d/%d within 5%%",
                        100 * fidelity.get(fidelity.size() / 2),
                        fidelity.stream().filter(f -> f <= 0.05).count(),
                        fidelity.size());
        System.out.printf("%n%s  %s  wall %.0fs  fidelity: %s%n", run.getFileName(), argv, wall / 1000.0, fid);
        System.out.println(
                "  model                                         ETA err @10   @25   @50   @75   @90    bar @25  @50  @75");
        for (Model m : models) {
            List<long[]> pts = points.get(m);
            StringBuilder eta = new StringBuilder();
            StringBuilder bar = new StringBuilder();
            double peak = 0;
            int pi = 0;
            double etaSum = 0;
            double barSum = 0;
            int nScored = 0;
            boolean etaOk = true;
            boolean barOk = true;
            for (double f : CHECKPOINTS) {
                long at = t0 + Math.round(f * wall);
                long[] shown = null;
                while (pi < pts.size() && pts.get(pi)[0] <= at) {
                    long[] p = pts.get(pi++);
                    double el = p[0] - t0;
                    double b = Math.min(99.0, 100.0 * el / (el + Math.max(0, p[1])));
                    peak = Math.max(peak, b);
                    shown = p;
                }
                if (shown == null && pi > 0) shown = pts.get(pi - 1);
                long trueMs = end - at;
                if (shown == null || trueMs <= 0) {
                    eta.append("     -");
                    bar.append("    -");
                    continue;
                }
                long etaMs = Math.max(0, shown[1] - (at - shown[0]));
                double err = (etaMs - trueMs) / (double) trueMs;
                double barErr = peak - 100 * f;
                eta.append(String.format(" %+5.0f%%", 100 * err));
                bar.append(String.format(" %+4.0f", barErr));
                for (double s : SCORED) {
                    if (s == f) {
                        etaSum += Math.abs(err);
                        barSum += Math.abs(barErr);
                        nScored++;
                        etaOk &= Math.abs(err) <= 0.20;
                        barOk &= Math.abs(barErr) <= 10;
                    }
                }
            }
            System.out.printf("  %-44s %s  %s%n", m.name(), eta, bar);
            if (nScored > 0) {
                byModel.computeIfAbsent(m.name(), k -> new ArrayList<>())
                        .add(new double[] {etaSum / nScored, barSum / nScored, etaOk ? 1 : 0, barOk ? 1 : 0});
            }
        }
    }

    /** The replay's clock: the timestamp of the event being replayed. */
    static final long[] REPLAY_NOW = {0};

    static final Clock REPLAY_CLOCK = new Clock() {
        @Override
        public long millis() {
            return REPLAY_NOW[0];
        }

        @Override
        public long nanos() {
            return REPLAY_NOW[0] * 1_000_000;
        }
    };

    /** A step-priced seed's steps, by module; empty for a weight-priced seed. */
    static Map<Path, List<StepCost>> steps(Map<?, ?> s) {
        Map<Path, List<StepCost>> out = new LinkedHashMap<>();
        if (!(s.get("steps") instanceof Map<?, ?> m)) return out;
        for (var e : m.entrySet()) {
            List<StepCost> rows = new ArrayList<>();
            if (e.getValue() instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof List<?> r && r.size() >= 3 && r.get(1) instanceof Number n) {
                        int low = r.size() >= 5 && r.get(3) instanceof Number lo ? lo.intValue() : n.intValue();
                        int high = r.size() >= 5 && r.get(4) instanceof Number hi ? hi.intValue() : n.intValue();
                        rows.add(new StepCost(
                                String.valueOf(r.get(0)),
                                n.intValue(),
                                // Classified as the engine classifies today, so a journal priced
                                // before a kind existed replays under the current shape.
                                StepEta.kindOf(String.valueOf(r.get(0))),
                                low,
                                high));
                    }
                }
            }
            out.put(Path.of(String.valueOf(e.getKey())), rows);
        }
        return out;
    }

    static List<ModuleWorkCost> costs(Map<?, ?> s) {
        List<ModuleWorkCost> costs = new ArrayList<>();
        for (Object o : MiniJson.list(s, "costs")) {
            Map<?, ?> c = (Map<?, ?>) o;
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Object p : MiniJson.list(c, "prereqs")) prereqs.add(Path.of(p.toString()));
            costs.add(new ModuleWorkCost(
                    Path.of(String.valueOf(c.get("dir"))),
                    prereqs,
                    (int) num(c, "weight"),
                    (int) num(c, "testWeight"),
                    (int) num(c, "tailWeight"),
                    (int) num(c, "gateWeight"),
                    (int) num(c, "suiteWall1"),
                    (int) num(c, "suiteClasses")));
        }
        return costs;
    }

    static String dirOf(Map<?, ?> e, Map<String, String> dirOfCoord) {
        Object dir = e.get("dir");
        if (dir != null) return dir.toString();
        Object module = e.get("module");
        return module == null ? null : dirOfCoord.getOrDefault(module.toString(), null);
    }

    static long num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : -1;
    }
}
