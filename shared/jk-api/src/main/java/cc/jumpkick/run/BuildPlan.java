// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import cc.jumpkick.plugin.build.Phase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Named DAG of {@link Task}s for one invocation: readiness-level scheduling, progress, diagnostics,
 * and a terminal {@link BuildPlanResult}. Cancellation is cooperative at the step level: a flag is
 * set, futures are cancelled after a short grace ({@link #COOPERATIVE_CANCEL_GRACE}). OS-level
 * worker JVMs are shut down by the engine ({@code JobWorkers},soft then force within
 * ~500 ms — never hang.
 */
public final class BuildPlan {

    /** How long we wait for async steps to notice cancellation before calling {@code Future.cancel}. */
    static final Duration COOPERATIVE_CANCEL_GRACE = Duration.ofMillis(200);

    /** How often the interpolation interpTimer eases opaque steps forward. */
    private static final long INTERP_TICK_MS = 100;

    /**
     * Expected wall-clock per unit of step weight, the divisor for time-based interpolation. Weights
     * are tuned as rough time shares, so treating a weight unit as a fixed slice of time gives each
     * opaque step a plausible duration; the {@code INTERP_CAP} ceiling absorbs the inevitable
     * misestimate.
     */
    private static final long INTERP_NANOS_PER_WEIGHT = 150_000_000L; // ~150ms

    /** Running steps currently being eased forward by the interpolation interpTimer. */
    private final Set<DefaultTaskContext> easing = ConcurrentHashMap.newKeySet();

    private final String name;
    private final boolean interactive;
    private final List<Task> steps;
    private final List<BuildPlanListener> listeners;

    private final LongAdder numerator = new LongAdder();
    private final LongAdder denominator = new LongAdder();
    private final AtomicInteger stepsComplete = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** True only for host {@link #requestCancel} (vs internal cancel after a step failure). */
    private final AtomicBoolean userRequestedCancel = new AtomicBoolean(false);

    private final Map<String, TaskStatus> statuses = new ConcurrentHashMap<>();
    private final List<BuildPlanResult.Diagnostic> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<BuildPlanResult.Diagnostic> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<BuildPlanResult.StepReport> reports = Collections.synchronizedList(new ArrayList<>());

    /** Cross-step shared state — typed via {@link BuildPlanKey}. Reads happen via TaskContext. */
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    BuildPlan(String name, boolean interactive, List<Task> steps, List<BuildPlanListener> listeners) {
        this.name = Objects.requireNonNull(name);
        this.interactive = interactive;
        this.steps = List.copyOf(steps);
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        // Validate the DAG up front so misconfigurations fail loudly.
        validate(this.steps);
    }

    public String name() {
        return name;
    }

    public boolean interactive() {
        return interactive;
    }

    public List<Task> steps() {
        return steps;
    }

    public void addListener(BuildPlanListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Request cancellation; running steps see {@link TaskContext#cancelled} flip. */
    public void requestCancel() {
        userRequestedCancel.set(true);
        cancelled.set(true);
    }

    public BuildPlanView snapshot() {
        return new BuildPlanView(
                name, numerator.sum(), denominator.sum(), steps.size(), stepsComplete.get(), cancelled.get());
    }

    /** Sum of step weights without running; a throwing estimate contributes 0. */
    public int estimatedTotalWeight() {
        List<CompletableFuture<Integer>> futures = new ArrayList<>(steps.size());
        for (Task p : steps) {
            futures.add(CompletableFuture.supplyAsync(p::estimateWeight, JkThreads.io()));
        }
        int total = 0;
        for (CompletableFuture<Integer> f : futures) {
            try {
                total += f.get();
            } catch (Exception ignored) {
                // best-effort estimate; a failing step just contributes 0
            }
        }
        return total;
    }

    /**
     * Run the pipeline. Blocks until every step reaches a terminal state. Throws no checked exceptions
     * step failures are folded into {@link BuildPlanResult#success}.
     */
    public BuildPlanResult run() {
        Instant pipelineStart = Instant.now();

        // Step 1: ticks estimation (parallel on IO). `initialTicks` is each step's
        // internal unit count (how granularly it ticks); `weights` is its share of
        // the bar (time-proportional). The denominator sums weights, not units, so a
        // file-count-scoped compile can't dwarf a quick step. A step without an
        // explicit weight reuses its ticks, so the denominator is unchanged for it.
        List<CompletableFuture<Integer>> tickFutures = new ArrayList<>(steps.size());
        for (Task p : steps) {
            tickFutures.add(CompletableFuture.supplyAsync(p::estimateTicks, JkThreads.io()));
        }
        Map<String, Integer> initialTicks = new HashMap<>();
        Map<String, Integer> weights = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Task p = steps.get(i);
            int s = 0;
            try {
                s = tickFutures.get(i).get();
            } catch (Exception ignored) {
                // best-effort; a failing estimate contributes 0
            }
            initialTicks.put(p.name(), s);
            // Reuse the computed ticks when no weight was set — avoids re-walking
            // sources just to learn the weight equals the ticks.
            int w = p.hasExplicitWeight() ? p.estimateWeight() : s;
            weights.put(p.name(), w);
            denominator.add(w);
        }

        for (Task p : steps) {
            statuses.put(p.name(), TaskStatus.PENDING);
        }
        emit(l -> l.pipelineStart(snapshot()));

        // Interpolation interpTimer: while an opaque step runs, ease its bar slice
        // forward over elapsed time so the bar doesn't sit flat until the step's
        // single body call returns. Only started when some step opts in.
        ScheduledExecutorService interpTimer = startInterpolationTimer();

        // Step 2: run steps by readiness levels.
        Set<String> completedOk = new HashSet<>();
        List<Task> remaining = new ArrayList<>(topoSort(steps));
        try {
            while (!remaining.isEmpty() && !cancelled.get()) {
                List<Task> ready = remaining.stream()
                        .filter(p -> completedOk.containsAll(p.requires()))
                        .toList();
                if (ready.isEmpty()) {
                    // Either remaining steps all depend on a failed predecessor
                    // (mark them CANCELLED) or there's a programming bug. Bail.
                    break;
                }
                remaining.removeAll(ready);
                boolean levelOk = runLevel(ready, initialTicks, weights);
                for (Task p : ready) {
                    if (isOk(statuses.get(p.name()))) {
                        completedOk.add(p.name());
                    }
                }
                if (!levelOk) {
                    cancelled.set(true);
                    break;
                }
            }
        } finally {
            if (interpTimer != null) interpTimer.shutdownNow();
        }

        // Any remaining (un-run) steps are CANCELLED because a dep failed.
        for (Task p : remaining) {
            statuses.put(p.name(), TaskStatus.CANCELLED);
            reports.add(new BuildPlanResult.StepReport(p.name(), TaskStatus.CANCELLED, Duration.ZERO, p.requires()));
        }

        // Session cancel (Ctrl-C / BUILD_CANCEL) may never call requestCancel — fold it in so the
        // result's userCancelled flag reaches the journal/metrics path.
        if (SessionCancel.cancelled()) {
            userRequestedCancel.set(true);
            cancelled.set(true);
        }
        boolean success = !cancelled.get()
                && steps.stream().map(p -> statuses.get(p.name())).allMatch(BuildPlan::isOk);

        // Sort reports back into declaration order so the printed summary
        // matches the user's mental model of the build pipeline.
        Map<String, Integer> declOrder = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) declOrder.put(steps.get(i).name(), i);
        List<BuildPlanResult.StepReport> orderedReports = new ArrayList<>(reports);
        orderedReports.sort(Comparator.comparingInt(r -> declOrder.getOrDefault(r.name(), Integer.MAX_VALUE)));

        BuildPlanResult result = new BuildPlanResult(
                name,
                success,
                Duration.between(pipelineStart, Instant.now()),
                orderedReports,
                warnings,
                errors,
                cancelled.get(),
                userRequestedCancel.get());
        emit(l -> l.pipelineFinish(result));
        return result;
    }

    // --- Scheduling ----------------------------------------------------

    /**
     * Start the daemon interpTimer that eases interpolated steps forward over time, or return {@code
     * null} when no step opts into interpolation (so the common case spins up no thread). The caller
     * shuts it down when the run finishes.
     */
    private ScheduledExecutorService startInterpolationTimer() {
        if (steps.stream().noneMatch(Task::interpolated)) return null;
        ScheduledExecutorService interpTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-progress-interp");
            t.setDaemon(true);
            return t;
        });
        interpTimer.scheduleAtFixedRate(
                () -> {
                    long now = System.nanoTime();
                    for (DefaultTaskContext c : easing) {
                        try {
                            c.tick(now);
                        } catch (RuntimeException ignored) {
                            /* never break the interpTimer */
                        }
                    }
                },
                INTERP_TICK_MS,
                INTERP_TICK_MS,
                TimeUnit.MILLISECONDS);
        return interpTimer;
    }

    /**
     * Dispatch one readiness level. SYNC steps run inline (sequentially); IO/CPU steps run in
     * parallel on their respective pools. Returns true when every step in the level succeeded; false
     * on the first fail (triggers cooperative cancellation).
     */
    private boolean runLevel(List<Task> ready, Map<String, Integer> initialTicks, Map<String, Integer> weights) {
        List<CompletableFuture<TaskStatus>> futures = new ArrayList<>();
        for (Task p : ready) {
            int ticks = initialTicks.getOrDefault(p.name(), 0);
            int weight = weights.getOrDefault(p.name(), ticks);
            statuses.put(p.name(), TaskStatus.RUNNING);
            String stepName = p.name();
            Phase stepPhase = p.phase().orElse(null);
            emit(l -> l.stepStart(stepName, stepPhase, ticks));
            Executor exec = executorFor(p.kind());
            if (p.kind() == TaskKind.SYNC) {
                futures.add(CompletableFuture.completedFuture(runOneStep(p, ticks, weight)));
            } else {
                futures.add(CompletableFuture.supplyAsync(() -> runOneStep(p, ticks, weight), exec));
            }
        }

        boolean ok = true;
        for (CompletableFuture<TaskStatus> f : futures) {
            try {
                TaskStatus s = f.get();
                if (!isOk(s)) ok = false;
            } catch (Exception e) {
                ok = false;
            }
        }

        if (!ok && !cancelled.get()) {
            cancelled.set(true);
            // Give cooperative shutdown a window before we interrupt.
            try {
                Thread.sleep(COOPERATIVE_CANCEL_GRACE.toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            for (CompletableFuture<TaskStatus> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
        }
        return ok;
    }

    /**
     * A step status that counts as "the step is done and the build may proceed": a real success or
     * a cache-hit/up-to-date {@link TaskStatus#SKIPPED}. Used for dependency gating and overall
     * success so a fully-cached build (every step SKIPPED) is still a success.
     */
    private static boolean isOk(TaskStatus s) {
        return s == TaskStatus.SUCCESS || s == TaskStatus.SKIPPED;
    }

    private TaskStatus runOneStep(Task step, int initialTicks, int weight) {
        Instant start = Instant.now();
        long startNum = numerator.sum();
        long startNanos = System.nanoTime();
        long expectedNanos = step.interpolated() ? (long) weight * INTERP_NANOS_PER_WEIGHT : 0;
        DefaultTaskContext ctx = new DefaultTaskContext(
                step.name(), this, initialTicks, weight, step.hasExplicitWeight(), expectedNanos, startNanos);
        boolean ticked = ctx.interpolating();
        if (ticked) easing.add(ctx);
        try {
            step.execute(ctx);
            // Stop interpolating before auto-fill so no late tick races the top-up.
            if (ticked) easing.remove(ctx);
            // Auto-fill: if the step reported less progress than its bar budget
            // promised, top up the difference on a clean success so the bar
            // visually settles. Failures don't auto-fill — the bar stays
            // where the work actually stopped.
            long stepProgress = numerator.sum() - startNum;
            long stepBudget = ctx.stepBudget();
            if (stepProgress < stepBudget) {
                int gap = (int) Math.min(Integer.MAX_VALUE, stepBudget - stepProgress);
                numerator.add(gap);
                ctx.notifyProgress(gap);
            }
            // A step that reported no real work (outputs up-to-date / served from cache via
            // ctx.cached) terminates SKIPPED, not SUCCESS. SKIPPED counts as "ok" everywhere the
            // pipeline decides success (see isOk), so it never fails a build — it only feeds the
            // dashboard's per-project cache-hit ("steps skipped") ratio.
            TaskStatus terminal = ctx.wasCached() ? TaskStatus.SKIPPED : TaskStatus.SUCCESS;
            statuses.put(step.name(), terminal);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new BuildPlanResult.StepReport(step.name(), terminal, dur, step.requires()));
            stepsComplete.incrementAndGet();
            emit(l -> l.stepFinish(step.name(), step.phase().orElse(null), terminal, dur));
            return terminal;
        } catch (Throwable t) {
            if (ticked) easing.remove(ctx);
            // If the step body already recorded a specific error via
            // ctx.error(...) and then threw to signal failure, don't
            // pile on a duplicate "exception" diagnostic — the step
            // told us exactly what went wrong. We only synthesise a
            // generic diagnostic when nothing else was reported.
            // BuildPlan flag (sibling fail / requestCancel) OR session-level Ctrl-C (SessionCancel).
            // Session cancel alone must still terminal-CANCELLED and set userCancelled on the result,
            // otherwise force-killed builds journal as failed/success and poison ETA history.
            boolean cancel = cancelled.get() || SessionCancel.cancelled();
            if (SessionCancel.cancelled()) userRequestedCancel.set(true);
            boolean stepAlreadyReported =
                    !cancel && errors.stream().anyMatch(d -> step.name().equals(d.step()));
            if (!stepAlreadyReported) {
                errors.add(new BuildPlanResult.Diagnostic(
                        step.name(), cancel ? "cancelled" : "exception", diagnosticMessage(t)));
            }
            TaskStatus terminal = cancel ? TaskStatus.CANCELLED : TaskStatus.FAIL;
            statuses.put(step.name(), terminal);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new BuildPlanResult.StepReport(step.name(), terminal, dur, step.requires()));
            stepsComplete.incrementAndGet();
            emit(l -> l.stepFinish(step.name(), step.phase().orElse(null), terminal, dur));
            return terminal;
        }
    }

    /**
     * Human diagnostic for an unexpected step throwable. Bare messages like {@code closed} (pipe /
     * stream closed mid-worker) are nearly useless alone — append the exception class.
     */
    static String diagnosticMessage(Throwable t) {
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) return type;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("closed") || lower.equals("stream closed") || lower.equals("broken pipe")) {
            return msg + " (" + type + ")";
        }
        return msg;
    }

    private static Executor executorFor(TaskKind kind) {
        return switch (kind) {
            case IO -> JkThreads.io();
            case CPU -> JkThreads.cpu();
            case SYNC -> Runnable::run; // not used; SYNC runs inline
        };
    }

    // --- Fanout helpers ------------------------------------------------

    void emit(java.util.function.Consumer<BuildPlanListener> action) {
        for (BuildPlanListener l : listeners) {
            try {
                action.accept(l);
            } catch (RuntimeException ignored) {
                // Listeners must not impact the pipeline's success/fail decision.
            }
        }
    }

    LongAdder numeratorRef() {
        return numerator;
    }

    LongAdder denominatorRef() {
        return denominator;
    }

    AtomicInteger stepsCompleteRef() {
        return stepsComplete;
    }

    AtomicBoolean cancelledRef() {
        return cancelled;
    }

    List<BuildPlanResult.Diagnostic> warningsRef() {
        return warnings;
    }

    List<BuildPlanResult.Diagnostic> errorsRef() {
        return errors;
    }

    ConcurrentHashMap<String, Object> stateRef() {
        return state;
    }

    /**
     * Read a step-stashed value after {@link #run} has returned. Command bodies use this to surface
     * state steps produced — resolved lockfile, JDK outcome, etc. — into their summary output
     * without needing a separate holder object.
     */
    public <T> java.util.Optional<T> get(BuildPlanKey<T> key) {
        Object raw = state.get(key.name());
        if (raw == null) return java.util.Optional.empty();
        if (!key.type().isInstance(raw)) {
            throw new ClassCastException("pipeline state '"
                    + key.name()
                    + "' is "
                    + raw.getClass().getName()
                    + " not "
                    + key.type().getName());
        }
        return java.util.Optional.of(key.type().cast(raw));
    }

    // --- DAG validation + topo sort -----------------------------------

    private static void validate(List<Task> steps) {
        Set<String> known = new HashSet<>();
        for (Task p : steps) {
            if (!known.add(p.name())) {
                throw new IllegalArgumentException("duplicate step name: " + p.name());
            }
        }
        for (Task p : steps) {
            for (String req : p.requires()) {
                if (!known.contains(req)) {
                    throw new IllegalArgumentException("step '" + p.name() + "' requires unknown '" + req + "'");
                }
            }
        }
        // Cheap cycle detection via topo sort attempt.
        topoSort(steps);
    }

    private static List<Task> topoSort(List<Task> steps) {
        Map<String, Task> byName = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> reverse = new HashMap<>();
        for (Task p : steps) {
            byName.put(p.name(), p);
            inDegree.put(p.name(), 0);
        }
        for (Task p : steps) {
            for (String r : p.requires()) {
                inDegree.merge(p.name(), 1, Integer::sum);
                reverse.computeIfAbsent(r, k -> new ArrayList<>()).add(p.name());
            }
        }
        List<Task> out = new ArrayList<>();
        List<String> ready = new ArrayList<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        while (!ready.isEmpty()) {
            String n = ready.remove(0);
            out.add(byName.get(n));
            for (String down : reverse.getOrDefault(n, List.of())) {
                int newDeg = inDegree.merge(down, -1, Integer::sum);
                if (newDeg == 0) ready.add(down);
            }
        }
        if (out.size() != steps.size()) {
            throw new IllegalArgumentException("step DAG has a cycle");
        }
        return out;
    }

    // --- Builder -------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private boolean interactive = false;
        private final List<Task> steps = new ArrayList<>();
        private final List<BuildPlanListener> listeners = new ArrayList<>();

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /**
         * Interactive pipelines (wizards, prompts) suppress automatic progress visualization — the
         * foreground UI owns the terminal. Listeners still get every event; only the default
         * progress-bar consumer respects this flag.
         */
        public Builder interactive(boolean v) {
            this.interactive = v;
            return this;
        }

        public Builder addTask(Task step) {
            steps.add(step);
            return this;
        }

        /**
         * Append every step from {@code more} whose name is not already present — ordered-set
         * semantics. Use this to compose a pipeline from multiple step sequences without duplicate steps.
         */
        public Builder addAllTasks(java.util.Collection<Task> more) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Task p : steps) existing.add(p.name());
            for (Task p : more) {
                if (existing.add(p.name())) steps.add(p);
            }
            return this;
        }

        public Builder addListener(BuildPlanListener listener) {
            listeners.add(listener);
            return this;
        }

        public BuildPlan build() {
            return new BuildPlan(name, interactive, steps, listeners);
        }
    }
}
