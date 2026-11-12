// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.StepCost;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Each step's recent walls, per project, under the shape of the run that measured them: the
 * history the ETA prices a step from. One line per finished successful run in {@value #FILE} under
 * the project's home: the run's shape ({@link RunShapes.Shape}), its frontier (the work set its
 * estimate was priced under: the modules it runs a step in; the modules whose main compile ran when
 * it had no estimate), every step's status and wall, each finished suite's {@link SuiteCurve} and
 * the test JVMs it ran on.
 *
 * <p>A step is priced at its median wall under the same status (a restore is not a run) and the
 * same shape where the shape changes the work: a suite under its own profile and never from a
 * filtered run, a compile or native image as a full rebuild or not. The first rung is the runs with
 * the same frontier: the same work beside the step, which in an inner loop is the run before; then
 * any frontier.
 * A run's {@code forced} flag records a full rebuild, forced or not ({@link #fullRebuild}): a cold
 * first build carries a forced build's work and load. A step seen only under the other load is read
 * at the load ratio the ledger has learned (JK-D3785).
 */
public final class StepLedger {

    /** The ledger's file name under a project's home. */
    public static final String FILE = "step-ledger.jsonl";

    /** Runs kept: enough for every recent edit shape, small enough to read per estimate. */
    static final int MAX_RUNS = 60;

    /** Samples a rung's median reads, newest first. */
    static final int SAME_FRONTIER = 5;

    static final int ANY_FRONTIER = 7;

    /** Steps shorter than this are not recorded: they price as nothing either way. */
    static final long MIN_MS = 20;

    private static final ReentrantLock WRITE = new ReentrantLock();

    private static final ConcurrentHashMap<Path, StepLedger> MEMO = new ConcurrentHashMap<>();

    /** One step's wall in a finished run. */
    public record Sample(String dir, String step, String status, long ms) {
        public Sample {
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(step, "step");
            status = status == null ? "" : status;
        }
    }

    /** One recorded run, newest last. */
    record Run(
            RunShapes.Shape shape,
            Set<String> frontier,
            List<Sample> samples,
            Map<String, int[]> curves,
            Map<String, long[]> workers) {
        Run(RunShapes.Shape shape, Set<String> frontier, List<Sample> samples) {
            this(shape, frontier, samples, Map.of(), Map.of());
        }

        /** The test JVMs the suite of {@code dir} ran on in this run; {@code 0} when not recorded. */
        int workersOf(String dir) {
            long[] w = workers.get(dir);
            return w == null ? 0 : (int) w[0];
        }

        /** {@code {classWallsMs, longestClassMs}} of the suite of {@code dir} in this run; zeros when not recorded. */
        long[] classWallsOf(String dir) {
            long[] w = workers.get(dir);
            return w == null ? new long[2] : new long[] {w[1], w[2]};
        }
    }

    private final List<Run> runs;
    private final @Nullable FileTime stamp;
    /** dir + '\n' + step + '\n' + status → the runs' samples, oldest first. */
    private final Map<String, List<Indexed>> index = new HashMap<>();

    private record Indexed(Run run, long ms) {}

    private StepLedger(List<Run> runs, @Nullable FileTime stamp) {
        this.runs = List.copyOf(runs);
        this.stamp = stamp;
        for (Run r : this.runs) {
            for (Sample s : r.samples()) {
                index.computeIfAbsent(key(s.dir(), s.step(), s.status()), k -> new ArrayList<>())
                        .add(new Indexed(r, s.ms()));
            }
        }
    }

    /** An empty ledger. */
    public static StepLedger empty() {
        return new StepLedger(List.of(), null);
    }

    /** Fewest modules with a main compile for a run to count as a full rebuild by its frontier. */
    static final int FULL_REBUILD_MIN_MODULES = 4;

    /**
     * Whether a run that compiles {@code compiling} of its {@code compilable} modules is a full
     * rebuild: at least half of them. A first build on an empty cache recompiles everything as a
     * forced one does and runs every step beside every compile, so it is priced and recorded as one:
     * its server/guard guard took 69 s, the same guard 18 s beside one leaf edit.
     */
    public static boolean fullRebuild(int compiling, int compilable) {
        return compilable >= FULL_REBUILD_MIN_MODULES && compiling * 2 >= compilable;
    }

    /** The modules with a main compile in a finished run, run or restored. */
    static Set<String> compilableOf(List<Sample> samples) {
        Set<String> out = new TreeSet<>();
        for (Sample s : samples) {
            if (isMainCompile(s.step())) out.add(s.dir());
        }
        return out;
    }

    /** Whether {@code step} is a module's main compile, the step a frontier and a full rebuild count. */
    public static boolean isMainCompile(String step) {
        return TaskNames.COMPILE_JAVA.equals(step)
                || TaskNames.COMPILE_KOTLIN.equals(step)
                || TaskNames.COMPILE_GROOVY.equals(step);
    }

    /** The modules whose main compile ran in a finished run: its frontier. */
    public static Set<String> frontierOf(List<Sample> samples) {
        Set<String> out = new TreeSet<>();
        for (Sample s : samples) {
            if ("SUCCESS".equals(s.status()) && isMainCompile(s.step())) out.add(s.dir());
        }
        return out;
    }

    /**
     * Record a finished run's steps into the ledger under {@code projectHome}: one line, the
     * oldest dropped past {@link #MAX_RUNS}. Best effort; a failure is logged and forgotten.
     */
    public static void append(Path projectHome, RunShapes.Shape shape, List<Sample> samples) {
        append(projectHome, shape, null, samples);
    }

    /**
     * Record a finished run under the work set its estimate was priced with, {@code frontier}; a
     * run with none (its estimate was never priced) is recorded under the modules whose main
     * compile ran.
     */
    public static void append(
            Path projectHome, RunShapes.Shape shape, @Nullable Set<String> frontier, List<Sample> samples) {
        append(projectHome, shape, frontier, Map.of(), samples);
    }

    /**
     * {@link #append(Path, RunShapes.Shape, Set, List)}, with the curves its suites measured, by
     * module dir ({@link SuiteCurve}).
     */
    public static void append(
            Path projectHome,
            RunShapes.Shape shape,
            @Nullable Set<String> frontier,
            Map<String, int[]> curves,
            List<Sample> samples) {
        append(projectHome, shape, frontier, curves, Map.of(), samples);
    }

    /**
     * {@link #append(Path, RunShapes.Shape, Set, Map, List)}, with the test JVMs each suite ran on,
     * by module dir.
     */
    public static void append(
            Path projectHome,
            RunShapes.Shape shape,
            @Nullable Set<String> frontier,
            Map<String, int[]> curves,
            Map<String, long[]> workers,
            List<Sample> samples) {
        List<Sample> kept = new ArrayList<>();
        for (Sample s : samples) {
            if (s.ms() >= MIN_MS) kept.add(s);
        }
        if (kept.isEmpty()) return;
        Set<String> compiled = frontierOf(samples);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("profile", shape.profile());
        line.put(
                "forced",
                shape.forced()
                        || fullRebuild(compiled.size(), compilableOf(samples).size()));
        line.put("filtered", shape.filtered());
        line.put("frontier", List.copyOf(new TreeSet<>(frontier == null ? compiled : frontier)));
        List<Object> steps = new ArrayList<>();
        for (Sample s : kept) steps.add(List.of(s.dir(), s.step(), s.status(), s.ms()));
        line.put("steps", steps);
        if (curves != null && !curves.isEmpty()) {
            Map<String, Object> byDir = new TreeMap<>();
            for (Map.Entry<String, int[]> e : curves.entrySet()) {
                if (e.getValue() == null || e.getValue().length != SuiteCurve.POINTS) continue;
                List<Object> points = new ArrayList<>();
                for (int v : e.getValue()) points.add(v);
                byDir.put(e.getKey(), points);
            }
            if (!byDir.isEmpty()) line.put("curves", byDir);
        }
        if (workers != null && !workers.isEmpty()) {
            Map<String, Object> byDir = new TreeMap<>();
            for (Map.Entry<String, long[]> e : workers.entrySet()) {
                long[] w = e.getValue();
                if (w != null && w.length == 3 && w[0] > 0) byDir.put(e.getKey(), List.of(w[0], w[1], w[2]));
            }
            if (!byDir.isEmpty()) line.put("workers", byDir);
        }
        Path file = projectHome.resolve(FILE);
        WRITE.lock();
        try {
            List<String> lines = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!l.isBlank()) lines.add(l);
                }
            }
            lines.add(MiniJson.write(line));
            if (lines.size() > MAX_RUNS) lines = new ArrayList<>(lines.subList(lines.size() - MAX_RUNS, lines.size()));
            Files.createDirectories(projectHome);
            AtomicWrites.replace(file, String.join("\n", lines) + "\n");
            MEMO.remove(projectHome);
        } catch (IOException | RuntimeException e) {
            Log.debug("StepLedger.append: not recorded", e);
        } finally {
            WRITE.unlock();
        }
    }

    /** The ledger under {@code projectHome}, read once per change of its file. */
    public static StepLedger load(Path projectHome) {
        Path file = projectHome.resolve(FILE);
        FileTime now;
        try {
            if (!Files.isRegularFile(file)) return empty();
            now = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return empty();
        }
        StepLedger memo = MEMO.get(projectHome);
        if (memo != null && now.equals(memo.stamp)) return memo;
        StepLedger read = read(file, now);
        MEMO.put(projectHome, read);
        return read;
    }

    private static StepLedger read(Path file, FileTime stamp) {
        List<Run> runs = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank()) continue;
                if (!(MiniJson.parseRelaxed(l) instanceof Map<?, ?> m)) continue;
                RunShapes.Shape shape = new RunShapes.Shape(
                        String.valueOf(m.get("profile") == null ? "" : m.get("profile")),
                        Boolean.TRUE.equals(m.get("forced")),
                        Boolean.TRUE.equals(m.get("filtered")));
                Set<String> frontier = new TreeSet<>();
                for (Object o : MiniJson.list(m, "frontier")) frontier.add(String.valueOf(o));
                List<Sample> samples = new ArrayList<>();
                for (Object o : MiniJson.list(m, "steps")) {
                    if (!(o instanceof List<?> row) || row.size() < 4) continue;
                    if (!(row.get(3) instanceof Number n)) continue;
                    samples.add(new Sample(
                            String.valueOf(row.get(0)),
                            String.valueOf(row.get(1)),
                            String.valueOf(row.get(2)),
                            n.longValue()));
                }
                Map<String, int[]> curves = new HashMap<>();
                if (m.get("curves") instanceof Map<?, ?> byDir) {
                    for (Map.Entry<?, ?> e : byDir.entrySet()) {
                        if (!(e.getValue() instanceof List<?> points) || points.size() != SuiteCurve.POINTS) continue;
                        int[] curve = new int[SuiteCurve.POINTS];
                        boolean ok = true;
                        for (int i = 0; i < curve.length; i++) {
                            if (points.get(i) instanceof Number n) curve[i] = n.intValue();
                            else ok = false;
                        }
                        if (ok) curves.put(String.valueOf(e.getKey()), curve);
                    }
                }
                Map<String, long[]> workers = new HashMap<>();
                if (m.get("workers") instanceof Map<?, ?> byDir) {
                    for (Map.Entry<?, ?> e : byDir.entrySet()) {
                        if (e.getValue() instanceof List<?> w
                                && w.size() == 3
                                && w.get(0) instanceof Number n
                                && w.get(1) instanceof Number sum
                                && w.get(2) instanceof Number longest
                                && n.intValue() > 0) {
                            workers.put(
                                    String.valueOf(e.getKey()),
                                    new long[] {n.longValue(), sum.longValue(), longest.longValue()});
                        }
                    }
                }
                runs.add(new Run(shape, frontier, samples, curves, workers));
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("StepLedger.read: unreadable, priced from nothing", e);
            return empty();
        }
        return new StepLedger(runs, stamp);
    }

    /** Runs recorded. */
    public int runs() {
        return runs.size();
    }

    /**
     * {@code step}'s wall in {@code dir} under {@code status}, for a run of {@code shape} whose
     * frontier is {@code frontier}: the median of the latest samples of the first rung that has any
     * ({@link #rung}): the same shape (profile, full rebuild or not, filtered) and frontier, the same
     * shape, runs that differ only where the step does not care under the same load, then those
     * under the other load at the {@link #lightOverHeavy load ratio}, then, for a suite under a new
     * profile, its default profile's walls. Empty when the ledger has none. A filtered suite has no
     * price here: its selected classes' walls are its price.
     *
     * <p>The whole shape comes first because it carries the run's load as well as its work: a
     * guard in a forced build ran beside every compile at once and took four times its wall in a
     * filtered test run, and a forced integration run's guard is neither.
     */
    public OptionalLong price(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        Rung rung = rung(dir, step, status, shape, frontier);
        return rung.samples().isEmpty() ? OptionalLong.empty() : OptionalLong.of(rung.at(median(rung.samples())));
    }

    /**
     * The likely range of {@code step}'s wall, {@code {low, high}}: the 20th and 80th percentiles
     * of the samples {@link #price} takes its median from, widened to at least the rung's least
     * spread either side of the median ({@link #MIN_SPREAD}, more when the samples were borrowed
     * from another load or profile). Empty when {@link #price} is.
     */
    public Optional<long[]> range(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        Rung rung = rung(dir, step, status, shape, frontier);
        if (rung.samples().isEmpty()) return Optional.empty();
        List<Long> sorted = new ArrayList<>(rung.samples());
        sorted.sort(Long::compare);
        long median = rung.at(median(rung.samples()));
        long low = Math.min(rung.at(percentile(sorted, 0.2)), Math.round(median * (1 - rung.spreadBelow())));
        long high = Math.max(rung.at(percentile(sorted, 0.8)), Math.round(median * (1 + rung.spreadAbove())));
        return Optional.of(new long[] {Math.max(0, low), high});
    }

    static final double MIN_SPREAD = 0.15;

    /** The least spread of a price read across loads: the load ratio is one number for every step. */
    static final double LOAD_SPREAD = 0.4;

    /**
     * A suite's wall under a profile it has never run in, borrowed from its default profile: the
     * profile adds its own tests to the default ones, so the borrowed wall is a floor and the range
     * reaches well above it.
     */
    static final double PROFILE_SPREAD_ABOVE = 2.0;

    /**
     * The test JVMs {@link #price} assumes for the suite of {@code dir}: the count its newest sample
     * ran on, the count the build is likeliest to pick again; {@code 0} when not recorded or not a
     * suite. The other samples are read at this count ({@link StepCost#scale}).
     */
    public int workers(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        return rung(dir, step, status, shape, frontier).workers();
    }

    /**
     * The samples {@link #price} reads, newest first, the factor it reads them at, the least spread
     * around them, and for a suite the test JVMs they were read at.
     */
    private record Rung(List<Long> samples, double scale, double spreadBelow, double spreadAbove, int workers) {
        static final Rung NONE = new Rung(List.of(), 1, MIN_SPREAD, MIN_SPREAD, 0);

        long at(long ms) {
            return Math.round(ms * scale);
        }
    }

    /** The {@code q} quantile of {@code sorted}, interpolated between the samples either side. */
    private static long percentile(List<Long> sorted, double q) {
        double at = q * (sorted.size() - 1);
        int below = (int) Math.floor(at);
        int above = Math.min(sorted.size() - 1, below + 1);
        return Math.round(sorted.get(below) + (at - below) * (sorted.get(above) - sorted.get(below)));
    }

    /**
     * The samples {@link #price} answers from, the first rung that has any: the same shape and
     * frontier; the same shape; runs that differ only where the step does not care, under the same
     * load (a full rebuild or not) and the same frontier first; those under the other load, read at
     * the {@link #lightOverHeavy load ratio}; for a suite under a profile it has never run in, its
     * default profile's walls, as a floor.
     */
    private Rung rung(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        boolean suite = TaskNames.isRunTests(step);
        if (suite && shape.filtered()) return Rung.NONE;
        List<Indexed> rows = index.getOrDefault(key(dir, step, status), List.of());
        if (rows.isEmpty()) return Rung.NONE;
        boolean forcedMatters = loadChangesWork(step);
        List<Indexed> sameShapeFrontier = new ArrayList<>();
        List<Indexed> sameShape = new ArrayList<>();
        List<Indexed> sameLoadFrontier = new ArrayList<>();
        List<Indexed> sameLoad = new ArrayList<>();
        List<Indexed> otherLoad = new ArrayList<>();
        List<Indexed> defaultProfile = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Indexed r = rows.get(i);
            RunShapes.Shape s = r.run().shape();
            boolean frontierMatches = r.run().frontier().equals(frontier);
            if (sameShape(s, shape)) {
                if (sameShapeFrontier.size() < SAME_FRONTIER && frontierMatches) sameShapeFrontier.add(r);
                if (sameShape.size() < ANY_FRONTIER) sameShape.add(r);
            }
            if (suite && s.filtered()) continue;
            if (suite && !s.profile().equals(shape.profile())) {
                if (s.profile().isEmpty() && defaultProfile.size() < ANY_FRONTIER) {
                    // Read at this run's load: a first build's suites ran beside every compile.
                    double atLoad =
                            s.forced() == shape.forced() ? 1 : shape.forced() ? 1 / lightOverHeavy() : lightOverHeavy();
                    defaultProfile.add(new Indexed(r.run(), Math.round(r.ms() * atLoad)));
                }
                continue;
            }
            if (s.forced() != shape.forced()) {
                if (!forcedMatters && otherLoad.size() < ANY_FRONTIER) otherLoad.add(r);
                continue;
            }
            if (sameLoadFrontier.size() < SAME_FRONTIER && frontierMatches) sameLoadFrontier.add(r);
            if (sameLoad.size() < ANY_FRONTIER) sameLoad.add(r);
        }
        for (List<Indexed> rung : List.of(sameShapeFrontier, sameShape, sameLoadFrontier, sameLoad)) {
            if (!rung.isEmpty()) return read(rung, dir, suite, 1, MIN_SPREAD, MIN_SPREAD);
        }
        if (!otherLoad.isEmpty()) {
            double ratio = lightOverHeavy();
            return read(otherLoad, dir, suite, shape.forced() ? 1 / ratio : ratio, LOAD_SPREAD, LOAD_SPREAD);
        }
        if (!defaultProfile.isEmpty()) return read(defaultProfile, dir, suite, 1, MIN_SPREAD, PROFILE_SPREAD_ABOVE);
        return Rung.NONE;
    }

    /**
     * A rung of {@code rows}, newest first; a suite's walls read at the test JVMs of its newest
     * sample that recorded any. A suite's wall is the answer for the JVMs it ran on: the engine's
     * suite took 73 s on 2 in a first build and 37 s on 8 in each forced build after it, and the
     * median of the two priced the next forced build at the first.
     */
    private static Rung read(List<Indexed> rows, String dir, boolean suite, double scale, double below, double above) {
        int workers = 0;
        long[] walls = new long[2];
        if (suite) {
            for (Indexed r : rows) {
                if ((workers = r.run().workersOf(dir)) > 0) {
                    walls = r.run().classWallsOf(dir);
                    break;
                }
            }
        }
        List<Long> samples = new ArrayList<>(rows.size());
        for (Indexed r : rows) {
            double at = workers > 0 ? StepCost.scale(r.run().workersOf(dir), workers, walls[0], walls[1]) : 1;
            samples.add(Math.round(r.ms() * at));
        }
        return new Rung(samples, scale, below, above, workers);
    }

    /** Whether a full rebuild changes the step's work, not only the load it runs beside. */
    private static boolean loadChangesWork(String step) {
        return step.startsWith("compile-") || TaskNames.NATIVE_IMAGE.equals(step);
    }

    /** The ratio read when no step of the ledger has run under both loads: from jk's 2026-11-12 corpus. */
    static final double DEFAULT_LIGHT_OVER_HEAVY = 0.5;

    /** Steps that ran under both loads needed before the ledger's own ratio is read. */
    static final int MIN_LOAD_PAIRS = 3;

    /** Steps shorter than this under a full rebuild are too noisy to teach the load ratio. */
    static final long MIN_LOAD_PAIR_MS = 1_000;

    private volatile double lightOverHeavy = Double.NaN;

    /**
     * What a step's wall in a light run is of its wall in a full rebuild: the median, over the
     * steps that ran under both (same profile and filter, work the load does not change), of their
     * light median over their heavy one; {@link #DEFAULT_LIGHT_OVER_HEAVY} until {@link
     * #MIN_LOAD_PAIRS} steps have. A step seen under one load only is priced for the other at this
     * ratio: a full rebuild runs every step beside every compile.
     */
    double lightOverHeavy() {
        double memo = lightOverHeavy;
        if (!Double.isNaN(memo)) return memo;
        List<Double> ratios = new ArrayList<>();
        for (Map.Entry<String, List<Indexed>> e : index.entrySet()) {
            String step = e.getKey().split("\n", -1)[1];
            if (loadChangesWork(step)) continue;
            Map<String, List<Long>> light = new HashMap<>();
            Map<String, List<Long>> heavy = new HashMap<>();
            for (Indexed r : e.getValue()) {
                RunShapes.Shape s = r.run().shape();
                String kind = s.profile() + '\n' + s.filtered();
                (s.forced() ? heavy : light)
                        .computeIfAbsent(kind, k -> new ArrayList<>())
                        .add(r.ms());
            }
            for (Map.Entry<String, List<Long>> h : heavy.entrySet()) {
                List<Long> l = light.get(h.getKey());
                if (l == null) continue;
                long hm = median(h.getValue());
                if (hm < MIN_LOAD_PAIR_MS) continue;
                ratios.add((double) median(l) / hm);
            }
        }
        double ratio = DEFAULT_LIGHT_OVER_HEAVY;
        if (ratios.size() >= MIN_LOAD_PAIRS) {
            ratios.sort(Double::compare);
            ratio = Math.max(0.2, Math.min(1.0, ratios.get(ratios.size() / 2)));
        }
        lightOverHeavy = ratio;
        return ratio;
    }

    /**
     * The steps that ran ({@code SUCCESS}) in {@code dir} in the latest run of {@code shape} that
     * ran any there, in recorded order; empty when none did. A forced build's forecast lists a
     * clean module with no steps (it reads as cached), yet the build runs all of them again: these
     * are what it ran the last time.
     */
    public List<String> stepsRun(String dir, RunShapes.Shape shape) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            if (!sameShape(r.shape(), shape)) continue;
            List<String> out = new ArrayList<>();
            for (Sample s : r.samples()) {
                if (s.dir().equals(dir) && "SUCCESS".equals(s.status()) && !out.contains(s.step())) out.add(s.step());
            }
            if (!out.isEmpty()) return out;
        }
        return List.of();
    }

    /** Curves {@link #curve} reads at most. */
    static final int MAX_CURVES = 5;

    /**
     * The curve the suite of {@code dir} likely follows ({@link SuiteCurve}): the pointwise median
     * of its latest curves from runs of the same shape and work set, else of the same shape, else
     * of the same profile and filter; {@code null} when none did. One run's curve is noisy where a
     * long class ends: the engine's suite was 98% through its wall at 90% of its class walls in one
     * engine body edit and 76% in the next two. Curves of other work sets are not mixed in: what
     * runs beside a suite shapes its curve, and replaying the 2026-11-12 corpus with them mixed in
     * doubled the median run's ETA error.
     */
    public int @Nullable [] curve(String dir, RunShapes.Shape shape, Set<String> frontier) {
        List<int[]> sameWork = new ArrayList<>();
        List<int[]> sameShape = new ArrayList<>();
        List<int[]> sameProfile = new ArrayList<>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            int[] c = r.curves().get(dir);
            if (c == null) continue;
            if (sameShape(r.shape(), shape)) {
                (r.frontier().equals(frontier) ? sameWork : sameShape).add(c);
            } else if (r.shape().profile().equals(shape.profile()) && r.shape().filtered() == shape.filtered()) {
                sameProfile.add(c);
            }
        }
        List<int[]> tier = !sameWork.isEmpty() ? sameWork : !sameShape.isEmpty() ? sameShape : sameProfile;
        if (tier.isEmpty()) return null;
        List<int[]> read = tier.subList(0, Math.min(MAX_CURVES, tier.size()));
        int[] out = new int[SuiteCurve.POINTS];
        int[] at = new int[read.size()];
        for (int p = 0; p < SuiteCurve.POINTS; p++) {
            for (int i = 0; i < read.size(); i++) at[i] = read.get(i)[p];
            Arrays.sort(at);
            int n = at.length;
            out[p] = n % 2 == 1 ? at[n / 2] : (at[n / 2 - 1] + at[n / 2]) / 2;
            if (p > 0) out[p] = Math.max(out[p], out[p - 1]);
        }
        return out;
    }

    /** Two runs of one shape: the same profile, forced or not, filtered or not. */
    private static boolean sameShape(RunShapes.Shape a, RunShapes.Shape b) {
        return a.profile().equals(b.profile()) && a.forced() == b.forced() && a.filtered() == b.filtered();
    }

    /**
     * The median of {@code values}, newest first; of an even count's middle two, the newer: with one
     * old and one new sample the older is no likelier, and the upper of the two priced a forced
     * build from the first build's slower walls.
     */
    private static long median(List<Long> values) {
        List<Integer> order = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(values.get(a), values.get(b)));
        int n = order.size();
        if (n % 2 == 1) return values.get(order.get(n / 2));
        return values.get(Math.min(order.get(n / 2 - 1), order.get(n / 2)));
    }

    private static String key(String dir, String step, String status) {
        return dir + '\n' + step + '\n' + status;
    }
}
