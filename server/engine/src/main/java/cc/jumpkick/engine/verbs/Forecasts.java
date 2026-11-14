// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.jobs.JobSelect;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.BuildKnobs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The forecast of a build ({@code jk explain}), one operation for every surface: the wire verb
 * renders it as its event burst, REST {@code GET /api/explain} and MCP {@code explain} as {@link
 * #structured}. A REST or MCP forecast decodes the same job request a build does and runs under the
 * session a hosted build of it would, so it answers for exactly that build.
 */
public final class Forecasts {

    /** Module rows a structured forecast lists; the rest are counted. */
    static final int MAX_MODULE_ROWS = 100;

    /** The job-request fields a forecast reads: the selection, never the kind or deadline. */
    public static final List<JobRequests.Field> FIELDS = JobRequests.FIELDS.stream()
            .filter(f -> !"kind".equals(f.name()) && !"deadline_s".equals(f.name()))
            .toList();

    /** A forecast: refused with the build's own errors, or ready with its report. */
    public sealed interface Forecast {
        /** The selection matched nothing or the plan has errors; the build would fail the same way. */
        record Refused(List<String> errors) implements Forecast {}

        /** The plan and its estimates. */
        record Ready(ExplainReport report) implements Forecast {}
    }

    private Forecasts() {}

    /**
     * The explain request a REST or MCP caller's job fields ask for: the build a hosted job of
     * that selection would run, with the CLI's defaults for everything the fields do not name.
     */
    public static ExplainRequest request(
            Map<String, ?> fields, @Nullable String fallbackDir, Set<String> callerFields) {
        JobSpec spec = JobRequests.decode(fields, FIELDS, JobOrigin.WEB, "build", fallbackDir, callerFields);
        return new ExplainRequest(
                PathUtil.resolveUserPath(spec.dir()).toString(),
                JkDirs.cache().toString(),
                0,
                spec.skipTests(),
                null,
                JkDirs.jdks().toString(),
                false,
                true,
                false,
                false,
                0,
                spec.modules(),
                JobSelect.testSelection(spec.includeTags(), spec.excludeTags(), spec.suites(), spec.classes()),
                false);
    }

    /** {@link #forecast(ExplainRequest, Session, Calibration.ProbeAnnouncer)} under a hosted job's session. */
    public static Forecast forecast(ExplainRequest req) throws Exception {
        Session session = ProtoSession.sessionOf(req.encode(), Session.CancelToken.NONE);
        return forecast(req, session, (stage, done, total, label) -> {});
    }

    /**
     * Forecast {@code req} under {@code session}: its selector tokens resolve as the build verb
     * resolves them, and the plan is that build's cone. {@code probe} hears the host calibration a
     * first forecast runs.
     */
    public static Forecast forecast(ExplainRequest req, Session session, Calibration.ProbeAnnouncer probe)
            throws Exception {
        Path entryDir = Path.of(String.valueOf(req.dir()));
        Path cache = Path.of(String.valueOf(req.cache()));
        JkBuild entryBuild = JkBuildParser.parse(ManifestPaths.manifestIn(entryDir));
        int budget = req.maxModuleConcurrency();
        if (budget <= 0 && req.serial()) budget = 1;
        Set<Path> selection = Set.of();
        if (!req.modules().isEmpty()) {
            ModuleSelection.Result hit = JobSelect.resolveTokens(entryDir, entryBuild, req.modules());
            if (hit != null && !hit.ok()) {
                String why = hit.errorMessage();
                return new Forecast.Refused(List.of(why == null ? "module selection failed: " + req.modules() : why));
            }
            if (hit != null) {
                if (hit.moduleDirs().isEmpty()) {
                    return new Forecast.Refused(
                            List.of("selection matched no workspace module: " + String.join(", ", req.modules())));
                }
                selection = hit.moduleDirs().stream()
                        .map(BuildGraph::canonicalPath)
                        .collect(Collectors.toUnmodifiableSet());
            }
        }
        Calibration.ensureAnnounced(req.jdksDir() == null ? null : Path.of(req.jdksDir()), probe);
        BuildKnobs knobs = BuildKnobs.of(session)
                .withProfile(req.profile())
                .withSkipTests(req.skipTests())
                .withSelection(selection);
        int width = budget;
        // A quick forecast (jk status) must not start a compiler worker to plan a compile.
        ExplainReport report = ScopedValue.where(
                        JavaCompile.PLAN_WORKERS,
                        req.quick() ? JavaCompile.PlanWorkers.WARM : JavaCompile.PlanWorkers.ANY)
                .call(() -> ExplainReport.compute(entryDir, entryBuild, cache, session, knobs, width));
        if (report.plan().hasErrors()) return new Forecast.Refused(report.plan().errors());
        return new Forecast.Ready(report);
    }

    /**
     * The forecast as one bounded JSON object, the same on REST and MCP: per-module dirty and
     * cached-step counts (at most {@link #MAX_MODULE_ROWS} rows), the ETAs and the critical path.
     * Step rows stay on the wire burst {@code jk explain} renders.
     */
    public static Map<String, Object> structured(Forecast forecast) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (forecast instanceof Forecast.Refused(List<String> errors)) {
            m.put("errors", errors);
            return m;
        }
        ExplainReport report = ((Forecast.Ready) forecast).report();
        List<Map<String, Object>> mods = new ArrayList<>();
        int dirty = 0;
        long provisionalSteps = 0;
        List<TaskForecast.Module> modules = report.plan().modules();
        for (TaskForecast.Module mod : modules) {
            if (mod.dirty()) dirty++;
            // Verdicts that wait on a sibling the build has yet to compile: a guess the build settles.
            long provisional =
                    mod.steps().stream().filter(TaskForecast.Task::provisional).count();
            provisionalSteps += provisional;
            if (mods.size() >= MAX_MODULE_ROWS) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dir", mod.dir().toString());
            row.put("coord", mod.coord());
            row.put("dirty", mod.dirty());
            row.put(
                    "cachedSteps",
                    mod.steps().stream().filter(TaskForecast.Task::cached).count());
            row.put("steps", mod.steps().size());
            if (provisional > 0) row.put("provisionalSteps", provisional);
            mods.add(row);
        }
        m.put("modules", mods);
        if (modules.size() > mods.size()) m.put("moreModules", modules.size() - mods.size());
        m.put("dirtyCount", dirty);
        m.put("provisionalSteps", provisionalSteps);
        m.put("maxReadyWidth", report.plan().maxReadyWidth());
        m.put("etaMillis", report.etaMillis());
        m.put("fullMillis", report.fullMillis());
        // The estimate's likely range: the 10th to 90th percentile of where the truth lands.
        m.put("etaLowMillis", report.lowMillis());
        m.put("etaHighMillis", report.highMillis());
        m.put("etaConfidence", Math.round(report.confidence() * 100) / 100.0);
        if (!report.criticalPath().isEmpty()) {
            m.put(
                    "criticalPath",
                    report.criticalPath().stream().map(Path::toString).toList());
        }
        return m;
    }
}
