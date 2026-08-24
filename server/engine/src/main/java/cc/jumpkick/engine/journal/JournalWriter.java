// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.CacheBenefit;
import cc.jumpkick.runtime.ChromeTimeline;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.test.MarkdownTestReport;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Fold live events into {@link BuildAccumulator} and persist the journal row. */
@RequiredArgsConstructor
public final class JournalWriter {

    private final JobSessions sessions;
    private final BuildJournal journal;
    private final JkHistoryConfig historyConfig;
    private final Supplier<Path> metricsFile;
    private final LongSupplier clock;
    private final String version;
    private final Consumer<String> log;

    public void register(
            long requestId,
            String kind,
            String dir,
            String trigger,
            boolean noTimeline,
            boolean rebuild,
            long buildNumber,
            String journalId) {
        Path projectDir = null;
        try {
            if (dir != null && !dir.isBlank()) projectDir = Path.of(dir);
        } catch (RuntimeException ignored) {
            projectDir = null;
        }
        ChromeTimeline timeline = ChromeTimeline.open(projectDir, noTimeline);
        sessions.accumulator(
                requestId,
                new BuildAccumulator(
                        kind, dir, coordOf(dir), trigger, timeline, rebuild, buildNumber, journalId, requestId));
    }

    public static @Nullable String coordOf(String dir) {
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve("jk.toml")).project();
            return project.group() + ":" + project.name();
        } catch (Exception e) {
            return null;
        }
    }

    public void accModule(long requestId, ModuleOutcome o) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addModule(o);
    }

    public void accModuleGraph(long requestId, Map<Path, Set<Path>> prereqs) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.setModuleEdges(prereqs);
    }

    public void accBuildPlanFinish(long requestId, String dir, BuildPlanResult result) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addBuildPlan(dir, result);
    }

    public void accStepStart(long requestId, String dir, String step, String phase) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.noteTaskStart(dir, step, phase);
    }

    public void accStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.addTask(dir, step, phase, status, millis);
    }

    public void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null && tests != null) a.addTests(tests);
    }

    public void accOutcome(long requestId, boolean success, int exitCode) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a != null) a.setOutcome(success, exitCode);
    }

    public void write(long requestId, boolean cancelled, long millis, @Nullable BufferedWriter writer) {
        BuildAccumulator a = sessions.takeAccumulator(requestId);
        if (a == null) {
            // Usually means clearProgress/retire ran first — leaves a permanent running=true journal
            // stub (jk jobs "Building" forever). Surface it; do not silently drop.
            log.accept("jk engine: build journal skip requestId=" + requestId + " (no accumulator)");
            return;
        }
        try {
            long finishedAt = clock.getAsLong();
            String commit = gitCommit(a.dir());
            boolean cancelledEffective = cancelled || a.wasCancelled();
            CacheBenefit.Result benefit = computeBenefit(a, millis);
            BuildRecord record = a.toRecord(finishedAt, cancelledEffective, millis, version, commit, benefit);
            long buildNumber = a.buildNumber();
            if (buildNumber > 0) record = record.withBuildNumber(buildNumber);
            a.flushTimeline().ifPresent(path -> {
                if (writer != null) WireWriter.sendQuiet(writer, ProtoJobs.timeline(path.toString()));
            });
            List<MarkdownTestReport.ModuleRun> tests = takeTests(a.dir());
            if (record.synthetic()) {
                if (historyConfig.enabled()) {
                    String jid = a.journalId();
                    if (jid != null && !jid.isBlank()) {
                        journal.delete(jid, record.coord(), record.dir());
                    }
                    journal.purgeProject(record.coord(), record.dir());
                }
                return;
            }
            Path runDir = null;
            if (historyConfig.enabled()) {
                Path dir = Path.of(a.dir());
                BuildJournal.Snapshot snapshot =
                        new BuildJournal.Snapshot(null, LockPaths.lockFile(dir), a.diagnosticsText());
                String jid = a.journalId();
                String locator;
                if (jid != null && !jid.isBlank()) {
                    locator = journal.complete(jid, record, snapshot) ? jid : journal.append(record, snapshot);
                } else {
                    locator = journal.append(record, snapshot);
                }
                if (locator != null && !locator.isBlank()) {
                    runDir = journal.runDir(locator).orElse(null);
                }
            }
            Path latest = writesProjectTarget(record.kind()) ? latestPath(a.dir()) : null;
            try {
                JkResultsMarkdown.write(record, runDir, latest, tests);
            } catch (IOException | RuntimeException e) {
                log.accept("jk engine: jk-results.md write failed: " + e);
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal append failed: " + e);
        }
    }

    static List<MarkdownTestReport.ModuleRun> takeTests(String dir) {
        if (dir == null || dir.isBlank()) return List.of();
        try {
            return MarkdownTestReport.takeUnder(Path.of(dir));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** {@code target/jk-results.md} at the invocation root. */
    static @Nullable Path latestPath(String dir) {
        if (dir == null || dir.isBlank()) return null;
        try {
            return Path.of(dir).resolve("target").resolve(JkResultsMarkdown.FILE_NAME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Maintenance kinds delete {@code target/}; writing the latest report there would recreate the
     * tree.
     */
    static boolean writesProjectTarget(String kind) {
        if (kind == null || kind.isBlank()) return true;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return !"clean".equals(k) && !"cache".equals(k);
    }

    private CacheBenefit.@Nullable Result computeBenefit(BuildAccumulator a, long millis) {
        if (!a.succeeded() || a.wasCancelled()) return null;
        BuildMetrics metrics = BuildMetrics.load(metricsFile.get());
        BiFunction<String, String, OptionalLong> baseline = (dir, step) -> {
            Optional<BuildMetrics.Entry> e = metrics.step(dir, step).filter(x -> x.ok().count() > 0);
            if (e.isEmpty()) e = metrics.step("", step).filter(x -> x.ok().count() > 0);
            return e.map(x -> OptionalLong.of(x.ok().avgMillis())).orElse(OptionalLong.empty());
        };
        return CacheBenefit.compute(a.benefitModules(), a.benefitModuleEdges(), millis, baseline);
    }

    /**
     * A {@code history-diag} replay line carrying the FULL persisted shape — the journal keeps
     * module/class/method/stack/snippet/worker and replay must not flatten a failure
     * back to task+message.
     */
    public static String historyDiagLine(BuildRecord.Diag d) {
        var o = JsonOut.object()
                .put("type", EngineProtocol.HISTORY_DIAG)
                .put("severity", d.severity())
                .put("task", d.step())
                .put("code", d.code())
                .put("message", d.message())
                .put("test", d.test())
                .put("exceptionClass", d.exceptionClass());
        if (d.module() != null && !d.module().isEmpty()) o.put("module", d.module());
        if (d.engine() != null && !d.engine().isEmpty()) o.put("engine", d.engine());
        if (d.className() != null && !d.className().isEmpty()) o.put("class", d.className());
        if (d.method() != null && !d.method().isEmpty()) o.put("method", d.method());
        if (d.stack() != null && !d.stack().isEmpty()) o.put("stack", d.stack());
        if (d.file() != null && !d.file().isEmpty()) o.put("file", d.file());
        if (d.line() > 0) o.put("line", d.line());
        if (d.col() > 0) o.put("col", d.col());
        if (d.snippetStart() > 0) o.put("snippetStart", d.snippetStart());
        if (d.snippet() != null && !d.snippet().isEmpty()) o.putStrings("snippet", d.snippet());
        if (d.worker() > 0) o.put("worker", d.worker());
        return o.toString();
    }

    /**
     * The project's git HEAD as a short SHA, or {@code null} when the dir isn't a git repo, git
     * isn't on PATH, or the call errors/times out. Best-effort (1s cap): a commit stamp is a
     * nice-to-have on the history record, never worth failing or stalling journaling.
     *
     * <p>stderr is discarded at the OS level and stdout drained on a side thread, so the 1s cap
     * actually holds. Reading stdout to EOF inline deadlocks on a repo where git is chatty enough
     * to fill its stderr pipe: git can't exit, stdout never sees EOF, and waitFor is never
     * reached — on the journal teardown path that hangs the whole request.
     */
    static @Nullable String gitCommit(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        Process p = null;
        try {
            p = new ProcessBuilder("git", "-C", dir, "rev-parse", "--short", "HEAD")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process proc = p;
            StringBuilder sb = new StringBuilder();
            Thread drainer = new Thread(
                    () -> {
                        try (var in = proc.getInputStream()) {
                            sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                        }
                    },
                    "jk-git-commit-probe");
            drainer.setDaemon(true);
            drainer.start();
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            drainer.join(200);
            String out = sb.toString().trim();
            return p.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                if (p != null) p.destroyForcibly();
            }
            return null;
        }
    }
}
