// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The remaining-work models a run's countdown was seeded from, kept beside its record as {@code
 * eta-model.jsonl}: one line per model the run received (a model that arrives mid-build is a second
 * line, and a re-pricing carries its {@code revision}), each with the time it arrived, R0, the
 * schedule knobs and every module's cost.
 *
 * <p>With the run's {@code details.jsonl}, which carries every module's plan progress and finish,
 * this is everything the live residual read: {@code bench/eta/Replay.java} feeds the two back
 * through {@link cc.jumpkick.wire.runtime.RemainingWork} offline, so a change to the live model is
 * scored against recorded runs in seconds instead of re-running them.
 *
 * <p>An in-process hand-off keyed by request id, drained when the journal writes the run: the model
 * is not part of the record.
 */
public final class EtaModels {

    /** The sidecar's name in a run directory. */
    public static final String FILE = "eta-model.jsonl";

    /** Requests whose run never journals (a refused job) must not pin their models forever. */
    private static final int MAX_REQUESTS = 64;

    private record Seed(long at, WorkModel model) {}

    private static final ConcurrentHashMap<Long, List<Seed>> BY_REQUEST = new ConcurrentHashMap<>();

    private EtaModels() {}

    public static void put(long requestId, WorkModel model) {
        if (requestId <= 0 || model == null) return;
        if (BY_REQUEST.size() >= MAX_REQUESTS && !BY_REQUEST.containsKey(requestId)) {
            BY_REQUEST.keySet().stream().min(Long::compare).ifPresent(BY_REQUEST::remove);
        }
        List<Seed> seeds = BY_REQUEST.computeIfAbsent(requestId, k -> new ArrayList<>());
        // A re-pricing arrives on its own thread (JK-0PDBN), beside the journal's drain.
        synchronized (seeds) {
            seeds.add(new Seed(Clock.SYSTEM.millis(), model));
        }
    }

    /**
     * What {@code requestId}'s seed priced {@code step} in {@code dir} at: the first model that priced
     * the module, the one the scorer attributes against; null when none did.
     */
    public static @Nullable StepCost priced(long requestId, String dir, String step) {
        List<Seed> seeds = BY_REQUEST.get(requestId);
        if (seeds == null) return null;
        synchronized (seeds) {
            for (Seed s : seeds) {
                for (var e : s.model().steps().entrySet()) {
                    if (!String.valueOf(e.getKey()).equals(dir)) continue;
                    for (StepCost c : e.getValue()) {
                        if (c.step().equals(step)) return c;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    /** Drain {@code requestId}'s models into {@code runDir}; nothing is written when there were none. */
    static void write(Path runDir, long requestId) throws IOException {
        List<Seed> seeds = BY_REQUEST.remove(requestId);
        if (seeds == null || seeds.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        synchronized (seeds) {
            for (Seed s : seeds) sb.append(MiniJson.write(line(s))).append('\n');
        }
        Files.writeString(runDir.resolve(FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> line(Seed s) {
        WorkModel m = s.model();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", s.at());
        out.put("R0", m.R0());
        if (m.revision() > 0) out.put("revision", m.revision());
        out.put("concurrency", m.concurrency());
        out.put("serial", m.serial());
        out.put("parallelTests", m.parallelTests());
        List<Object> costs = new ArrayList<>();
        for (ModuleWorkCost c : m.costs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dir", String.valueOf(c.dir()));
            List<String> prereqs = new ArrayList<>();
            if (c.prereqs() != null) for (Path p : c.prereqs()) prereqs.add(String.valueOf(p));
            row.put("prereqs", prereqs);
            row.put("weight", c.weight());
            row.put("testWeight", c.testWeight());
            row.put("tailWeight", c.tailWeight());
            row.put("gateWeight", c.gateWeight());
            row.put("suiteWall1", c.suiteWall1());
            row.put("suiteClasses", c.suiteClasses());
            costs.add(row);
        }
        out.put("costs", costs);
        if (!m.steps().isEmpty()) {
            Map<String, Object> steps = new LinkedHashMap<>();
            for (var e : m.steps().entrySet()) {
                List<Object> rows = new ArrayList<>();
                for (StepCost c : e.getValue())
                    rows.add(List.of(
                            c.step(),
                            c.weight(),
                            c.kind().name(),
                            Math.round(c.spread() * 1000) / 1000.0,
                            c.workers(),
                            c.rung().name()));
                steps.put(String.valueOf(e.getKey()), rows);
            }
            out.put("steps", steps);
        }
        return out;
    }
}
