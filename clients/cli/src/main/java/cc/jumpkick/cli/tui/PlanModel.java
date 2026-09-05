// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The step and phase state of one plan, and how wire events change it. No terminal, no ANSI, no
 * threads: the views read it under the manager's monitor, which this class receives and takes
 * exactly where the manager did, and declares no monitor of its own. Three rules are
 * load-bearing: a row with a terminal state is never resurrected by a late {@code stepStart}; a
 * phase whose running count reaches zero without a failure leaves the chain, while a failed phase
 * stays with a brief error defaulting to {@code Failed}; and a brief error is cut at 96 characters
 * without ever splitting a surrogate pair.
 */
final class PlanModel {

    /** What the region does when the model changes; called inside the model's critical section. */
    interface Events {
        /** A step started running: the header target follows the module and the R0 seed locks. */
        void stepStarted(String module);

        void stepMessage(Row row);

        void testTick(Row row, int delta);

        void moduleBuilt(String module);

        /** The preflight pill's header label while the bar has no denominator. */
        void preflight(String pill, int done, int total, String label);
    }

    private final Object lock;
    private final Events events;

    private final Map<String, Row> rows = new LinkedHashMap<>();

    /** Coarse plan phases in first-seen order (render newest-first). Key = wire phase name. */
    private final List<String> phaseOrder = new ArrayList<>();

    private final Map<String, PhaseNode> phases = new LinkedHashMap<>();

    /** Pre-formatted completion lines, oldest→newest; bounded to {@link JkManager#MAX_COMPLETIONS}. */
    private final List<String> recentCompletions = new ArrayList<>();

    private int completedCount;
    private long finishSeq;

    PlanModel(Object lock, Events events) {
        this.lock = lock;
        this.events = events;
    }

    /** Live view for the painters; read under the lock. */
    Map<String, Row> rows() {
        return rows;
    }

    List<String> phaseOrder() {
        return phaseOrder;
    }

    Map<String, PhaseNode> phases() {
        return phases;
    }

    List<String> recentCompletions() {
        return recentCompletions;
    }

    int completedCount() {
        return completedCount;
    }

    /** Register a not-yet-started step row with a humanized display name. */
    public void addTask(String module, String stepKey) {
        addTaskLabeled(module, stepKey, humanize(stepKey));
    }

