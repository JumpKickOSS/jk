// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-task progress with one line per task, like Cargo / uv. Activated by {@code --verbose}.
 * Tasks scroll up as they complete; the active task shows its current label.
 */
public final class VerboseListener implements BuildPlanListener {

    private final PrintStream out;
    private final PrintStream err;
    private final ConcurrentMap<String, String> labels = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> outputBuf = new ConcurrentHashMap<>();

    /**
     * One monitor per step, held across the whole buffer transaction (flush-open-buffer, append,
     * footer flush). The class defends against two threads racing one step; copying a
     * synchronizedList outside its monitor and the header/flush check-then-act were exactly the
     * races that defence missed. Bounded by the plan's step count; the listener dies with the run.
     */
    private final ConcurrentMap<String, Object> stepMonitors = new ConcurrentHashMap<>();

    private Object monitorFor(String step) {
        return stepMonitors.computeIfAbsent(step, s -> new Object());
    }

    public VerboseListener(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void planStart(BuildPlanView view) {
        out.println(Theme.colorize("▶", Theme.active().activeStep())
                + " "
                + Theme.colorize(view.planName(), Theme.active().focused())
                + " ("
                + view.stepsTotal()
                + " task"
                + (view.stepsTotal() == 1 ? "" : "s")
                + ")");
    }

    /** Renders a task's place in the run hierarchy as {@code group/task} (a redundant {@code group-}
     * prefix on the task name is dropped, so group {@code compile} + task {@code compile-java} →
     * {@code compile/java}); the bare task name when it has no group. */
    static String qualified(String step, String group) {
        if (group == null) return step;
        String pw = group;
        String shortName = step.startsWith(pw + "-") ? step.substring(pw.length() + 1) : step;
        return pw + "/" + shortName;
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        out.println("  " + Theme.colorize("·", Theme.active().normalGray()) + " " + qualified(step, group) + " (ticks: "
                + ticks + ")");
    }

    @Override
    public void label(String step, String label) {
        labels.put(step, label);
    }

    /**
     * Non-failure output streams immediately — watching a hung test live is a primary use of
     * {@code --verbose}, so lines must not sit in a buffer until stepFinish. Only a
     * {@code Test Failure} block is held back, from its header sentinel to its footer, so the
     * report paints as one unit; the footer flushes it without waiting for the step.
     */
    @Override
    public void output(String step, String line) {
        synchronized (monitorFor(step)) {
            // A second header with the first block's footer never delivered (worker killed
            // mid-block) must flush the open buffer first, not append into it — otherwise both
            // blocks sit until stepFinish and paint as one malformed unit.
            if (outputBuf.get(step) != null && TestFailureHighlight.isHeader(line)) {
                flushOutput(step);
            }
            List<String> buf;
            if (TestFailureHighlight.isHeader(line)) {
                buf = outputBuf.computeIfAbsent(step, s -> new ArrayList<>());
            } else {
                buf = outputBuf.get(step);
            }
            if (buf != null) {
                buf.add(line);
                if (line != null && TestFailureHighlight.FOOTER_SENTINEL.equals(line.strip())) {
                    flushOutput(step);
                }
                return;
            }
            out.println(StackTraceHighlight.line(line));
        }
    }

    private void flushOutput(String step) {
        synchronized (monitorFor(step)) {
            List<String> buf = outputBuf.remove(step);
            if (buf == null || buf.isEmpty()) return;
            for (String painted : TestFailureHighlight.paintLines(List.copyOf(buf))) {
                out.println(painted);
            }
        }
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        flushOutput(step);
        String glyph =
                switch (status) {
                    case SUCCESS -> Theme.colorize(Glyphs.CHECK, Theme.active().completedStep());
                    case FAIL -> Theme.colorize(Glyphs.CROSS, Theme.active().error());
                    case CANCELLED -> Theme.colorize("·", Theme.active().normalGray());
                    default -> "·";
                };
        out.println("  "
                + glyph
                + " "
                + qualified(step, group)
                + "  "
                + Theme.colorize(
                        ConsoleSpec.fmtDuration(duration), Theme.active().darkGray()));
    }

    @Override
    public void warn(String step, String code, String message) {
        String location = (code != null && !code.isBlank()) ? " " + step + "/" + code : "";
        err.println("    " + Theme.colorize(Glyphs.BANG, Theme.active().warning()) + location + ": " + message);
    }

    @Override
    public void error(String step, String code, String message) {
        if ("verbatim".equals(code)) {
            err.println(message);
        } else {
            err.println("    " + Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + step + "/" + code + ": "
                    + message);
        }
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        for (String step : new ArrayList<>(outputBuf.keySet())) flushOutput(step);
        String summary = result.success()
                ? Theme.colorize(Glyphs.CHECK + " done", Theme.active().completedStep())
                : Theme.colorize(Glyphs.CROSS + " failed", Theme.active().error());
        out.println(summary + " (" + ConsoleSpec.fmtDuration(result.duration()) + ")");
    }
}
