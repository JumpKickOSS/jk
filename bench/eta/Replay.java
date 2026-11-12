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

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Replay {

    static final double[] CHECKPOINTS = {0.10, 0.25, 0.50, 0.75, 0.90};
    static final double[] SCORED = {0.25, 0.50, 0.75};
    static final int MS_PER_WEIGHT = 150;
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

        public long remaining(State s, long now) {
            if (s.costs.isEmpty()) return -1;
            double scale = scaleMs(s);
            List<ModuleWorkCost> residual = new ArrayList<>();
            for (ModuleWorkCost c : s.costs) {
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
            return (timeAware ? "V4" + (globalScale ? "c" : "d") : "V4" + (globalScale ? "a" : "b"))
                    + " step-weighted" + (timeAware ? ", time floor" : "") + (globalScale ? ", global scale" : ", no global scale");
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
            return Math.max(share, Math.min(overrun, 1.0 - Math.min(1.0, (preDone + testDone + tailDone) / (double) Math.max(1, den))));
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
    static final class V5 extends Residual {
        final boolean globalScale;

        V5(boolean globalScale) {
            this.globalScale = globalScale;
        }

        public String name() {
            return globalScale ? "V5a step-finished + suite ticks, global scale" : "V5b step-finished + suite ticks, no global scale";
        }

        double scaleMs(State s) {
            return globalScale ? s.weightToMs : MS_PER_WEIGHT;
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
        for (Path run : runs) replay(run, series, wanted, byModel);
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

    static void replay(Path run, boolean series, Set<String> wanted, Map<String, List<double[]>> byModel)
            throws Exception {
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
                new V5(false)));
        if (wanted != null) models.removeIf(m -> !wanted.contains(m.name().split(" ")[0]));

        State st = new State();
        Map<String, String> dirOfCoord = new HashMap<>();
        int nextSeed = 0;
        List<Double> fidelity = new ArrayList<>();
        Map<Model, List<long[]>> points = new LinkedHashMap<>();
        for (Model m : models) points.put(m, new ArrayList<>());
        for (Map<?, ?> e : events) {
            long ts = num(e, "ts");
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
                v0.rw = RemainingWork.seed(costs, st.r0, st.concurrency, st.serial, st.parallelTests);
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
                    if (pre(task.toString())) st.preStarted.computeIfAbsent(Path.of(dir), k -> new LinkedHashSet<>()).add(task.toString());
                }
                case "task-finish" -> {
                    String dir = dirOf(e, dirOfCoord);
                    Object task = e.get("task");
                    if (dir == null || task == null) break;
                    if (pre(task.toString())) st.preFinished.computeIfAbsent(Path.of(dir), k -> new LinkedHashSet<>()).add(task.toString());
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
                            long unspent = Math.max(1, den - st.reported.getOrDefault(p, 0L) - other.getOrDefault(p, 0L));
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
