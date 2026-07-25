// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk engine calibrate} — measure this machine's build-relevant costs (JK-1180).
 *
 * <ol>
 *   <li>If the engine is not running (or {@code --force} + stop), time a cold engine spawn.
 *   <li>Ask the engine to run the offline multi-probe suite (JVM fork, javac, disk, hash CPU,
 *       synthetic test-worker) and store a pessimistic {@code ms-per-weight} under {@code
 *       ~/.jk/state/builds/calibration.toml}.
 * </ol>
 *
 * <p>No network. Idempotent unless the global {@code --force} flag is set. Prints a human summary
 * of component timings.
 */
public final class EngineCalibrateCommand implements CliCommand {

    @Override
    public String name() {
        return "calibrate";
    }

    @Override
    public String description() {
        return "Measure host build timings for better cold ETAs (offline micro-probes; use --force to re-run)";
    }

    @Override
    public List<Opt> options() {
        // --force is global (GlobalOptions); re-run probes + retime cold engine start.
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        boolean force = GlobalOptions.from(in).force;
        EnginePaths.Paths paths = EnginePaths.current();
        long coldMs = 0;
        try {
            boolean alreadyUp = EngineClient.handshake(EnginePaths.activeSocket(paths), Jk.VERSION)
                    .map(h -> Jk.VERSION.equals(h.version()))
                    .orElse(false);
            if (force && alreadyUp) {
                // Stop so we can time a true cold start, then re-probe.
                EngineClient.forceStop(EnginePaths.activeSocket(paths));
                alreadyUp = false;
            }
            if (!alreadyUp) {
                CliOutput.out(PipelineWedge.chipLine(
                        Glyphs.PLAY, "Calibrate", GlobalConfig.nerdfont(), "Starting engine (cold)…"));
                long t0 = System.nanoTime();
                EngineClient.ensureRunning(paths, Jk.VERSION);
                coldMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
            } else {
                EngineClient.ensureRunning(paths, Jk.VERSION);
            }

            CliOutput.out(PipelineWedge.chipLine(
                    Glyphs.PLAY,
                    "Calibrate",
                    GlobalConfig.nerdfont(),
                    force ? "Re-running host probes…" : "Running host probes…"));
            Optional<String> ack = EngineClient.calibrate(paths, force, coldMs);
            if (ack.isEmpty()) {
                CliOutput.err(CommandWedge.fail("Calibrate", "engine did not return calibration"));
                return Exit.SOFTWARE;
            }
            String line = ack.get();
            if (!Jsonl.bool(line, "ok", false)) {
                CliOutput.err(CommandWedge.fail(
                        "Calibrate", Jsonl.str(line, "summary") != null ? Jsonl.str(line, "summary") : "failed"));
                return Exit.SOFTWARE;
            }
            String summary = Jsonl.str(line, "summary");
            CliOutput.out(PipelineWedge.chipLine(
                    Glyphs.CHECK, "Calibrate", GlobalConfig.nerdfont(), "Host calibration saved"));
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
