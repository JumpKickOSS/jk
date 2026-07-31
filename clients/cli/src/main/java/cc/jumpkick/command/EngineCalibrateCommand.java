// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk engine calibrate} — measure this machine's build-relevant costs.
 *
 * <ol>
 * <li>If the engine is not running (or {@code --force} + stop), time a cold engine spawn.
 * <li>Ask the engine to run the multi-probe suite (JVM fork, javac, disk, hash CPU, synthetic
 * test-worker, JUnit Platform + resolve when online) and store timings under {@code
 * ~/.jk/state/builds/calibration.toml}.
 * </ol>
 *
 * <p>Network is <strong>on by default</strong> (fetch Jupiter jars if missing + Maven Central
 * micro-GET). Opt out with global {@code --offline}. Idempotent unless global {@code --force}.
 *
 * <p>TTY chrome: one live {@code ▶ Calibrate …} line that settles in place to
 * {@code ✓ Calibrate Host calibration saved} (probe details print below).
 */
public final class EngineCalibrateCommand implements CliCommand {

    @Override
    public String name() {
        return "calibrate";
    }

    @Override
    public String description() {
        return "Measure host build timings for better cold ETAs (--force to re-run; --offline skips network probes)";
    }

    @Override
    public List<cc.jumpkick.model.command.Opt> options() {
        // --force and --offline are global (GlobalOptions).
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = global.force;
        boolean allowNetwork = !global.offline;
        EnginePaths.Paths paths = EnginePaths.current();
        long coldMs = 0;
        // One live chip line on a TTY (▶ … → ✓ …); pipes/json get a single settled line only.
        boolean animate =
                PipelineConsole.isInteractiveTerminal() && PipelineConsole.modeFor(global) == PipelineConsole.Mode.AUTO;
        try (CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Calibrate", animate)) {
            boolean alreadyUp = EngineClient.handshake(EnginePaths.activeSocket(paths), Jk.VERSION)
                    .map(h -> Jk.VERSION.equals(h.version()))
                    .orElse(false);
            if (force && alreadyUp) {
                // Stop so we can time a true cold start, then re-probe.
                EngineClient.forceStop(EnginePaths.activeSocket(paths));
                alreadyUp = false;
            }
            if (!alreadyUp) {
                view.solveLabel("Starting engine (cold)…");
                long t0 = System.nanoTime();
                EngineClient.ensureRunning(paths, Jk.VERSION);
                coldMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
            } else {
                EngineClient.ensureRunning(paths, Jk.VERSION);
            }

            String msg = force ? "Re-running host probes" : "Running host probes";
            if (!allowNetwork) msg += " (offline)";
            view.solveLabel(msg + "…");
            Optional<String> ack = EngineClient.calibrate(paths, force, coldMs, allowNetwork);
            if (ack.isEmpty()) {
                view.finishPipelineFailure("engine did not return calibration");
                return Exit.SOFTWARE;
            }
            String line = ack.get();
            if (!Jsonl.bool(line, "ok", false)) {
                String fail = Jsonl.str(line, "summary");
                view.finishPipelineFailure(fail != null && !fail.isBlank() ? fail : "failed");
                return Exit.SOFTWARE;
            }
            // In-place settle: replaces the ▶ probes line with the ✓ result.
            view.finishPipelineSuccess("Host calibration saved");
            String summary = Jsonl.str(line, "summary");
            if (summary != null && !summary.isBlank()) {
                for (String row : summary.split("\n")) {
                    CliOutput.out("  " + row);
                }
            } else {
                CliOutput.out("  ms-per-weight=" + Jsonl.longValue(line, "msPerWeight", 0));
            }
            if (coldMs > 0) {
                CliOutput.out("  (engine cold start measured by client: " + coldMs + " ms)");
            }
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err(CommandWedge.fail("Calibrate", e.getMessage()));
            return Exit.SOFTWARE;
        }
    }
}
