// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.CacheBenefit;
import cc.jumpkick.runtime.ChromeTimeline;
import cc.jumpkick.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe collector of one build's outcome, folded from {@link
 * cc.jumpkick.runtime.WorkspaceBuildListener}/{@link cc.jumpkick.run.BuildPlanListener} callbacks
 * that fire on scheduler/worker threads, then frozen into a {@link BuildRecord} at request-finish.
 * Success is taken from the runner's terminal result when set, else derived (no failed module/plan
 * and not cancelled).
 */
public final class BuildAccumulator {
    private final String kind;
    private final String dir;
    private final String coord;
    private final String projectId;
    private final String trigger; // how the build was started: "cli" (socket) or "web" (dashboard)
    /** Per-request chrome timeline; null when disabled. Same step millis as metrics. */
    private final @Nullable ChromeTimeline timeline;
    /** request was {@code --redo}/{@code --force} — train {@code build:rebuild} metrics. */
    private final boolean rebuild;

    /**
     * This run's byte accounting. Opened as the ambient ledger on the runner thread, so every
     * session the request builds meters into it (see {@link cc.jumpkick.task.IoLedger}).
     */
    private final cc.jumpkick.task.IoLedger io = new cc.jumpkick.task.IoLedger();

    // Plain lists under their own monitor (snapshot to iterate): CopyOnWriteArrayList copied the
    // whole backing array per append — O(n²) array churn for a build with many modules or
    // diagnostics, on the engine heap (JK-1942).
    private final List<ModuleOutcome> modules = new ArrayList<>();
    // Steps per module dir (name → Step, arrival order, last status wins). The single-plan path
    // uses the "" (SINGLE_PLAN_DIR) bucket; workspace modules use their real dir. Rendered as a
    // chain per module (the dashboard shows one chain per module, not one merged strip).
    private final Map<String, Map<String, BuildRecord.Task>> stepsByDir = new ConcurrentHashMap<>();
    // Step dependency edges (dir → step name → requires), captured from the genuine in-process
    // BuildPlanResult in addBuildPlan. Reconstructs each module's step DAG for the critical-path
    // cache-benefit metric; the wire's stepFinish carries no edges, so this is the only source.
    private final Map<String, Map<String, List<String>>> requiresByDir = new ConcurrentHashMap<>();
    // Module dependency graph (dir → prereq dirs) from onModuleGraph; empty for single-plan builds.
    private volatile Map<String, Set<String>> moduleEdges = Map.of();
    private final List<BuildRecord.Diag> diagnostics = new ArrayList<>();
    private int droppedDiagnostics;
    private volatile BuildRecord.Tests tests;
    private volatile boolean anyFailure;
    private volatile boolean userCancelled;
    private volatile @Nullable Boolean success;
    private volatile int exitCode;

    public BuildAccumulator(String kind, String dir, String coord, String trigger) {
        this(kind, dir, coord, trigger, null, false);
    }

    public BuildAccumulator(String kind, String dir, String coord, String trigger, ChromeTimeline timeline) {
        this(kind, dir, coord, trigger, timeline, false);
    }

    /** Start-time build number; 0 when unnumbered. */
    private final long buildNumber;
    /** In-flight journal id from begin(); null when history disabled or non-journaled. */
    private final @Nullable String journalId;

    public BuildAccumulator(
            String kind, String dir, String coord, String trigger, ChromeTimeline timeline, boolean rebuild) {
        this(kind, dir, coord, trigger, timeline, rebuild, 0L, null);
    }

    public BuildAccumulator(
            String kind,
            String dir,
            String coord,
            String trigger,
            ChromeTimeline timeline,
            boolean rebuild,
            long buildNumber,
            String journalId) {
        this.kind = kind;
        this.dir = dir;
        this.coord = coord;
        this.projectId = cc.jumpkick.runtime.ProjectIds.idOf(dir);
        this.trigger = trigger;
        this.timeline = timeline;
        this.rebuild = rebuild;
        this.buildNumber = buildNumber;
        this.journalId = journalId;
    }

    public boolean rebuild() {
        return rebuild;
    }

