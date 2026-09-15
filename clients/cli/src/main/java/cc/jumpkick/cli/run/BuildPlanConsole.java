// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Single entry point CLI commands use to run a {@link BuildPlan} against the right set of console
 * listeners — picks {@link CommandManagerListener} (default TTY), {@link VerboseListener} ({@code
 * --verbose}), {@link JsonlListener} ({@code --output json}), or {@link SilentListener} (non-TTY,
 * {@code --quiet}, or interactive plan).
 *
 * <p>Ctrl-C during a plan is handled by the app-level {@link cc.jumpkick.cli.tui.GlobalCancel}
 * handler (installed at startup): it repaints the in-flight progress bar as canceled, prints {@code
 * ‼ Canceled by user}, and halts. There is no cooperative unwind — a hard cancel is immediate and
 * predictable.
 */
public final class BuildPlanConsole {

    /** Output mode requested by the user. */
    public enum Mode {
        /** Default: progress bar on a TTY, silent on pipes. */
        AUTO,
        /** Per-step lines (today's {@code --verbose}). */
        VERBOSE,
        /** Silent (today's {@code --quiet}, or interactive plans). */
        QUIET,
        /** Live JSONL to stdout ({@code --output json} or {@code jsonl}; identical). */
        JSON
    }

    private BuildPlanConsole() {}

    /**
     * Pick listeners + run the plan. Ctrl-C is handled by the app-level {@link
     * cc.jumpkick.cli.tui.GlobalCancel} handler. Returns the plan's {@link BuildPlanResult}; caller
     * decides what exit code to surface based on {@code result.success}.
     */
    public static BuildPlanResult run(BuildPlan plan, Mode mode, Path cacheRoot) {
        attachSessionMirror(plan, mode);

        BuildPlanListener console = chooseConsoleListener(plan, mode);
        if (console != null) plan.addListener(console);

        return plan.run();
    }

    /**
     * Variant that derives the cache root from {@link JkDirs#cache}. Use when the command doesn't
     * have an explicit override.
     */
    public static BuildPlanResult run(BuildPlan plan, Mode mode) {
        return run(plan, mode, JkDirs.cache());
    }

    /**
     * Simple-task variant: render the plan as a spinner + command (on a TTY) and a {@code ✓}/{@code ✗}
     * result line from {@code spec}, instead of the step-by-step progress bar. {@code --output
     * json} still emits JSONL and {@code --verbose} still prints per-step lines; otherwise the
     * {@link SimpleTaskListener} owns the output (animating only on a TTY).
     */
    public static BuildPlanResult run(BuildPlan plan, Mode mode, Path cacheRoot, ConsoleSpec spec) {
        attachSessionMirror(plan, mode);

        BuildPlanListener console =
                switch (mode) {
                    case JSON -> new JsonlListener(System.out);
                    case VERBOSE -> new VerboseListener(System.out, System.err);
                    // AUTO animates only on an interactive TTY; QUIET / pipes print the
                    // result line without a spinner.
                    case AUTO -> new SimpleTaskListener(System.out, System.err, spec, isInteractiveTerminal());
                    case QUIET -> new SimpleTaskListener(System.out, System.err, spec, false);
                };
        plan.addListener(console);
        return plan.run();
    }

    /**
     * Translate {@code GlobalOptions} flags into a {@link Mode}. Lives here so every command picks
     * the same precedence: {@code --output json} &gt; {@code --quiet} &gt; {@code --no-progress} &gt;
     * {@code --verbose} &gt; default.
     *
     * <p>{@code --output json} wins over the visualization flags because it's an explicit "I want
     * machine-readable output" — the user's other preferences don't override that.
     */
    public static Mode modeFor(@Nullable GlobalOptions opts) {
        if (opts == null) return Mode.AUTO;
        if (opts.outputIsJson()) return Mode.JSON;
        if (opts.quiet) return Mode.QUIET;
        if (opts.noProgress) return Mode.QUIET;
        if (opts.verbose) return Mode.VERBOSE;
        return Mode.AUTO;
    }

