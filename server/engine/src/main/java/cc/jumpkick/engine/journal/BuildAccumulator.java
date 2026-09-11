// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.CacheBenefit;
import cc.jumpkick.runtime.base.ChromeTimeline;
import cc.jumpkick.runtime.base.ProjectIds;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.test.AffectedTests;
import cc.jumpkick.wire.runtime.ModuleOutcome;
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
 * cc.jumpkick.wire.runtime.WorkspaceBuildListener}/{@link cc.jumpkick.run.BuildPlanListener} callbacks
 * that fire on scheduler/worker threads, then frozen into a {@link BuildRecord} at request-finish.
 * Success is the body's own verdict when it ruled ({@link #stamp}); otherwise it is derived from
 * the rows the run left behind — no failed module or plan, not cancelled, and at least one row.
 */
public final class BuildAccumulator {
    private final String kind;
    private final String dir;
    private final @Nullable String coord;
    private final @Nullable String projectId;
    private final String trigger; // how the build was started: "cli" (socket) or "web" (dashboard)
    /** Per-request chrome timeline; null when disabled. Same step millis as metrics. */
    private final @Nullable ChromeTimeline timeline;
    /** request was {@code --redo}/{@code --force} — train {@code build:rebuild} metrics. */
    private final boolean rebuild;

    /**
     * This run's byte accounting. Opened as the ambient ledger on the runner thread, so every
     * session the request builds meters into it (see {@link cc.jumpkick.task.IoLedger}).
     */
    private final IoLedger io = new IoLedger();

    // Plain lists under their own monitor (snapshot to iterate): CopyOnWriteArrayList copied the
    // whole backing array per append — O(n²) array churn for a build with many modules or
    // diagnostics, on the engine heap.
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
    private volatile BuildRecord.@Nullable Tests tests;
    private @Nullable AffectedTests affected;
    private volatile boolean anyFailure;
    // Whether this run recorded anything it can be judged on: a module outcome, a finished plan,
    // a finished step, a test summary. A started-but-unfinished step is not one — it is exactly
    // what an abandoned run leaves behind. A body that declines to rule leaves the verdict to
    // these; with none of them "no failure seen" is not evidence of success, it is silence.
    private volatile boolean anyFact;
    private volatile boolean userCancelled;
    private volatile @Nullable String cancelReason;
    private volatile @Nullable Boolean success;
    private volatile int exitCode;

    public BuildAccumulator(String kind, String dir, @Nullable String coord, String trigger) {
        this(kind, dir, coord, trigger, null, false);
    }

    public BuildAccumulator(String kind, String dir, @Nullable String coord, String trigger, ChromeTimeline timeline) {
        this(kind, dir, coord, trigger, timeline, false);
    }

    /** Start-time build number; 0 when unnumbered. */
    private final long buildNumber;
    /** In-flight journal id from begin(); null when history disabled or non-journaled. */
    private final @Nullable String journalId;

    /** Engine request id (MCP jid); 0 when unknown. */
    private final long requestId;

    public BuildAccumulator(
            String kind,
            String dir,
            @Nullable String coord,
            String trigger,
            @Nullable ChromeTimeline timeline,
            boolean rebuild) {
        this(kind, dir, coord, trigger, timeline, rebuild, 0L, null, 0L);
    }

    public BuildAccumulator(
            String kind,
            String dir,
            @Nullable String coord,
            String trigger,
            @Nullable ChromeTimeline timeline,
            boolean rebuild,
            long buildNumber,
            @Nullable String journalId) {
        this(kind, dir, coord, trigger, timeline, rebuild, buildNumber, journalId, 0L);
    }

    public BuildAccumulator(
            String kind,
            String dir,
            @Nullable String coord,
            String trigger,
            @Nullable ChromeTimeline timeline,
            boolean rebuild,
            long buildNumber,
            @Nullable String journalId,
            long requestId) {
        this.kind = kind;
        this.dir = dir;
        this.coord = coord;
        this.projectId = ProjectIds.idOf(dir);
        this.trigger = trigger;
        this.timeline = timeline;
        this.rebuild = rebuild;
        this.buildNumber = buildNumber;
        this.journalId = journalId;
        this.requestId = requestId;
    }

    public boolean rebuild() {
        return rebuild;
    }

    public long buildNumber() {
        return buildNumber;
    }

    public @Nullable String journalId() {
        return journalId;
    }

    public IoLedger io() {
        return io;
    }

    public String dir() {
        return dir;
    }

