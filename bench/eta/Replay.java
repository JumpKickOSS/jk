// SPDX-License-Identifier: Apache-2.0
//
// Replay a recorded run through the live remaining-work model, offline.
//
//   java -cp <dist>/lib/jk-engine-<v>.jar bench/eta/Replay.java <run dir>... [--series]
//
// A run directory holds eta-model.jsonl (the model the countdown was seeded from, EtaModels) and
// details.jsonl (every module's plan progress and finish). Replay seeds RemainingWork from the
// model and feeds it the recorded events in order, exactly as the engine's SsePublisher does, then
// compares at every recorded workspace-progress event:
//
//   - fidelity: the replayed remaining against the remaining the engine emitted (should match: it
//     is the same code on the same inputs; a gap means the replay misses an input)
//   - accuracy: the replayed remaining against the true remaining (the run's end minus now), at
//     10/25/50/75/90% of the wall, the same checkpoints score.py reads
//
// Run it against the engine jar of the model under test: a changed RemainingWork is scored against
// every recorded run without building anything.

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Replay {

    static final double[] CHECKPOINTS = {0.10, 0.25, 0.50, 0.75, 0.90};

    public static void main(String[] args) throws Exception {
        boolean series = false;
        List<Path> runs = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--series")) series = true;
            else runs.add(Path.of(a));
        }
        if (runs.isEmpty()) {
            System.err.println("usage: Replay <run dir>... [--series]");
            System.exit(2);
        }
        System.out.println("run      wall  fidelity(med/max)   replayed ETA err @10   @25   @50   @75   @90");
        for (Path run : runs) replay(run, series);
    }

    static void replay(Path run, boolean series) throws Exception {
        Path modelFile = run.resolve("eta-model.jsonl");
        Path details = run.resolve("details.jsonl");
        if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(details)) {
            System.out.printf("%-6s  (no eta-model.jsonl or details.jsonl)%n", run.getFileName());
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
        long t0 = num(events.get(0), "ts");
        long end = t0;
        for (Map<?, ?> e : events) {
            if ("workspace-finish".equals(e.get("type"))) end = num(e, "ts");
            else end = Math.max(end, num(e, "ts"));
        }
        long wall = end - t0;

        Map<String, String> dirOfCoord = new HashMap<>();
        RemainingWork rw = null;
        int nextSeed = 0;
        List<Double> fidelity = new ArrayList<>();
        List<long[]> points = new ArrayList<>(); // ts, replayed remaining
        for (Map<?, ?> e : events) {
            long ts = num(e, "ts");
            while (nextSeed < seeds.size() && num(seeds.get(nextSeed), "ts") <= ts) {
                rw = seed(seeds.get(nextSeed++));
            }
            String type = String.valueOf(e.get("type"));
            switch (type) {
                case "module-start" -> {
                    Object dir = e.get("dir");
                    Object coord = e.get("coord");
                    if (dir != null && coord != null) dirOfCoord.put(coord.toString(), dir.toString());
                }
                case "progress", "buildplan-start" -> {
                    if (rw == null) break;
                    String dir = dirOf(e, dirOfCoord);
                    long den = num(e, "denominator");
                    if (dir != null && den > 0) rw.moduleProgress(Path.of(dir), (double) num(e, "numerator") / den);
                }
                case "module-finish" -> {
                    if (rw == null) break;
                    String dir = dirOf(e, dirOfCoord);
                    if (dir != null) rw.moduleComplete(Path.of(dir));
                }
                case "workspace-progress" -> {
                    if (rw == null) break;
                    long recorded = num(e, "remainingMs");
                    long replayed = rw.remaining();
                    points.add(new long[] {ts, replayed});
                    if (recorded >= 0 && !"preflight".equals(e.get("phase"))) {
                        double base = Math.max(1000, recorded);
                        fidelity.add(Math.abs(replayed - recorded) / base);
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

        StringBuilder acc = new StringBuilder();
        for (double f : CHECKPOINTS) {
            long at = t0 + Math.round(f * wall);
            long[] shown = null;
            for (long[] p : points) {
                if (p[0] > at) break;
                shown = p;
            }
            long trueMs = end - at;
            if (shown == null || trueMs <= 0) {
                acc.append("     -");
                continue;
            }
            long eta = Math.max(0, shown[1] - (at - shown[0]));
            acc.append(String.format(" %+5.0f%%", 100.0 * (eta - trueMs) / trueMs));
        }
        fidelity.sort(Double::compare);
        String fid = fidelity.isEmpty()
                ? "      -"
                : String.format(
                        "%5.1f%% / %5.1f%%",
                        100 * fidelity.get(fidelity.size() / 2), 100 * fidelity.get(fidelity.size() - 1));
        System.out.printf("%-6s %5.0fs  %-18s %s%n", run.getFileName(), wall / 1000.0, fid, acc);
    }

    static RemainingWork seed(Map<?, ?> s) {
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
        return RemainingWork.seed(
                costs,
                num(s, "R0"),
                (int) num(s, "concurrency"),
                Boolean.TRUE.equals(s.get("serial")),
                Boolean.TRUE.equals(s.get("parallelTests")));
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