    /**
     * BuildPlan-oriented variant: render the plan with the new {@link CommandManagerListener} (spinner
     * header + aggregate bar + dynamic step list) attributed to {@code module} (the project's {@code
     * group:artifact}), then a {@code ✓}/{@code ✗} result line from {@code spec}. {@code --output
     * json} still emits JSONL and {@code --verbose} still prints per-step lines.
     */
    public static BuildPlanResult runBuildPlan(
            BuildPlan plan, Mode mode, Path cacheRoot, ConsoleSpec spec, String module) {
        attachSessionMirror(plan, mode);

        plan.addListener(chooseConsoleListener(plan.steps(), mode, spec, module));
        return plan.run();
    }

    /**
     * The listener {@link #runBuildPlan} picks per {@code mode} — split out so a caller that doesn't have
     * a real {@code BuildPlan} yet (a engine-hosted test run reconstructing the step list from wire
     * events; see {@code EngineEventDecoder}) can choose the same listener from just {@code
     * steps} once it knows them, instead of duplicating this switch.
     */
    public static BuildPlanListener chooseConsoleListener(
            List<Task> steps, Mode mode, ConsoleSpec spec, String module) {
        return switch (mode) {
            case JSON -> new JsonlListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> new CommandManagerListener(System.out, spec, module, steps, isInteractiveTerminal());
            case QUIET -> new CommandManagerListener(System.out, spec, module, steps, false);
        };
    }

    /**
     * As {@link #chooseConsoleListener(List, Mode, ConsoleSpec, String)}, for one member of a
     * multi-module workspace run: listeners never stamp their plan-local fraction into {@link
     * LiveProgress} — the aggregate {@code progress} rider belongs to the engine's {@code
     * workspace-progress} snapshots alone.
     */
    public static BuildPlanListener chooseWorkspaceMemberListener(
            List<Task> steps, Mode mode, ConsoleSpec spec, String module) {
        return switch (mode) {
            case JSON -> new JsonlListener(System.out, false);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> new CommandManagerListener(System.out, spec, module, steps, isInteractiveTerminal(), false);
            case QUIET -> new CommandManagerListener(System.out, spec, module, steps, false, false);
        };
    }

    /**
     * Run {@code plan} with no console output (only the event log), returning its result. For builds
     * whose progress must NOT render to the terminal — e.g. composite dependency units built
     * concurrently, where N live progress bars can't share one terminal region; the caller prints a
     * compact summary line per unit instead.
     */
    public static BuildPlanResult runBuildPlanSilently(BuildPlan plan, Path cacheRoot) {
        // Silent still mirrors to details.jsonl when a session is open (TTY chrome suppressed).
        attachSessionMirror(plan, Mode.QUIET);
        plan.addListener(new SilentListener(System.out, System.err));
        return plan.run();
    }

    /** A buffered run's result paired with its captured output/diagnostic lines. */
    public record Buffered(BuildPlanResult result, List<String> output) {}

    /**
     * Run {@code plan} capturing its output + warnings + errors into a buffer instead of rendering
     * live — for concurrently-built units, where the caller flushes each unit's buffer as one
     * contiguous block on completion (no interleaving across parallel builds). Only the event log
     * renders eagerly.
     */
    public static Buffered runBuildPlanBuffered(BuildPlan plan, Path cacheRoot) {
        attachSessionMirror(plan, Mode.QUIET);
        List<String> lines = new ArrayList<>();
        plan.addListener(new BuildPlanListener() {
            @Override
            public synchronized void output(String step, String line) {
                lines.add(line);
            }

            @Override
            public synchronized void warn(String step, String code, String message) {
                lines.add("  " + Glyphs.BANG + " " + step + ": " + message);
            }

            @Override
            public synchronized void error(String step, String code, String message) {
                if ("test-failure".equals(code)) return;
                lines.add("  " + Glyphs.CROSS + " " + step + ": " + message);
            }
        });
        BuildPlanResult r = plan.run();
        synchronized (lines) { // visibility barrier after the plan's threads finish
            return new Buffered(r, new ArrayList<>(TestFailureHighlight.paintLines(lines)));
        }
    }

