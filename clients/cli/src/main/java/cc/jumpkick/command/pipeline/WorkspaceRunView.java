// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.AggregateModuleListener;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.CompositeBuildPlanListener;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DashboardCodeLink;
import cc.jumpkick.cli.run.JsonlListener;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.run.LiveProgress;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.run.TestFailureHighlight;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The one workspace renderer. Every verb that drives an engine {@code buildWorkspace}-shaped RPC —
 * {@code build}, {@code test}, {@code run}, {@code compile}, {@code image}, {@code native},
 * {@code install} — attaches its {@link WorkspaceBuildListener} from here and settles through here,
 * so the module completion line, the buffered-output flush, the JSONL workspace vocabulary and the
 * four-arm cancel/errors/failure/success ladder exist once.
 *
 * <p><b>Two renderers, not one.</b> {@link #live} paints into a {@link JkManager} region;
 * {@link #headless} appends blocks under a print mutex and never opens a region. They are siblings
 * on the <em>mode</em> axis: every verb picks {@code headless} for {@code --output json} /
 * {@code --verbose} and {@code live} for a TTY, so no region is ever opened dormant with a JSON
 * stream running through it. {@code buffered} chrome may flush a block with
 * {@link JkManager#writeAbove}; non-buffered chrome owns no output of its own and must not.
 *
 * <p><b>One vocabulary.</b> Every verb here emits the same {@code workspace-*} / {@code module-*}
 * events: exactly one {@code workspace-start}, a {@code module-start}/{@code module-finish} pair per
 * module, and exactly one terminal {@code workspace-finish} on every exit path — success, failure,
 * cancel or a wire exception. The verb is a hint about which stages the build includes, not a
 * different kind of job, so a parser gets one envelope from all of them. {@code jk run} is the one
 * outlier and only at its tail: it emits the full vocabulary for the workspace pre-build,
 * terminates it before the exec, and writes no further stdout of its own — the child owns the
 * stream from there, so {@code workspace-finish} is end-of-stream for {@code run}.
 *
 * <p>What stays with the caller is <b>policy</b>: the tails ({@link Tails}), whatever it wants to
 * observe per module ({@code observer}), and whatever it does after the ladder picks an arm
 * ({@link Settled}). What lives here is <b>mechanism</b>, once — a denominator, a painted test
 * failure and the event vocabulary are rules of the renderer, not of the verb.
 */
final class WorkspaceRunView {

    /**
     * Per-verb rendering policy.
     *
     * @param planName the wedge/region name ({@code Build}, {@code Test}, …)
     * @param buffered whether module output is captured per module and flushed as one block on
     *     completion — also the flag that says this verb owns append-only output when the region is
     *     not animating
     */
    record Chrome(String planName, boolean buffered) {}

    /** Which arm of the settle ladder fired. */
    enum Settled {
        CANCELLED,
        GRAPH_ERRORS,
        FAILED,
        SUCCEEDED
    }

    /** The success wedge text for a finished workspace run. */
    @FunctionalInterface
    interface SuccessTail {
        String of(WorkspaceResult result, int planned);
    }

    /** The failure wedge text for a workspace run that ran and failed. */
    @FunctionalInterface
    interface FailureTail {
        String of(WorkspaceResult result);
    }

    /** The two per-verb strings the shared ladder cannot know. */
    record Tails(SuccessTail success, FailureTail failure) {}

    /**
     * One print mutex per process: parallel modules finish concurrently and each flushes a
     * multi-line block, so the block and its completion line must reach stdout together.
     */
    private static final Object OUT_LOCK = new Object();

    private final Chrome chrome;
    private final Path entryDir;
    private final @Nullable CliSessionTranscript session;
    private final boolean toStdout;

    private final Map<Path, List<String>> buffers = new ConcurrentHashMap<>();
    private final List<String> deferred = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger planned = new AtomicInteger();
    private final AtomicInteger served = new AtomicInteger();

    /**
     * @param session transcript to mirror module events into, or null when the verb keeps none
     * @param toStdout {@code --output json}: JSONL events go to stdout as well as the transcript
     */
    WorkspaceRunView(Chrome chrome, Path entryDir, @Nullable CliSessionTranscript session, boolean toStdout) {
        this.chrome = chrome;
        this.entryDir = entryDir;
        this.session = session;
        this.toStdout = toStdout;
    }

    /**
     * Modules the engine entered. Grows only: {@code onPlan} states it and the engine's progress
     * snapshots correct it upward when {@code -m} pulls in transitive prereqs the client never
     * counted. A caller with its own pre-count seeds it before the run.
     */
    void seedPlanned(int modules) {
        planned.accumulateAndGet(Math.max(0, modules), Math::max);
    }

    int planned() {
        return planned.get();
    }

    /** Modules whose test suite was served from the action cache — its green marker replayed instead of a run. */
    int servedFromCache() {
        return served.get();
    }

    /** Buffered module output in completion order, painted. Empty for non-buffered chrome. */
    List<String> deferredOutput() {
        synchronized (deferred) {
            // paintLines is idempotent for already-styled content (stack frames re-highlight safely).
            return new ArrayList<>(TestFailureHighlight.paintLines(deferred));
        }
    }

    /**
     * A line of the verb's own to print above the settle wedge, with the buffered module output —
     * for a step the client runs after the engine's half is done ({@code install}'s copy into the
     * user's home), so its report lands under the region and above the one wedge.
     */
    void defer(String line) {
        deferred.add(line);
    }

    /** Live aggregate listener over an open {@link JkManager} region. */
    WorkspaceBuildListener live(JkManager view, AggregateContext agg) {
        return live(view, agg, o -> {});
    }

    /**
     * As {@link #live(JkManager, AggregateContext)}, with {@code observer} called on every module
     * completion — where a verb keeps its own per-module tally ({@code image}'s pushed reference,
     * {@code native}'s built count).
     */
    WorkspaceBuildListener live(JkManager view, AggregateContext agg, Consumer<ModuleOutcome> observer) {
        return new WorkspaceBuildListener() {
            @Override
            public void onPreflight(String stage, int done, int totalUnits, String label) {
                // Labels only — aggregate % arrives via onWorkspaceProgress (engine tracker).
                agg.preflight(stage, done, totalUnits, label);
                // The label carries the dirty-module count; mirror it so journal and explain see
                // the same work size as the bar.
                mirror(JsonlShape.preflight(stage, done, totalUnits, label));
            }

            @Override
            public void onNote(String text) {
                defer(text);
                mirror(JsonlShape.note(text));
            }

            @Override
            public void onWorkspaceProgress(WorkspaceProgressTracker.Snapshot snap) {
                agg.applySnapshot(snap);
                seedPlanned(snap.modulesTotal());
                event(JsonlShape.workspaceProgress(
                        entryDir.toString(),
                        snap.numerator(),
                        snap.denominator(),
                        snap.phase(),
                        snap.modulesComplete(),
                        snap.modulesTotal()));
            }

            @Override
            public void onPlan(List<ModulePlan> plan) {
                // Engine calibrates the aggregate bar; the CLI only records plan size for the
                // completion lines' denominator.
                seedPlanned(plan.size());
                event(JsonlShape.workspaceStart(plan.size()));
            }

            @Override
            public void onEtaEstimate(long millis) {
                // Engine reports remaining work (post-lock dirty schedule — the same figure as
                // `jk explain`), so preflight/lock elapsed is not double-counted and the countdown
                // finishes near 0 when the estimate holds.
                view.setRemainingWorkEstimate(millis);
                // CliSessionTranscript.noteEta existed with no caller, so the seeded estimate was
                // never written down: the claim "the countdown is the same figure as jk explain"
                // was unfalsifiable from a finished run. It is the number this whole estimate path
                // exists to produce — it belongs in the transcript.
                if (session != null) session.noteEta(millis);
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                // Composed into the *returned* listener rather than attached to m.plan directly:
                // an engine-hosted module's plan is a client-side reconstruction that is never run,
                // and only the returned listener is driven by wire-replayed events.
                var lis = new AggregateModuleListener(agg, m.coord(), m.plan().steps());
                if (chrome.buffered()) lis.bufferOutputInto(buffer(m.dir()));
                event(JsonlShape.moduleStart(m.dir().toString(), m.coord()));
                return withMirror(counting(lis));
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                event(JsonlShape.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.millis()));
                observer.accept(o);
                String completion = completionLineFor(o);
                if (view.animating()) {
                    view.addCompletion(completion);
                    // Paint with this module's link context now; the deferredOutput() re-paint is a
                    // no-op on already-styled lines.
                    if (chrome.buffered()) deferred.addAll(paint(o.dir()));
                    return;
                }
                if (!chrome.buffered()) return; // this verb owns no output of its own
                StringBuilder block = new StringBuilder();
                for (String l : paint(o.dir())) block.append(l).append('\n');
                block.append(completion);
                view.writeAbove(block.toString());
            }
        };
    }

    /**
     * Append-only listener for {@code --output json} / {@code --verbose}: no region, one buffered
     * block plus a {@code ✓ [k of N]} line per module, printed under the shared mutex.
     */
    WorkspaceBuildListener headless() {
        return new WorkspaceBuildListener() {
            @Override
            public void onNote(String text) {
                if (toStdout) {
                    event(JsonlShape.note(text));
                    return;
                }
                // Headless blocks print as they finish; a run-level line prints where it happened.
                synchronized (OUT_LOCK) {
                    CliOutput.out(text);
                }
                mirror(JsonlShape.note(text));
            }

            @Override
            public void onWorkspaceProgress(WorkspaceProgressTracker.Snapshot snap) {
                // Engine tracker owns the aggregate rider; module listeners stay local.
                LiveProgress.get().apply(snap);
                event(JsonlShape.workspaceProgress(
                        entryDir.toString(),
                        snap.numerator(),
                        snap.denominator(),
                        snap.phase(),
                        snap.modulesComplete(),
                        snap.modulesTotal()));
            }

            @Override
            public void onPlan(List<ModulePlan> plan) {
                seedPlanned(plan.size());
                event(JsonlShape.workspaceStart(plan.size()));
            }

            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                event(JsonlShape.moduleStart(m.dir().toString(), m.coord()));
                if (toStdout) {
                    // Live step/progress events for agents (same shape as the single-module stream).
                    // Workspace member: no aggregate-rider writes (the engine snapshot owns it).
                    return counting(new JsonlListener(System.out, false));
                }
                List<String> buf = buffer(m.dir());
                return withMirror(counting(new BuildPlanListener() {
                    @Override
                    public synchronized void output(String step, String line) {
                        buf.add(line);
                    }

                    @Override
                    public synchronized void warn(String step, String code, String message) {
                        buf.add("  " + Glyphs.BANG + " " + step + ": " + message);
                    }

                    @Override
                    public synchronized void error(String step, String code, @Nullable String message) {
                        // test-failure renders as the styled output block, not an error line.
                        if ("test-failure".equals(code)) return;
                        buf.add("  " + Glyphs.CROSS + " " + step + ": " + message);
                    }
                }));
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                event(JsonlShape.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.millis()));
                if (toStdout) return;
                List<String> painted = paint(o.dir());
                String completion = completionLineFor(o);
                synchronized (OUT_LOCK) {
                    for (String line : painted) CliOutput.out(line);
                    CliOutput.out(completion);
                }
            }
        };
    }

    /**
     * The four-arm ladder every live workspace verb ends on, in the one order that is correct:
     * a cancel is not a failure, a graph/lock error never reached a module listener so it has no
     * module to blame, a module failure names one, and only what survives all three succeeded.
     *
     * <p>{@code after} runs once with the arm that fired — desktop notification, an exit-code
     * override, anything the verb owes its own surface. The return value is the process exit code.
     */
    int settleLive(
            JkManager view,
            AggregateContext agg,
            WorkspaceResult result,
            long elapsedMs,
            Tails tails,
            Consumer<Settled> after) {
        absorb(agg, result);
        List<String> above = deferredOutput();
        if (result.cancelled()) {
            view.finishBuildPlanCancelled(above);
            if (session != null) session.wedge(chrome.planName() + " job was cancelled");
            event(JsonlShape.workspaceFinish(false, elapsedMs, planned()));
            after.accept(Settled.CANCELLED);
            return 1;
        }
        if (!result.errors().isEmpty()) {
            List<String> errs = new ArrayList<>(above);
            for (String err : result.errors()) errs.add(ConsoleSpec.errorLine(errorStep(result), err));
            String tail = errorsTail(result);
            view.finishBuildPlanFailure(tail, errs);
            if (session != null) session.wedge(tail);
            event(JsonlShape.workspaceFinish(false, elapsedMs, planned()));
            after.accept(Settled.GRAPH_ERRORS);
            // 2 for graph errors, 6 for an unsatisfiable workspace lock (the engine's freshen guard).
            return result.exitCode();
        }
        if (!result.success()) {
            // Buffered sub-process output first, then the error diagnostics just above the failure
            // line — which stays last so the outcome is visible without scrolling.
            List<String> failAbove = new ArrayList<>(above);
            List<BuildPlanResult.Diagnostic> settleErrors = new ArrayList<>();
            for (BuildPlanResult.Diagnostic d : agg.unstreamedErrors()) {
                if ("test-failure".equals(d.code())) continue; // already printed by run-tests
                settleErrors.add(d);
            }
            ConsoleSpec.appendErrors(failAbove, settleErrors);
            // --continue: the wedge names a count, so the roll-call has to name the modules.
            List<String> failed = failedCoords(result);
            if (failed.size() > 1) {
                for (String coord : failed) failAbove.add(ConsoleSpec.errorLine("failed", coord));
            }
            String failTail = tails.failure().of(result);
            view.finishBuildPlanFailure(failTail, failAbove);
            if (session != null) session.wedge(failTail);
            event(JsonlShape.workspaceFinish(false, elapsedMs, planned()));
            after.accept(Settled.FAILED);
            return result.exitCode();
        }
        // An empty execute plan (planned == 0) is the engine finding nothing dirty; an explicit
        // empty module selection settles here too, as up to date.
        String okTail = tails.success().of(result, planned());
        view.finishBuildPlanSuccess(okTail, above);
        if (session != null) session.wedge(okTail);
        event(JsonlShape.workspaceFinish(true, elapsedMs, planned()));
        after.accept(Settled.SUCCEEDED);
        return 0;
    }

    /** Mirror the run's modules and errors into the transcript, once, before the ladder picks an arm. */
    void absorb(@Nullable AggregateContext agg, WorkspaceResult result) {
        if (session == null) return;
        for (var m : result.modules()) session.module(m.coord());
        for (String err : result.errors()) session.error(err);
        if (agg == null) return;
        for (BuildPlanResult.Diagnostic d : agg.lastErrors()) {
            session.error(d.step(), d.code(), d.message());
        }
    }

    /** Emit the terminal {@code workspace-finish} for a run that never reached the ladder. */
    void finishEvent(boolean success, long elapsedMs) {
        event(JsonlShape.workspaceFinish(success, elapsedMs, planned()));
    }

    /**
     * The step a run-level error is printed under: {@code composite} for a graph error, which
     * happens before any module exists, and {@code test} for a verdict over modules that finished
     * (the one such verdict is a {@code --class} selection that matched nothing anywhere).
     */
    static String errorStep(WorkspaceResult result) {
        return result.modules().isEmpty() ? "composite" : "test";
    }

    /** The failure wedge for a run that ended on run-level errors: the verdict itself when modules ran. */
    static String errorsTail(WorkspaceResult result) {
        return result.modules().isEmpty()
                ? "dependency resolution failed"
                : result.errors().get(0);
    }

    /** First failing module's coordinate, or {@code fallback} when the engine named none. */
    static String failedCoord(WorkspaceResult result, String fallback) {
        List<String> failed = failedCoords(result);
        return failed.isEmpty() ? fallback : failed.get(0);
    }

    /**
     * Every failing module's coordinate, in the order the engine finished them.
     *
     * <p>Under {@code --continue} the run keeps going, so "which module failed" has more than one
     * answer and reporting the first would hide the rest — which is the whole reason the flag
     * exists. Fail-fast runs have exactly one and read as before.
     */
    static List<String> failedCoords(WorkspaceResult result) {
        if (result.modules() == null) return List.of();
        return result.modules().stream()
                .filter(m -> !m.success())
                .map(ModuleOutcome::coord)
                .toList();
    }

    /** The failure wedge's subject: one coordinate, or {@code "N modules"} when several failed. */
    static String failedSubject(WorkspaceResult result, String fallback) {
        List<String> failed = failedCoords(result);
        if (failed.isEmpty()) return fallback;
        if (failed.size() == 1) return failed.get(0);
        return failed.size() + " modules";
    }

    private String completionLineFor(ModuleOutcome o) {
        int index = completed.incrementAndGet();
        return BuildTails.completionLine(o.success(), index, Math.max(planned(), index), o.coord(), o.millis());
    }

    private List<String> paint(Path moduleDir) {
        List<String> buf = buffers.getOrDefault(moduleDir, List.of());
        synchronized (buf) {
            if (buf.isEmpty()) return List.of();
            try (var link = DashboardCodeLink.open(entryDir, moduleDir)) {
                return TestFailureHighlight.paintLines(buf);
            }
        }
    }

    private List<String> buffer(Path moduleDir) {
        return buffers.computeIfAbsent(moduleDir, d -> Collections.synchronizedList(new ArrayList<>()));
    }

    private BuildPlanListener withMirror(BuildPlanListener lis) {
        return session == null ? lis : CompositeBuildPlanListener.of(lis, new SessionMirrorListener(session));
    }

    /** {@code lis} with this module's served-from-cache tally beside it; see {@link #servedFromCache()}. */
    private BuildPlanListener counting(BuildPlanListener lis) {
        return CompositeBuildPlanListener.of(lis, new ServedTally());
    }

    /**
     * One module's run-tests step, watched for a replay: the engine labels the step {@link
     * TaskNames#TESTS_UP_TO_DATE} when it serves the suite's green marker and then finishes it
     * SKIPPED. A SKIPPED suite under any other label — no tests, a {@code --class} that matched
     * nothing here — was not served anything.
     */
    private final class ServedTally implements BuildPlanListener {
        private volatile boolean replayed;

        @Override
        public void label(String step, String label) {
            if (TaskNames.RUN_TESTS.equals(step)) replayed = TaskNames.TESTS_UP_TO_DATE.equals(label);
        }

        @Override
        public void stepFinish(
                String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
            if (TaskNames.RUN_TESTS.equals(step) && status == TaskStatus.SKIPPED && replayed) {
                served.incrementAndGet();
            }
        }
    }

    /**
     * Write one event to the transcript whatever the output mode. {@link #event} only emits under
     * {@code --output json}; a fact worth keeping for a later reader has to go here or it exists
     * only for as long as the terminal shows it.
     */
    private void mirror(String line) {
        if (session != null) session.appendRaw(JsonlShape.withProgress(line, null), false);
    }

    /**
     * One {@code workspace-*} / {@code module-*} event. {@code toStdout} decides whether it also
     * reaches stdout; either way {@link JsonlShape#emitJsonl} appends it to whatever
     * {@link CliSessionTranscript} is active, so a verb that keeps no transcript of its own still
     * contributes to the session record.
     */
    private void event(String line) {
        JsonlShape.emitJsonl(line, toStdout);
    }
}
