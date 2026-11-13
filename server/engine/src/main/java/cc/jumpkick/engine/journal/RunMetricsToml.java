// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.RunMeasures;
import cc.jumpkick.util.DirKeys;
import cc.jumpkick.wire.runtime.TestSuiteScaling;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A finished run's measures as {@link MetricsFile} text, which its project's {@link
 * cc.jumpkick.builds.ProjectStore} and the host's {@link cc.jumpkick.builds.HostStore} fold: the
 * walls of its successful steps and its invocation, and its suites' class walls.
 */
final class RunMetricsToml {

    private RunMetricsToml() {}

    /**
     * Null for a running or cancelled run, or one with no wall to record; only the class walls of a
     * failed one. Takes from {@code measures} what it renders.
     */
    static @Nullable String render(BuildRecord finished, RunMeasures measures) {
        if (finished == null || finished.running() || finished.cancelled()) return null;
        if (!finished.success()) return renderClassWallsOnly(finished, measures);
        StringBuilder sb = new StringBuilder();
        if (finished.millis() > 0) {
            sb.append("workspace.wall-ms = ").append(finished.millis()).append('\n');
            String kind = sanitize(finished.kind() == null ? "build" : finished.kind());
            sb.append("invocation.")
                    .append(kind)
                    .append(".wall-ms = ")
                    .append(finished.millis())
                    .append('\n');
            if (finished.dir() != null && !finished.dir().isBlank()) {
                int dirty = finished.modules() == null ? 0 : finished.modules().size();
                if (dirty == 0 && finished.steps() != null && !finished.steps().isEmpty()) dirty = 1;
                String dirKey = ModuleKeys.ROOT + (dirty > 0 ? "#d" + dirty : "");
                sb.append("invocation.")
                        .append(kind)
                        .append('.')
                        .append(dirKey)
                        .append(".wall-ms = ")
                        .append(finished.millis())
                        .append('\n');
            }
        }
        String root = finished.dir();
        boolean coldWorker = measures.coldWorker();
        // Each module's suite as it ran (its selection), found again by the class walls below.
        Map<String, String> suiteSteps = new LinkedHashMap<>();
        if (finished.steps() != null) {
            for (BuildRecord.Task s : finished.steps()) {
                if (coldWorker && isCompile(s)) continue;
                appendStepMetrics(sb, s, root, root, measures, suiteSteps);
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.steps() == null) continue;
                for (BuildRecord.Task s : m.steps()) {
                    // A compile on a worker still warming up is not this module's compile cost.
                    if (coldWorker && isCompile(s)) continue;
                    appendStepMetrics(sb, s, m.dir(), root, measures, suiteSteps);
                }
            }
        }
        // Test-class walls buffered during run-tests (FQCN → ms), after every scalar row.
        appendTestClassWalls(sb, root, root, measures, suiteSteps);
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m != null) appendTestClassWalls(sb, m.dir(), root, measures, suiteSteps);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * A failed run teaches no step wall, but its suites' classes ran for real: their walls are
     * kept, and the next run reads its suites' progress by them.
     */
    private static @Nullable String renderClassWallsOnly(BuildRecord finished, RunMeasures measures) {
        StringBuilder sb = new StringBuilder();
        String root = finished.dir();
        Map<String, String> suiteSteps = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        if (root != null) dirs.add(root);
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m != null && m.dir() != null && !dirs.contains(m.dir())) dirs.add(m.dir());
            }
        }
        for (String moduleDir : dirs) {
            var suite = measures.takeSuite(moduleDir);
            if (suite != null) suiteSteps.put(moduleDir, suite.step());
            appendTestClassWalls(sb, moduleDir, root, measures, suiteSteps);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * The module's class walls as one {@code [test-class."<dir>"."<pkg>"]} table per package
     * ({@link MetricsFile}). Written after every scalar row: a row after a header is one of that
     * package's classes.
     */
    private static void appendTestClassWalls(
            StringBuilder sb, String moduleDir, String root, RunMeasures measures, Map<String, String> suiteSteps) {
        if (moduleDir == null || moduleDir.isBlank()) return;
        // A tier's walls sit under the module's key with its selection appended: its classes are
        // its own, and a default-tier run must not read an integration class's wall as its own.
        String step = suiteSteps.getOrDefault(moduleDir, TaskNames.RUN_TESTS);
        Map<String, Long> walls = measures.takeClassWalls(moduleDir, step);
        if (walls.isEmpty()) return;
        Map<String, String> rows = new LinkedHashMap<>();
        for (var e : walls.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            rows.put(sanitize(e.getKey()), Long.toString(e.getValue()));
        }
        MetricsFile.appendClassWalls(sb, ModuleKeys.relative(moduleDir, root) + TaskNames.suiteScope(step), rows);
    }

    private static boolean isCompile(BuildRecord.Task s) {
        return s != null && s.name() != null && s.name().startsWith("compile-");
    }

    private static void appendStepMetrics(
            StringBuilder sb,
            BuildRecord.Task s,
            String moduleDir,
            String root,
            RunMeasures measures,
            Map<String, String> suiteSteps) {
        if (s == null || s.millis() <= 0) return;
        if (s.status() == null || !"SUCCESS".equalsIgnoreCase(s.status())) return;
        // A suite's wall is kept under the selection that ran it. A --class run measured only some
        // of the suite's classes, and leaves no suite wall at all.
        String suiteStep = null;
        if (TaskNames.RUN_TESTS.equals(s.name())) {
            var suite = moduleDir == null ? null : measures.takeSuite(moduleDir);
            if (suite != null) {
                suiteSteps.put(moduleDir, suite.step());
                if (suite.partial()) {
                    measures.takeRunners(moduleDir);
                    return;
                }
                suiteStep = suite.step();
            }
        }
        // A compile of part of the sources is a different cost from a whole one: kept apart.
        String name = TaskNames.COMPILE_JAVA.equals(s.name()) && TaskNames.compiledPart(s.detail())
                ? TaskNames.COMPILE_JAVA_INCREMENTAL
                : suiteStep != null ? suiteStep : s.name();
        String task = sanitize(name);
        // Cache-restore / token hits can land as SUCCESS with absurdly short walls (e.g. native-image
        // 32ms). Those poison ETA means — never teach heavy steps below a floor.
        if (isImplausibleHeavyWall(task, s.millis())) return;
        // wall-ms is the wall clock, queue wait included.
        sb.append("task.").append(task).append(".wall-ms = ").append(s.millis()).append('\n');
        if (moduleDir == null || moduleDir.isBlank()) return;
        String mod = ModuleKeys.relative(moduleDir, root);
        sb.append("module.")
                .append(mod)
                .append(".task.")
                .append(task)
                .append(".wall-ms = ")
                .append(s.millis())
                .append('\n');
        // A suite wall is only re-usable next time in its normalized form, one runner's wall: see
        // TestSuiteScaling.
        if (TaskNames.isRunTests(task)) {
            int runners = measures.takeRunners(moduleDir);
            if (runners > 0) {
                sb.append("module.")
                        .append(mod)
                        .append(".task.")
                        .append(task)
                        .append(".wall1-ms = ")
                        .append(TestSuiteScaling.normalize(s.millis(), runners))
                        .append('\n');
            }
        }
    }

    /**
     * Heavy IO steps whose real wall is tens of seconds — sub-floor samples are action-cache
     * restore noise, not real work.
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
        return DirKeys.slashes(s).replaceAll("[^a-zA-Z0-9._:/@-]+", "_");
    }
}
