// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.TaskPhases;
import cc.jumpkick.runtime.base.TestClassWalls;
import cc.jumpkick.runtime.base.TestSuiteRunners;
import cc.jumpkick.util.DirKeys;
import cc.jumpkick.wire.runtime.TestSuiteScaling;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A finished run's {@code run-metrics.toml}: the walls of its successful steps and modules. */
final class RunMetricsToml {

    private RunMetricsToml() {}

    /** Writes nothing for a running, failed or cancelled run, or one with no wall to record. */
    static void write(Path dir, BuildRecord finished) throws IOException {
        if (finished == null || finished.running()) return;
        if (!finished.success() || finished.cancelled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# run metrics — successful steps/modules only\n");
        if (finished.millis() > 0) {
            sb.append("workspace.wall-ms = ").append(finished.millis()).append('\n');
            String kind = finished.kind() == null ? "build" : finished.kind();
            sb.append("invocation.")
                    .append(sanitize(kind))
                    .append(".wall-ms = ")
                    .append(finished.millis())
                    .append('\n');
            if (finished.dir() != null && !finished.dir().isBlank()) {
                int dirty = finished.modules() == null ? 0 : finished.modules().size();
                if (dirty == 0 && finished.steps() != null && !finished.steps().isEmpty()) dirty = 1;
                String dirKey = ModuleKeys.ROOT + (dirty > 0 ? "#d" + dirty : "");
                sb.append("invocation.")
                        .append(sanitize(kind))
                        .append(".")
                        .append(dirKey)
                        .append(".wall-ms = ")
                        .append(finished.millis())
                        .append('\n');
            }
        }
        String root = finished.dir();
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.millis() <= 0 || !m.success()) continue;
                String key = "module." + (m.coord() != null ? sanitize(m.coord()) : ModuleKeys.relative(m.dir(), root));
                sb.append(key).append(".wall-ms = ").append(m.millis()).append('\n');
            }
        }
        // Phase walls are summed per run — one key per phase. Per-task emission would fold as a
        // MEAN in MetricsHarvest (duplicate keys), deflating the phase dimension the taxonomy
        // exists to calibrate (4×2s compile tasks must read 8s, not 2s).
        Map<String, Long> phaseTotals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> modulePhaseTotals = new LinkedHashMap<>();
        if (finished.steps() != null) {
            for (BuildRecord.Task s : finished.steps()) {
                appendStepMetrics(sb, s, root, root, phaseTotals, modulePhaseTotals);
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.steps() == null) continue;
                for (BuildRecord.Task s : m.steps()) {
                    appendStepMetrics(sb, s, m.dir(), root, phaseTotals, modulePhaseTotals);
                }
            }
        }
        for (Map.Entry<String, Long> e : phaseTotals.entrySet()) {
            sb.append("phase.")
                    .append(e.getKey())
                    .append(".wall-ms = ")
                    .append(e.getValue())
                    .append('\n');
        }
        for (Map.Entry<String, Map<String, Long>> me : modulePhaseTotals.entrySet()) {
            for (Map.Entry<String, Long> e : me.getValue().entrySet()) {
                sb.append("module.")
                        .append(me.getKey())
                        .append(".phase.")
                        .append(e.getKey())
                        .append(".wall-ms = ")
                        .append(e.getValue())
                        .append('\n');
            }
        }
        // Test-class walls buffered during run-tests (FQCN → ms); train harvest without recounting methods.
        appendTestClassWalls(sb, root, root);
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m != null) appendTestClassWalls(sb, m.dir(), root);
            }
        }
        if (sb.length() > 40) {
            Files.writeString(dir.resolve(ProjectBuilds.METRICS), sb.toString(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The module's class walls as one {@code [test-class."<dir>"."<pkg>"]} table per package
     * ({@link MetricsFile}). Written after every scalar row: a row after a header is one of that
     * package's classes.
     */
    private static void appendTestClassWalls(StringBuilder sb, String moduleDir, String root) {
        if (moduleDir == null || moduleDir.isBlank()) return;
        Map<String, Long> walls = TestClassWalls.take(moduleDir);
        if (walls.isEmpty()) return;
        Map<String, String> rows = new LinkedHashMap<>();
        for (var e : walls.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            rows.put(sanitize(e.getKey()), Long.toString(e.getValue()));
        }
        MetricsFile.appendClassWalls(sb, ModuleKeys.relative(moduleDir, root), rows);
    }

    private static void appendStepMetrics(
            StringBuilder sb,
            BuildRecord.Task s,
            String moduleDir,
            String root,
            Map<String, Long> phaseTotals,
            Map<String, Map<String, Long>> modulePhaseTotals) {
        if (s == null || s.millis() <= 0) return;
        if (s.status() == null || !"SUCCESS".equalsIgnoreCase(s.status())) return;
        String task = sanitize(s.name());
        // Cache-restore / token hits can land as SUCCESS with absurdly short walls (e.g. native-image
        // 32ms). Those poison ETA means — never teach heavy steps below a floor.
        if (isImplausibleHeavyWall(task, s.millis())) return;
        // Prefer the stage the plan declared (wire `stage`) so metrics rollup and the UI fold
        // share one taxonomy. Name inference is only for records that carry none.
        String declared = s.stage();
        String phase = sanitize(declared != null && !declared.isBlank() ? declared : TaskPhases.of(s.name()));
        sb.append("task.").append(task).append(".wall-ms = ").append(s.millis()).append('\n');
        // wall-ms is the wall clock, queue wait included; wait-ms is the part of it spent blocked on
        // a shared resource (one compiler worker serving thirty modules). Summed walls across a
        // build read as machine-busy time, not work — the wait beside them says how much of that.
        if (s.waitMillis() > 0) {
            sb.append("task.")
                    .append(task)
                    .append(".wait-ms = ")
                    .append(s.waitMillis())
                    .append('\n');
        }
        phaseTotals.merge(phase, s.millis(), Long::sum);
        if (moduleDir != null && !moduleDir.isBlank()) {
            String mod = ModuleKeys.relative(moduleDir, root);
            sb.append("module.")
                    .append(mod)
                    .append(".task.")
                    .append(task)
                    .append(".wall-ms = ")
                    .append(s.millis())
                    .append('\n');
            if (s.waitMillis() > 0) {
                sb.append("module.")
                        .append(mod)
                        .append(".task.")
                        .append(task)
                        .append(".wait-ms = ")
                        .append(s.waitMillis())
                        .append('\n');
            }
            // A suite wall is only re-usable next time if we also say how many runners produced it,
            // and the re-usable form is the *normalized* one — see TestSuiteScaling.
            if (TaskNames.RUN_TESTS.equals(task)) {
                int runners = TestSuiteRunners.take(moduleDir);
                if (runners > 0) {
                    sb.append("module.")
                            .append(mod)
                            .append(".task.")
                            .append(task)
                            .append(".workers = ")
                            .append(runners)
                            .append('\n');
                    sb.append("module.")
                            .append(mod)
                            .append(".task.")
                            .append(task)
                            .append(".wall1-ms = ")
                            .append(TestSuiteScaling.normalize(s.millis(), runners))
                            .append('\n');
                }
            }
            // No input-bytes field here: nothing ever read it, and its value was wrong anyway —
            // BuildService's success fold takes (removes) the recorded bytes before the journal
            // write runs, so this always fell back to a fresh disk walk that could differ from
            // what ms/MB learning actually used. Size learning rides the in-memory
            // recordSuccessInputBytes → hostSamples path.
            modulePhaseTotals.computeIfAbsent(mod, k -> new LinkedHashMap<>()).merge(phase, s.millis(), Long::sum);
        }
    }

    /**
     * Heavy IO steps whose real wall is tens of seconds — sub-floor samples are action-cache
     * restore noise, not real work. Keep in sync with {@code MetricsHarvest} floors.
     */
    static boolean isImplausibleHeavyWall(String task, long millis) {
        if (task == null || millis <= 0) return false;
        String t = task.toLowerCase(Locale.ROOT);
        if (t.contains(TaskNames.NATIVE_IMAGE) || t.equals("native")) return millis < 5_000L;
        if (t.contains(TaskNames.WRITE_IMAGE) || t.equals("image")) return millis < 3_000L;
        return false;
    }

    static String sanitize(String s) {
        if (s == null) return "unknown";
        // Forward slashes first so Windows paths stay one key family with Unix; a POSIX
        // backslash name is NOT a separator and folds to '_' like any other odd character.
        return DirKeys.slashes(s).replaceAll("[^a-zA-Z0-9._:/-]+", "_");
    }
}