    public long buildNumber() {
        return buildNumber;
    }

    public String journalId() {
        return journalId;
    }

    public cc.jumpkick.task.IoLedger io() {
        return io;
    }

    public String dir() {
        return dir;
    }

    /** True only when the runner explicitly reported success (not merely "no failure seen yet"). */
    public boolean succeeded() {
        return Boolean.TRUE.equals(success);
    }

    /** True when the runner already stamped success or failure via {@link #setOutcome}. */
    public boolean hasOutcome() {
        return success != null;
    }

    /**
     * Outcome for SSE {@code request-finish} — same default as {@link #toRecord}: explicit
     * stamp when set, else not-failed and not cancelled.
     */
    public boolean effectiveSuccess(boolean cancelled) {
        if (cancelled) return false;
        return success != null ? success : !anyFailure;
    }

    /**
     * Genuine user/deadline cancellation — set by {@link #markUserCancelled} when BUILD_CANCEL /
     * mid-job EOF / deadline fires, or by a finished plan with
     * {@link BuildPlanResult#userCancelled}. Not the racy end-of-request EOF after a terminal
     * outcome (that is ignored in {@link #markUserCancelled} / {@link #toRecord}).
     */
    public boolean wasCancelled() {
        return userCancelled;
    }

    /**
     * Stamp cancel immediately so a force-killed runner still journals as cancelled, not success.
     * No-op once {@link #setOutcome} ran. For a non-{@code explicit} signal (socket EOF), also a
     * no-op once a module/plan reported failure ({@code anyFailure}): the client often closes
     * the socket the instant it reads a terminal failure, and that EOF must not re-label a
     * test/compile failure as cancelled. An {@code explicit} signal (BUILD_CANCEL, dashboard
     * cancel, wall deadline) is not that race — a genuine abort after a module failure still
     * journals as cancelled (JK-1521).
     */
    public void markUserCancelled(boolean explicit) {
        if (success != null) return;
        if (!explicit && anyFailure) return;
        userCancelled = true;
    }

    public void addModule(ModuleOutcome o) {
        synchronized (modules) {
            modules.add(o);
        }
        if (!o.success()) anyFailure = true;
    }

    /**
     * Journal-path diagnostics cap. Wire and SSE bound theirs at capture (JK-1880); the journal
     * previously kept every row, so one pathological plan could persist an unbounded record.
     * Overflow is dropped with an explicit {@code +N more} marker row at record time.
     */
    static final int MAX_JOURNAL_DIAGNOSTICS = 500;

    private void addDiag(BuildRecord.Diag d) {
        synchronized (diagnostics) {
            if (diagnostics.size() >= MAX_JOURNAL_DIAGNOSTICS) {
                droppedDiagnostics++;
                return;
            }
            diagnostics.add(d);
        }
    }

    /** Snapshot including the overflow marker row when anything was dropped. */
    private List<BuildRecord.Diag> diagSnapshot() {
        synchronized (diagnostics) {
            List<BuildRecord.Diag> out = new ArrayList<>(diagnostics);
            if (droppedDiagnostics > 0) {
                out.add(new BuildRecord.Diag(
                        "warning",
                        "",
                        null,
                        "diagnostics-truncated",
                        "+" + droppedDiagnostics + " more diagnostics dropped at the journal cap ("
                                + MAX_JOURNAL_DIAGNOSTICS + ")",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        List.of(),
                        0));
            }
            return out;
        }
    }

    private List<ModuleOutcome> moduleSnapshot() {
        synchronized (modules) {
            return new ArrayList<>(modules);
        }
    }