    /** True only when the runner explicitly reported success (not merely "no failure seen yet"). */
    public boolean succeeded() {
        return Boolean.TRUE.equals(success);
    }

    /** True when the runner already stamped success or failure via {@link #stamp}. */
    public boolean hasOutcome() {
        return success != null;
    }

    /**
     * Outcome for SSE {@code request-finish} — same rule as {@link #toRecord}: the body's stamp
     * when it ruled, else not-cancelled, no failure recorded, and at least one fact to say so.
     */
    public boolean effectiveSuccess(boolean cancelled) {
        if (cancelled) return false;
        return success != null ? success : (!anyFailure && anyFact);
    }

    /**
     * Genuine user/deadline cancellation — set by {@link #markUserCancelled} when CANCEL_REQUEST /
     * mid-job EOF / deadline fires, or by a finished plan with
     * {@link BuildPlanResult#userCancelled}. Not the racy end-of-request EOF after a terminal
     * outcome (that is ignored in {@link #markUserCancelled} / {@link #toRecord}).
     */
    public boolean wasCancelled() {
        return userCancelled;
    }

    /**
     * Stamp cancel immediately so a force-killed runner still journals as cancelled, not success.
     * No-op once {@link #stamp} recorded a verdict. For a non-{@code explicit} signal (socket
     * EOF), also a no-op once a module/plan reported failure ({@code anyFailure}): the client often closes
     * the socket the instant it reads a terminal failure, and that EOF must not re-label a
     * test/compile failure as cancelled. An {@code explicit} signal (CANCEL_REQUEST, dashboard
     * cancel, wall deadline) is not that race — a genuine abort after a module failure still
     * journals as cancelled.
     */
    public void markUserCancelled(boolean explicit) {
        if (success != null) return;
        if (!explicit && anyFailure) return;
        userCancelled = true;
    }

    /**
     * As {@link #markUserCancelled(boolean)}, recording <em>why</em> (wall deadline, …). The first
     * reason wins; it rides the journal as a warning diagnostic so a job with no wire writer still
     * records the cause, and {@code request-finish} carries it as {@code cancelReason}.
     */
    public void markUserCancelled(boolean explicit, String reason) {
        markUserCancelled(explicit);
        if (!userCancelled || reason == null || reason.isBlank() || cancelReason != null) return;
        cancelReason = reason;
        addDiag(new BuildRecord.Diag(
                "warning",
                "",
                null,
                "cancelled",
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0));
    }

    /** Why the job was cancelled (deadline, …), or {@code null} when no reason was recorded. */
    public @Nullable String cancelReason() {
        return cancelReason;
    }

    /**
     * The row for a throw that escaped the job body. The envelope's catch stamps a failed verdict
     * alongside it, so the record names the exception instead of deriving green from whatever
     * clean rows the body recorded before it died.
     */
    public void addEscapedThrow(Throwable t) {
        addDiag(new BuildRecord.Diag(
                "error",
                "",
                null,
                "escaped-throw",
                t.getMessage() == null ? t.getClass().getName() : t.getMessage(),
                null,
                t.getClass().getName(),
                null,
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0));
    }

    public void addModule(ModuleOutcome o) {
        synchronized (modules) {
            modules.add(o);
        }
        anyFact = true;
        if (!o.success()) anyFailure = true;
    }