    private static BuildPlanListener chooseConsoleListener(BuildPlan plan, Mode mode) {
        // Interactive plans (wizards, download bars) must NOT render a progress bar: the plan's
        // own chrome owns the terminal. --output json still gets its events — there is no
        // terminal to own, and the structured lines are what the caller asked for. An interactive
        // plan runs ahead of the job it prepares for, so it never owns the aggregate progress
        // rider: its lines carry null, and the fraction it reaches does not leak into the first
        // lines of the build that follows.
        if (plan.interactive()) {
            return mode == Mode.JSON
                    ? new JsonlListener(System.out, false)
                    : new SilentListener(System.out, System.err, true);
        }
        return chooseConsoleListener(plan.name(), plan.steps(), mode);
    }

    /**
     * The default (spec-less) listener {@link #run(BuildPlan, Mode, Path)} picks per {@code mode} — split
     * out so a caller with no real {@code BuildPlan} (an engine-hosted run reconstructing the step list
     * from wire events; see {@code EngineResolveAdapter}) can choose the same listener from just the
     * plan's name and steps, instead of duplicating this switch. Non-interactive plans only.
     */
    public static BuildPlanListener chooseConsoleListener(String planName, List<Task> steps, Mode mode) {
        return chooseConsoleListener(planName, planName, steps, mode);
    }

    /**
     * As {@link #chooseConsoleListener(String, List, Mode)} with a distinct header command and tree
     * module label (e.g. {@code Update} / {@code Updating versions}).
     */
    public static BuildPlanListener chooseConsoleListener(String command, String module, List<Task> steps, Mode mode) {
        return switch (mode) {
            case QUIET -> new SilentListener(System.out, System.err);
            case JSON -> new JsonlListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO ->
                isInteractiveTerminal()
                        ? new CommandManagerListener(System.out, command, module, steps, true)
                        : new SilentListener(System.out, System.err);
        };
    }

    /**
     * Run a workspace module's plan into a shared {@link AggregateContext} — its events feed the one
     * aggregate {@link cc.jumpkick.cli.tui.JkManager} (bar + step list) instead of a per-module
     * view. The shared view is settled by the caller after the last module. Always records the event
     * log.
     */
    public static BuildPlanResult runBuildPlanInto(
            BuildPlan plan, Path cacheRoot, String module, AggregateContext agg) {
        // Workspace TTY path is never JSON mode — always mirror plan events into details.jsonl.
        attachSessionMirror(plan, Mode.AUTO);
        plan.addListener(new AggregateModuleListener(agg, module, plan.steps()));
        return plan.run();
    }

    /**
     * As {@link #runBuildPlanInto(BuildPlan, Path, String, AggregateContext)} but for a module built
     * <em>concurrently</em>: its process output is appended to {@code outBuffer} instead of being
     * written above the live region as it arrives, so parallel modules' logs never interleave. The
     * caller flushes the buffer (above the shared region) when the module completes. Step/progress
     * events still feed the shared aggregate view live (the running rows + bar).
     */
    public static BuildPlanResult runBuildPlanIntoBuffered(
            BuildPlan plan, Path cacheRoot, String module, AggregateContext agg, List<String> outBuffer) {
        attachSessionMirror(plan, Mode.AUTO);
        AggregateModuleListener lis = new AggregateModuleListener(agg, module, plan.steps());
        lis.bufferOutputInto(outBuffer);
        plan.addListener(lis);
        return plan.run();
    }

    /**
     * When a CLI session is open and stdout is <em>not</em> already JSONL, mirror plan events
     * into {@code details.jsonl}. JSON mode dual-writes via {@link JsonlListener} instead.
     */
    private static void attachSessionMirror(BuildPlan plan, Mode mode) {
        if (mode == Mode.JSON) return;
        CliSessionTranscript session = CliSessionTranscript.active();
        if (session == null) return;
        plan.addListener(new SessionMirrorListener(session));
    }

    /**
     * True when stdout is an interactive terminal (not a pipe, dumb, or CI) — the output axis that
     * gates live progress / animation. See {@link cc.jumpkick.cli.tui.Interactivity#stdoutIsTty};
     * deliberately stdout-only so {@code jk build | less} draws plain text.
     */
    public static boolean isInteractiveTerminal() {
        return Interactivity.stdoutIsTty();
    }
}
