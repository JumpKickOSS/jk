// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Each step's recent walls, per project, under the shape of the run that measured them: the
 * history the ETA prices a step from. One line per finished successful run in {@value #FILE} under
 * the project's home: the run's shape ({@link RunShapes.Shape}), its frontier (the modules whose
 * main compile ran), and every step's status and wall.
 *
 * <p>A step is priced at its median wall under the same status (a restore is not a run) and the
 * same shape where the shape changes the work: a suite under its own profile and never from a
 * filtered run, a compile or native image as forced or not. The first rung is the runs with the
 * same frontier: the same edit, which in an inner loop is the run before; then any frontier.
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
    record Run(RunShapes.Shape shape, Set<String> frontier, List<Sample> samples) {}

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

    /** The modules whose main compile ran in a finished run: its frontier. */
    public static Set<String> frontierOf(List<Sample> samples) {
        Set<String> out = new TreeSet<>();
        for (Sample s : samples) {
            if ("SUCCESS".equals(s.status())
                    && (TaskNames.COMPILE_JAVA.equals(s.step())
                            || TaskNames.COMPILE_KOTLIN.equals(s.step())
                            || TaskNames.COMPILE_GROOVY.equals(s.step()))) {
                out.add(s.dir());
            }
        }
        return out;
    }

    /**
     * Record a finished run's steps into the ledger under {@code projectHome}: one line, the
     * oldest dropped past {@link #MAX_RUNS}. Best effort; a failure is logged and forgotten.
     */
    public static void append(Path projectHome, RunShapes.Shape shape, List<Sample> samples) {
        List<Sample> kept = new ArrayList<>();
        for (Sample s : samples) {
            if (s.ms() >= MIN_MS) kept.add(s);
        }
        if (kept.isEmpty()) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("profile", shape.profile());
        line.put("forced", shape.forced());
        line.put("filtered", shape.filtered());
        line.put("frontier", List.copyOf(frontierOf(samples)));
        List<Object> steps = new ArrayList<>();
        for (Sample s : kept) steps.add(List.of(s.dir(), s.step(), s.status(), s.ms()));
        line.put("steps", steps);
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
                runs.add(new Run(shape, frontier, samples));
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
     * frontier is {@code frontier}: the median of the latest samples from runs of the same shape
     * (profile, forced, filtered) and frontier, else of the same shape, else of runs that differ
     * only where the step does not care (a suite keeps to its profile and is never priced from a
     * filtered run; a compile or native image keeps to forced or not), each rung same frontier
     * first. Empty when the ledger has none. A filtered suite has no price here: its selected
     * classes' walls are its price.
     *
     * <p>The whole shape comes first because it carries the run's load as well as its work: a
     * guard in a forced build ran beside every compile at once and took four times its wall in a
     * filtered test run, and a forced integration run's guard is neither.
     */
    public OptionalLong price(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        List<Long> rung = rung(dir, step, status, shape, frontier);
        return rung.isEmpty() ? OptionalLong.empty() : OptionalLong.of(median(rung));
    }

    /**
     * The likely range of {@code step}'s wall, {@code {low, high}}: the 20th and 80th percentiles
     * of the samples {@link #price} takes its median from, widened to at least {@link
     * #MIN_SPREAD} either side of the median when there are too few to spread. Empty when {@link
     * #price} is.
     */
    public Optional<long[]> range(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        List<Long> rung = rung(dir, step, status, shape, frontier);
        if (rung.isEmpty()) return Optional.empty();
        List<Long> sorted = new ArrayList<>(rung);
        sorted.sort(Long::compare);
        long median = median(sorted);
        long low = percentile(sorted, 0.2);
        long high = percentile(sorted, 0.8);
        low = Math.min(low, Math.round(median * (1 - MIN_SPREAD)));
        high = Math.max(high, Math.round(median * (1 + MIN_SPREAD)));
        return Optional.of(new long[] {Math.max(0, low), high});
    }

    /** The least a priced step's range spreads either side of its median: a run-to-run wobble. */
    static final double MIN_SPREAD = 0.15;

    /** The {@code q} quantile of {@code sorted}, interpolated between the samples either side. */
    private static long percentile(List<Long> sorted, double q) {
        double at = q * (sorted.size() - 1);
        int below = (int) Math.floor(at);
        int above = Math.min(sorted.size() - 1, below + 1);
        return Math.round(sorted.get(below) + (at - below) * (sorted.get(above) - sorted.get(below)));
    }

    /** The samples {@link #price} answers from: the first rung that has any (see there). */
    private List<Long> rung(String dir, String step, String status, RunShapes.Shape shape, Set<String> frontier) {
        boolean suite = TaskNames.isRunTests(step);
        if (suite && shape.filtered()) return List.of();
        List<Indexed> rows = index.getOrDefault(key(dir, step, status), List.of());
        if (rows.isEmpty()) return List.of();
        boolean forcedMatters = step.startsWith("compile-") || TaskNames.NATIVE_IMAGE.equals(step);
        List<Long> sameShapeFrontier = new ArrayList<>();
        List<Long> sameShape = new ArrayList<>();
        List<Long> same = new ArrayList<>();
        List<Long> any = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Indexed r = rows.get(i);
            RunShapes.Shape s = r.run().shape();
            boolean frontierMatches = r.run().frontier().equals(frontier);
            if (sameShape(s, shape)) {
                if (sameShapeFrontier.size() < SAME_FRONTIER && frontierMatches) sameShapeFrontier.add(r.ms());
                if (sameShape.size() < ANY_FRONTIER) sameShape.add(r.ms());
            }
            if (suite && (s.filtered() || !s.profile().equals(shape.profile()))) continue;
            if (forcedMatters && s.forced() != shape.forced()) continue;
            if (same.size() < SAME_FRONTIER && frontierMatches) same.add(r.ms());
            if (any.size() < ANY_FRONTIER) any.add(r.ms());
        }
        for (List<Long> rung : List.of(sameShapeFrontier, sameShape, same, any)) {
            if (!rung.isEmpty()) return rung;
        }
        return List.of();
    }

    /** Two runs of one shape: the same profile, forced or not, filtered or not. */
    private static boolean sameShape(RunShapes.Shape a, RunShapes.Shape b) {
        return a.profile().equals(b.profile()) && a.forced() == b.forced() && a.filtered() == b.filtered();
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static String key(String dir, String step, String status) {
        return dir + '\n' + step + '\n' + status;
    }
}