    /**
     * Journal-path diagnostics cap. Wire and SSE bound theirs at capture; the journal bounds here
     * so one pathological plan cannot persist an unbounded record. Overflow is dropped with an
     * explicit {@code +N more} marker row at record time.
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
                        "",
                        0,
                        0,
                        0,
                        List.of(),
                        0));
            }
            return out;
        }
    }

    /**
     * The row that says "I do not know" in the only vocabulary the record has. A journal entry
     * carries a verdict and a cancel bit and nothing in between, so a run that produced neither
     * says so in its diagnostics rather than picking a side silently.
     */
    private static List<BuildRecord.Diag> withNoVerdictRow(List<BuildRecord.Diag> diags) {
        List<BuildRecord.Diag> out = new ArrayList<>(diags);
        out.add(new BuildRecord.Diag(
                "error",
                "",
                null,
                "no-verdict",
                "the job produced no result: it recorded no module, plan, step or test row and "
                        + "returned no verdict, so this run is not known to have succeeded",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0));
        return out;
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
            m.put(step, new BuildRecord.Task(step, phase == null ? "" : phase, "RUN", 0L, 0L));
        }
    }

    /** One finished step, stored under its module dir ("" for a single-plan build). */
    public void addTask(String dir, String step, String phase, String status, long millis, long waitMillis) {
        anyFact = true;
        stepsByDir
                .computeIfAbsent(dir == null ? "" : dir, k -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(step, new BuildRecord.Task(step, phase, status, millis, waitMillis));
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
        List<HttpLive.Module> moduleList = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (ModuleOutcome o : moduleSnapshot()) {
            String mdir = o.dir() == null ? "" : o.dir().toString();
            covered.add(mdir);
            moduleList.add(new HttpLive.Module(
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
            moduleList.add(new HttpLive.Module(
                    d, null, /* finished */ false, false, 0L, /* didWork n/a */ true, liveTasks(stepsFor(d))));
        }
        List<HttpLive.Task> top = moduleList.isEmpty() ? liveTasks(stepsFor("")) : List.of();
        return new MidFlight(moduleList, top);
    }

    private static List<HttpLive.Task> liveTasks(List<BuildRecord.Task> steps) {
        List<HttpLive.Task> out = new ArrayList<>(steps.size());
        for (BuildRecord.Task s : steps) {
            out.add(new HttpLive.Task(s.name(), s.stage(), s.status(), s.millis()));
        }
        return out;
    }

    public record MidFlight(List<HttpLive.Module> modules, List<HttpLive.Task> tasks) {
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
        anyFact = true;
        String d0 = dir == null ? "" : dir;
        // Prefer the plan's own dir for.env lookup; fall back to the run's entry dir.
        String redactDir = (dir != null && !dir.isBlank()) ? dir : this.dir;
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            addDiag(diagFromPlan("error", d0, redactDir, d));
        }
        for (BuildPlanResult.Diagnostic d : result.warnings()) {
            addDiag(diagFromPlan("warning", d0, redactDir, d));
        }
        // Capture the step dependency edges from the genuine in-process result (engine-side
        // result.steps is reliably populated, unlike a client-side reconstruction).
        for (BuildPlanResult.StepReport s : result.steps()) {
            requiresByDir.computeIfAbsent(d0, k -> new ConcurrentHashMap<>()).put(s.name(), List.copyOf(s.requires()));
        }
        if (!result.success()) anyFailure = true;
        if (result.userCancelled()) userCancelled = true;
    }

    /**
     * Copy a plan diagnostic into the journal, filling {@code file}/{@code line}/{@code col} from
     * a javac/kotlinc/groovyc header (and caret) when the plan row left them empty.
     */
    static BuildRecord.Diag diagFromPlan(String severity, String dir, String redactDir, BuildPlanResult.Diagnostic d) {
        String redacted = redactEnv(redactDir, d.message());
        String message = redacted == null ? "" : redacted;
        String file = d.file() == null ? "" : d.file();
        int line = d.line();
        int col = 0;
        CompilerLocus loc = CompilerLocus.parse(message);
        if (loc != null) {
            // The parsed column belongs with the parsed file/line: a diag whose file/line came
            // from elsewhere (test identity) must not adopt a column from a locus its message
            // merely quotes.
            boolean fromMessage = file.isEmpty() && line <= 0;
            if (file.isEmpty()) file = loc.file();
            if (line <= 0) line = loc.line();
            if (fromMessage) col = loc.col();
        }
        return new BuildRecord.Diag(
                severity,
                dir,
                d.step(),
                d.code(),
                message,
                d.test(),
                d.exceptionClass(),
                d.module(),
                d.engine(),
                d.className(),
                d.method(),
                redactEnv(redactDir, d.stack()),
                file,
                line,
                col,
                d.snippetStart(),
                d.snippet(),
                d.worker());
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
        anyFact = true;
        tests = tests == null
                ? new BuildRecord.Tests(t.total(), t.succeeded(), t.failed(), t.skipped())
                : new BuildRecord.Tests(
                        tests.total() + t.total(),
                        tests.succeeded() + t.succeeded(),
                        tests.failed() + t.failed(),
                        tests.skipped() + t.skipped());
    }

    /**
     * Fold in one plan's affected-tests slice ({@code --affected} runs). Workspace builds call it
     * per module; the merged report is written to {@code jk-tests-affected.md} at request-finish.
     * The accumulator lives exactly one request, so a later run never inherits this run's rows.
     */
    public synchronized void addAffected(@Nullable AffectedTests slice) {
        if (slice == null) return;
        anyFact = true; // a ranking (even "nothing affected") is a fact — never a synthetic record
        affected = affected == null ? slice : affected.merge(slice);
    }

    public synchronized @Nullable AffectedTests affected() {
        return affected;
    }

    /**
     * Fold in the body's verdict — the one place a {@link JobOutcome} reaches the journal.
     *
     * <p>{@link JobOutcome.Declined} deliberately stamps nothing: the run's own rows are the
     * verdict, and {@link #toRecord} refuses to read an empty set of them as success.
     */
    public void stamp(JobOutcome outcome) {
        // Cancelled records only that the body stopped for a cancel; which cancel it was — user,
        // deadline, or a socket race the engine must not believe — stays the cancel stamps' call.
        switch (outcome) {
            case JobOutcome.Succeeded ignored -> setOutcome(true, Exit.SUCCESS);
            case JobOutcome.Failed failed -> setOutcome(false, failed.exitCode());
            case JobOutcome.Cancelled ignored -> setOutcome(false, Exit.FAILURE);
            case JobOutcome.Declined ignored -> {}
        }
    }

    private void setOutcome(boolean ok, int exit) {
        this.success = ok;
        this.exitCode = exit;
        if (!ok) anyFailure = true;
    }

    public @Nullable String diagnosticsText() {
        List<BuildRecord.Diag> diags = diagSnapshot();
        if (diags.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (BuildRecord.Diag d : diags) {
            b.append('[').append(d.severity()).append("] ");
            String step = d.step();
            String test = d.test();
            String thrown = d.exceptionClass();
            if (step != null && !step.isBlank()) b.append(step).append(": ");
            if (test != null && !test.isBlank()) b.append(test).append(" — ");
            if (thrown != null && !thrown.isBlank())
                b.append('(').append(thrown).append(") ");
            b.append(d.message() == null ? "" : d.message()).append('\n');
        }
        return b.toString();
    }

    public BuildRecord toRecord(
            long finishedAt, boolean cancelled, long millis, String jkVersion, @Nullable String commit) {
        return toRecord(finishedAt, cancelled, millis, jkVersion, commit, null);
    }

    public BuildRecord toRecord(
            long finishedAt,
            boolean cancelled,
            long millis,
            String jkVersion,
            @Nullable String commit,
            CacheBenefit.@Nullable Result benefit) {
        // A body that declined to rule leaves the verdict to its rows. With no rows and no cancel
        // there is nothing to derive from, and "no failure recorded" is the same silence a body
        // that died before its first row leaves behind — so that run is written down as a failure
        // the user can act on, with a diagnostic saying the job produced no result at all.
        boolean noVerdict = success == null && !anyFact && !cancelled;
        boolean ok = success != null ? success : (!anyFailure && !cancelled && !noVerdict);
        // cancelToken / late markUserCancelled also trip on the benign end-of-request EOF (the
        // client closes the socket as soon as it reads the terminal). Trust a stamped outcome:
        // success is never cancelled; an explicit failure is cancelled only when the user/deadline
        // stamp was set (not merely cancelled=true from cooperative fail-fast / EOF race).
        boolean cancelledEffective = resolveCancelledFlag(success, userCancelled, cancelled);
        // One derivation, cancelled arm first: a row labelled cancelled carries the code every
        // shell already means by an interrupt, so `$?` and `jk history` agree about the same run.
        // Until every cancel wrote FAILURE and read back as an ordinary failed build; the
        // exit of work that stopped before it could rule is not evidence of anything else.
        int exit = cancelledEffective
                ? Exit.INTERRUPTED
                : success != null ? exitCode : (ok ? Exit.SUCCESS : (noVerdict ? Exit.SOFTWARE : Exit.FAILURE));
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
        IoLedger.Totals bytes = io.totals();
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
                noVerdict ? withNoVerdictRow(diagSnapshot()) : diagSnapshot(),
                trigger,
                commit,
                benefitRow,
                false,
                ioRow,
                requestId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static @Nullable String redactEnv(String dir, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            Path root;
            if (dir != null && !dir.isBlank()) {
                root = Path.of(dir);
            } else {
                root = SessionContext.current().workingDir();
            }
            if (root == null) return text;
            return BuildEnv.secretsFor(root).redact(text);
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
    public static boolean resolveCancelledFlag(
            @Nullable Boolean successStamp, boolean userCancelled, boolean cancelHint) {
        if (Boolean.TRUE.equals(successStamp)) return false;
        if (userCancelled) return true;
        if (successStamp != null) return false; // explicit failure without a user-cancel stamp
        return cancelHint;
    }
}