    /** Register a not-yet-started step row with an explicit display label. */
    public void addTaskLabeled(String module, String stepKey, String display) {
        synchronized (lock) {
            rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, display, stepKey));
        }
    }

    /** Mark a step running (phase defaults to the step key). */
    public void stepRunning(String module, String stepKey) {
        stepRunning(module, stepKey, "");
    }

    /**
     * Mark a step running and record its coarse {@code phase} (wire name, e.g. {@code compile}) for
     * the vertical phase chain. Empty phase falls back to the step key (same as the web dashboard).
     *
     * <p>No-ops when the step already has a terminal status so a late/out-of-order {@code stepStart}
     * cannot resurrect a finished row (same rule as the dashboard rehydrate path).
     */
    public void stepRunning(String module, String stepKey, String phase) {
        synchronized (lock) {
            String phaseKey = phaseKey(phase, stepKey);
            Row r = rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, humanize(stepKey), phaseKey));
            if (r.state == RowState.DONE || r.state == RowState.FAILED) return;
            r.phase = phaseKey;
            r.state = RowState.ACTIVE;
            touchPhaseStart(phaseKey);
            // First module task starting = execute has begun: freeze the R0 seed path so
            // provisional eta rewrites cannot thrash the total. Residual still
            // re-anchors the painted countdown.
            events.stepStarted(module);
        }
    }

    /** Set the current sub-task message for a running step. */
    public void stepMessage(@Nullable String module, String stepKey, String message) {
        synchronized (lock) {
            Row r = rows.get(key(module, stepKey));
            if (r == null) return;
            r.message = message == null ? "" : message;
            events.stepMessage(r);
        }
    }

    /**
     * A static test finished. Decrements the plain remaining-test count without printing — the next
     * stage-change or 30s heartbeat line shows the updated {@code running N tests}.
     */
    public void notePlainTestTick(String module, String stepKey, int delta) {
        synchronized (lock) {
            Row r = rows.get(key(module, stepKey));
            if (r != null) events.testTick(r, delta);
        }
    }

    /** Mark a step finished (phase defaults empty). */
    public void stepDone(@Nullable String module, String stepKey, boolean ok) {
        stepDone(module, stepKey, ok, "");
    }

    /** Mark a step finished; updates the phase chain aggregate for {@code phase}. */
    public void stepDone(@Nullable String module, String stepKey, boolean ok, String phase) {
        synchronized (lock) {
            String phaseKey = phaseKey(phase, stepKey);
            Row r = rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, humanize(stepKey), phaseKey));
            if (r.state == RowState.DONE || r.state == RowState.FAILED) return;
            r.phase = phaseKey;
            r.state = ok ? RowState.DONE : RowState.FAILED;
            r.message = "";
            r.seq = ++finishSeq;
            touchPhaseFinish(phaseKey, ok);
        }
    }

    /**
     * Terminal cleanup for one module's plan: any still-{@link RowState#ACTIVE} rows (lost wire
     * {@code task-finish}, cancel between start and finish, …) settle so they leave the live tree
     * instead of lingering for the rest of the workspace build.
     */
    public void finishModule(String module, boolean ok) {
        synchronized (lock) {
            if (module == null) return;
            for (Row r : rows.values()) {
                if (!module.equals(r.module) || r.state != RowState.ACTIVE) continue;
                r.state = ok ? RowState.DONE : RowState.FAILED;
                r.message = "";
                r.seq = ++finishSeq;
                if (r.phase != null && !r.phase.isEmpty()) {
                    touchPhaseFinish(r.phase, ok);
                }
            }
            if (ok) events.moduleBuilt(module);
        }
    }

    /**
     * Workspace preflight: phase pill for {@code stage} + optional header label while the bar may
     * still be preflight-only. Called from {@link cc.jumpkick.cli.run.AggregateContext#preflight}.
     */
    public void preflight(String stage, int done, int total, String label) {
        synchronized (lock) {
            String key = stage == null || stage.isEmpty() ? "checking" : stage;
            String pill = phaseLabel(key);
            PhaseNode n = phases.get(key);
            if (n == null) {
                n = new PhaseNode(key, pill);
                phases.put(key, n);
                phaseOrder.add(key);
            }
            // Prefer an explicit label; else "3/10" style progress on the tree row detail.
            if (label != null && !label.isEmpty()) n.detail = label;
            else if (total > 0) n.detail = done + "/" + total;
            boolean complete = total > 0 && done >= total;
            if (complete) {
                // Success → drop from the live chain (failed preflight keeps a red row).
                phases.remove(key);
                phaseOrder.remove(key);
            } else {
                n.state = PhaseState.RUNNING;
            }
            events.preflight(pill, done, total, label);
        }
    }

    /**
     * Attach a short failure summary to the step/phase owning {@code stepKey} (or {@code phase} if
     * set). Full diagnostics still go above the region / result files.
     */
    public void attachPhaseError(String module, String stepKey, String phase, String brief) {
        synchronized (lock) {
            String msg = brief == null ? "" : brief.trim().replace('\n', ' ');
            if (msg.length() > 96) {
                int cut = 93;
                // Never split a surrogate pair: an emoji-heavy assertion brief cut mid-pair
                // ends in a lone high surrogate (mojibake in the tree's brief-error row, and
                // PlainAscii passes lone surrogates through).
                if (Character.isHighSurrogate(msg.charAt(cut - 1))) cut--;
                msg = msg.substring(0, cut) + Glyphs.ELLIPSIS;
            }
            Row r = rows.get(key(module, stepKey));
            if (r != null && !msg.isEmpty()) r.briefError = msg;
            // Prefer the row's recorded wire phase (e.g. "compile") when callers pass empty
            // phase or a step key that has no PhaseNode.
            String pk = phaseKey(phase, stepKey);
            if (r != null && r.phase != null && !r.phase.isEmpty()) {
                if (pk == null || pk.isEmpty() || !phases.containsKey(pk)) {
                    pk = r.phase;
                }
            }
            if (pk == null || pk.isEmpty()) return;
            PhaseNode n = phases.get(pk);
            if (n == null) return;
            if (!msg.isEmpty()) n.briefError = msg;
            n.anyFailed = true;
            n.state = PhaseState.FAILED;
        }
    }

    /**
     * Record a finished unit's pre-formatted completion line in the live tail under the wedge
     * (newest first, capped to {@link #MAX_COMPLETIONS}; the rest collapse into a
     * {@code … plus N more …} footer). Does not write to the terminal or to process-output
     * scrollback — the next plan paint includes it in the live region. Callers that aren't
     * animating should print append-only instead (see {@link JkManager#animating}).
     */
    public void addCompletion(String line) {
        synchronized (lock) {
            completedCount++;
            recentCompletions.add(line);
            if (recentCompletions.size() > JkManager.MAX_COMPLETIONS) recentCompletions.remove(0);
        }
    }

    private static String phaseKey(String phase, String stepKey) {
        if (phase != null && !phase.isEmpty()) return phase;
        return stepKey == null ? "" : stepKey;
    }

    private void touchPhaseStart(String phaseKey) {
        if (phaseKey == null || phaseKey.isEmpty()) return;
        PhaseNode n = phases.get(phaseKey);
        if (n == null) {
            n = new PhaseNode(phaseKey, phaseLabel(phaseKey));
            phases.put(phaseKey, n);
            phaseOrder.add(phaseKey);
        }
        n.runningCount++;
        n.state = PhaseState.RUNNING;
    }

    private void touchPhaseFinish(String phaseKey, boolean ok) {
        if (phaseKey == null || phaseKey.isEmpty()) return;
        PhaseNode n = phases.get(phaseKey);
        if (n == null) {
            n = new PhaseNode(phaseKey, phaseLabel(phaseKey));
            phases.put(phaseKey, n);
            phaseOrder.add(phaseKey);
        }
        n.runningCount = Math.max(0, n.runningCount - 1);
        if (!ok) {
            n.anyFailed = true;
            if (n.briefError == null || n.briefError.isEmpty()) n.briefError = "Failed";
        }
        if (n.runningCount == 0) {
            if (n.anyFailed) {
                n.state = PhaseState.FAILED;
            } else {
                // Success → remove from the live chain (web-like: only active/failed stay visible)
                phases.remove(phaseKey);
                phaseOrder.remove(phaseKey);
            }
        } else {
            n.state = n.anyFailed ? PhaseState.FAILED : PhaseState.RUNNING;
        }
    }

    private static String key(String module, String stepKey) {
        return module + '\0' + stepKey;
    }

    /** {@code compile} → {@code Compile}. */
    static String phaseLabel(String wire) {
        return JkManagerColor.phaseLabel(wire);
    }

    /** {@code compile-java} → {@code Compile java} for a step row's display label. */
    static String humanize(String stepKey) {
        return JkManagerColor.humanize(stepKey);
    }

    enum RowState {
        PENDING,
        ACTIVE,
        DONE,
        FAILED
    }

    enum PhaseState {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    static final class PhaseNode {
        final String key;
        final String label;
        PhaseState state = PhaseState.PENDING;
        int runningCount;
        boolean anyFailed;
        /** One-line summary under a failed phase (full diagnostics stay in result files). */
        String briefError = "";
        /** Live sub-task for preflight / phase-only rows (e.g. artifact being fetched). */
        String detail = "";

        PhaseNode(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static final class Row {
        final String module;
        final String step;
        String phase;
        RowState state = RowState.PENDING;
        String message = "";
        /** One-line failure summary under a failed row (full diagnostics stay above / in files). */
        String briefError = "";
        /** Plain status override ({@code compiling 12 sources} / {@code running 80 tests}). */
        String plainStatusOverride = "";
        /** Remaining static tests for plain countdown; {@code -1} = unknown. */
        int plainRemainingTests = -1;

        long seq;

        Row(String module, String step, String phase) {
            this.module = module;
            this.step = step;
            this.phase = phase == null ? "" : phase;
        }
    }

    /** One compact tree line (+ optional brief under failed work). */
    static final class TreeEntry {
        final String line;
        final String briefError;

        TreeEntry(String line, String briefError) {
            this.line = line;
            this.briefError = briefError == null ? "" : briefError;
        }
    }
}