    /**
     * Currently-running step (dashboard rehydrate). No-op when the step already has a
     * terminal status so a late {@code stepStart} cannot resurrect a finished row.
     */
    public void noteTaskStart(String dir, String step, String phase) {
        if (step == null || step.isBlank()) return;
        String d = dir == null ? "" : dir;
        Map<String, BuildRecord.Task> m =
                stepsByDir.computeIfAbsent(d, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (m) {
            BuildRecord.Task existing = m.get(step);
            if (existing != null && isTerminalTaskStatus(existing.status())) return;
            m.put(step, new BuildRecord.Task(step, phase == null ? "" : phase, "RUN", 0L));
        }
    }

    /** One finished step, stored under its module dir ("" for a single-plan build). */
    public void addTask(String dir, String step, String phase, String status, long millis) {
        stepsByDir
                .computeIfAbsent(dir == null ? "" : dir, k -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(step, new BuildRecord.Task(step, phase, status, millis));
        if (timeline != null) {
            timeline.complete(timelineModule(dir), step, status == null ? "" : status, millis);
        }
    }

    private static boolean isTerminalTaskStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "SUCCESS".equals(u)
                || "FAIL".equals(u)
                || "FAILED".equals(u)
                || "CANCELLED".equals(u)
                || "CANCELED".equals(u)
                || "SKIPPED".equals(u);
    }

    /**
     * Mid-flight modules/tasks for dashboard catch-up. Finished modules keep their outcome;
     * dirs with steps but no module outcome are in-progress workspace modules. When no module
     * rows exist, top-level tasks are the single-plan chain (including {@code RUN}).
     */
    public MidFlight midFlight() {
        List<cc.jumpkick.engine.http.HttpLive.Module> moduleList = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (ModuleOutcome o : moduleSnapshot()) {
            String mdir = o.dir() == null ? "" : o.dir().toString();
            covered.add(mdir);
            moduleList.add(new cc.jumpkick.engine.http.HttpLive.Module(
                    mdir,
                    o.coord(),
                    /* finished */ true,
                    o.success(),
                    o.millis(),
                    o.didWork(),
                    liveTasks(stepsFor(mdir))));
        }
        for (String d : stepsByDir.keySet()) {
            if (covered.contains(d)) continue;
            if (d.isEmpty()) continue; // single-plan top-level bucket
            moduleList.add(new cc.jumpkick.engine.http.HttpLive.Module(
                    d, null, /* finished */ false, false, 0L, /* didWork n/a */ true, liveTasks(stepsFor(d))));
        }
        List<cc.jumpkick.engine.http.HttpLive.Task> top = moduleList.isEmpty() ? liveTasks(stepsFor("")) : List.of();
        return new MidFlight(moduleList, top);
    }

    private static List<cc.jumpkick.engine.http.HttpLive.Task> liveTasks(List<BuildRecord.Task> steps) {
        List<cc.jumpkick.engine.http.HttpLive.Task> out = new ArrayList<>(steps.size());
        for (BuildRecord.Task s : steps) {
            out.add(new cc.jumpkick.engine.http.HttpLive.Task(s.name(), s.stage(), s.status(), s.millis()));
        }
        return out;
    }

    public record MidFlight(
            List<cc.jumpkick.engine.http.HttpLive.Module> modules, List<cc.jumpkick.engine.http.HttpLive.Task> tasks) {
        public MidFlight {
            modules = modules == null ? List.of() : List.copyOf(modules);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    /** Track label for chrome: entry coord when single-plan; else module path leaf. */
    private String timelineModule(String stepDir) {
        if (stepDir == null || stepDir.isBlank()) {
            return coord != null && !coord.isBlank() ? coord : (dir != null ? dir : "_");
        }
        try {
            Path p = Path.of(stepDir);
            Path name = p.getFileName();
            return name != null ? name.toString() : stepDir;
        } catch (RuntimeException e) {
            return stepDir;
        }
    }

    private volatile boolean timelineFlushed;

    public Optional<Path> flushTimeline() {
        if (timeline == null || timelineFlushed) return Optional.empty();
        Optional<Path> written = timeline.flush();
        if (written.isPresent()) timelineFlushed = true;
        return written;
    }

    /** Diagnostics + failure flag from a finished plan (steps come from {@link #addTask}). */
    public void addBuildPlan(String dir, BuildPlanResult result) {
        String d0 = dir == null ? "" : dir;
        // Prefer the plan's own dir for.env lookup; fall back to the run's entry dir.
        String redactDir = (dir != null && !dir.isBlank()) ? dir : this.dir;
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            addDiag(new BuildRecord.Diag(
                    "error",
                    d0,
                    d.step(),
                    d.code(),
                    redactEnv(redactDir, d.message()),
                    d.test(),
                    d.exceptionClass(),
                    d.module(),
                    d.engine(),
                    d.className(),
                    d.method(),
                    redactEnv(redactDir, d.stack()),
                    d.file(),
                    d.line(),
                    d.snippetStart(),
                    d.snippet(),
                    d.worker()));
        }
        for (BuildPlanResult.Diagnostic d : result.warnings()) {
            addDiag(new BuildRecord.Diag(
                    "warning",
                    d0,
                    d.step(),
                    d.code(),
                    redactEnv(redactDir, d.message()),
                    d.test(),
                    d.exceptionClass(),
                    d.module(),
                    d.engine(),
                    d.className(),
                    d.method(),
                    redactEnv(redactDir, d.stack()),
                    d.file(),
                    d.line(),
                    d.snippetStart(),
                    d.snippet(),
                    d.worker()));
        }
        // Capture the step dependency edges from the genuine in-process result (engine-side
        // result.steps is reliably populated, unlike a client-side reconstruction).
        for (BuildPlanResult.StepReport s : result.steps()) {
            requiresByDir.computeIfAbsent(d0, k -> new ConcurrentHashMap<>()).put(s.name(), List.copyOf(s.requires()));
        }
        if (!result.success()) anyFailure = true;
        if (result.userCancelled()) userCancelled = true;
    }

    public void setModuleEdges(Map<Path, Set<Path>> edges) {
        Map<String, Set<String>> m = new HashMap<>();
        if (edges != null) {
            for (var e : edges.entrySet()) {
                Set<String> prereqs = new HashSet<>();
                for (Path p : e.getValue()) prereqs.add(p.toString());
                m.put(e.getKey().toString(), prereqs);
            }
        }
        this.moduleEdges = m;
    }

    /** Per-module step inputs (status + millis + dependency edges) for the cache-benefit metric. */
    public List<CacheBenefit.ModuleInput> benefitModules() {
        List<CacheBenefit.ModuleInput> out = new ArrayList<>();
        for (String d : stepsByDir.keySet()) {
            Map<String, List<String>> req = requiresByDir.getOrDefault(d, Map.of());
            List<CacheBenefit.StepInput> steps = new ArrayList<>();
            for (BuildRecord.Task s : stepsFor(d)) {
                steps.add(new CacheBenefit.StepInput(
                        s.name(), s.status(), s.millis(), req.getOrDefault(s.name(), List.of())));
            }
            out.add(new CacheBenefit.ModuleInput(d, steps));
        }
        return out;
    }

    public Map<String, Set<String>> benefitModuleEdges() {
        return moduleEdges;
    }

    private List<BuildRecord.Task> stepsFor(String dir) {
        Map<String, BuildRecord.Task> m = stepsByDir.get(dir == null ? "" : dir);
        if (m == null) return List.of();
        synchronized (m) {
            return new ArrayList<>(m.values());
        }
    }

    /**
     * Fold in one plan's test summary. Single-plan {@code jk test}/{@code 1build} call this once;
     * a workspace build calls it per module (each module's {@code TEST_RESULT}), so the counts
     * accumulate into the run's total rather than the last module overwriting the rest.
     */
    public synchronized void addTests(TestSummary t) {
        if (t == null) return;
        tests = tests == null
                ? new BuildRecord.Tests(t.total(), t.succeeded(), t.failed(), t.skipped())
                : new BuildRecord.Tests(
                        tests.total() + t.total(),
                        tests.succeeded() + t.succeeded(),
                        tests.failed() + t.failed(),
                        tests.skipped() + t.skipped());
    }

    public void setOutcome(boolean ok, int exit) {
        this.success = ok;
        this.exitCode = exit;
        if (!ok) anyFailure = true;
    }

    public String diagnosticsText() {
        List<BuildRecord.Diag> diags = diagSnapshot();
        if (diags.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (BuildRecord.Diag d : diags) {
            b.append('[').append(d.severity()).append("] ");
            if (notBlank(d.step())) b.append(d.step()).append(": ");
            if (notBlank(d.test())) b.append(d.test()).append(" — ");
            if (notBlank(d.exceptionClass()))
                b.append('(').append(d.exceptionClass()).append(") ");
            b.append(d.message() == null ? "" : d.message()).append('\n');
        }
        return b.toString();
    }

    public BuildRecord toRecord(long finishedAt, boolean cancelled, long millis, String jkVersion, String commit) {
        return toRecord(finishedAt, cancelled, millis, jkVersion, commit, null);
    }

    public BuildRecord toRecord(
            long finishedAt,
            boolean cancelled,
            long millis,
            String jkVersion,
            String commit,
            CacheBenefit.Result benefit) {
        boolean ok = success != null ? success : (!anyFailure && !cancelled);
        int exit = success != null ? exitCode : (ok ? 0 : 1);
        // cancelToken / late markUserCancelled also trip on the benign end-of-request EOF (the
        // client closes the socket as soon as it reads the terminal). Trust a stamped outcome:
        // success is never cancelled; an explicit failure is cancelled only when the user/deadline
        // stamp was set (not merely cancelled=true from cooperative fail-fast / EOF race).
        boolean cancelledEffective = resolveCancelledFlag(success, userCancelled, cancelled);
        // Each workspace module carries its own step chain (keyed by its dir); a single-plan
        // build has no module rows, so its steps live in the record's top-level list (the ""
        // bucket). This is exactly the two shapes the dashboard renders (per-module vs compact).
        List<BuildRecord.Module> moduleList = new ArrayList<>();
        for (ModuleOutcome o : moduleSnapshot()) {
            String mdir = o.dir() == null ? "" : o.dir().toString();
            moduleList.add(
                    new BuildRecord.Module(o.coord(), mdir, o.success(), o.exitCode(), o.millis(), stepsFor(mdir)));
        }
        List<BuildRecord.Task> topSteps = moduleList.isEmpty() ? stepsFor("") : List.of();
        BuildRecord.CacheBenefit benefitRow = benefit == null
                ? null
                : new BuildRecord.CacheBenefit(
                        benefit.estimatedUncachedMillis(),
                        benefit.savedMillis(),
                        benefit.coveredSkips(),
                        benefit.totalSkips());
        cc.jumpkick.task.IoLedger.Totals bytes = io.totals();
        BuildRecord.Io ioRow = bytes.isEmpty()
                ? null
                : new BuildRecord.Io(bytes.remoteUp(), bytes.remoteDown(), bytes.localUp(), bytes.localDown());
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                kind,
                dir,
                coord,
                projectId,
                finishedAt - millis,
                finishedAt,
                millis,
                ok,
                cancelledEffective,
                exit,
                jkVersion,
                tests,
                moduleList,
                topSteps,
                diagSnapshot(),
                trigger,
                commit,
                benefitRow,
                false,
                ioRow);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String redactEnv(String dir, String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            Path root;
            if (dir != null && !dir.isBlank()) {
                root = Path.of(dir);
            } else {
                root = cc.jumpkick.config.SessionContext.current().workingDir();
            }
            if (root == null) return text;
            return cc.jumpkick.config.BuildEnv.secretsFor(root).redact(text);
        } catch (RuntimeException e) {
            return text;
        }
    }

    /**
     * Journal / SSE cancel bit from a stamped runner outcome + cancel flags.
     *
     * <ul>
     *   <li>Stamped success → never cancelled (EOF-after-finish race).
     *   <li>Stamped failure → cancelled only when the user/deadline stamp was set (not cooperative
     *       fail-fast or post-finish EOF).
     *   <li>No outcome yet (force-killed mid-job) → honour the cancel hint.
     * </ul>
     */
    public static boolean resolveCancelledFlag(Boolean successStamp, boolean userCancelled, boolean cancelHint) {
        if (Boolean.TRUE.equals(successStamp)) return false;
        if (userCancelled) return true;
        if (successStamp != null) return false; // explicit failure without a user-cancel stamp
        return cancelHint;
    }
}
