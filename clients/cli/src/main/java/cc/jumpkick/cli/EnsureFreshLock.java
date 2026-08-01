// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Invisible lock freshen for every command that needs a current {@code jk-lock.toml}.
 *
 * <p>Users should never have to think about the lock: clones arrive with matching
 * {@code jk.toml}/{@code jk-lock.toml}, and any local manifest edit (or rare out-of-sync pair)
 * is repaired automatically the next time a lock-dependent command runs. When the lock is
 * missing or stale ({@link LockFreshness}), this runs the engine lock pipeline under a live
 * CommandWedge spinner ({@code Locking g:n…}). Fresh locks are a no-op.
 *
 * <p>Call sites: explain, tree, why, audit, deny, outdated, jshell, status, export, ide, sync,
 * plugin install-local, and anything else that reads the lock. Build already freshes engine-side.
 * {@code jk verify} is the exception — it must rebuild against the pinned lock verbatim.
 */
public final class EnsureFreshLock {

    private EnsureFreshLock() {}

    /**
     * Ensure the lock for {@code projectDir} is present and fresh. Shows its own {@code Locking
     * g:n…} spinner when interactive.
     *
     * @param wedgeCommand chip label (e.g. {@code "Explain"}, {@code "Status"})
     * @return 0 when the lock is already fresh or was refreshed successfully; otherwise a non-zero
     *     exit code (and an error already printed)
     */
    public static int ensure(Path projectDir, Path cacheDir, GlobalOptions global, String wedgeCommand) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, /* spinner */ null, /* ownSpinner */ true);
    }

    /**
     * Like {@link #ensure(Path, Path, GlobalOptions, String)} but never creates a spinner — the
     * caller owns progress UI (e.g. {@code jk explain}'s shared prep wedge). Still prints a fail
     * wedge on error.
     */
    public static int ensureQuiet(Path projectDir, Path cacheDir, GlobalOptions global, String wedgeCommand) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, null, false);
    }

    /**
     * @param spinner optional existing live wedge; when non-null, {@code ownSpinner} is ignored and
     *     this spinner is left open (caller may {@link Spinner#update} before/after)
     * @param ownSpinner when true and {@code spinner} is null, show a short-lived lock spinner
     */
    public static int ensure(
            Path projectDir,
            Path cacheDir,
            GlobalOptions global,
            String wedgeCommand,
            Spinner spinner,
            boolean ownSpinner) {
        Path dir = projectDir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("jk.toml"))) {
            return Exit.SUCCESS; // caller already validated project
        }
        if (!LockFreshness.needsRefresh(dir)) {
            return Exit.SUCCESS;
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        String coord = lockCoordLabel(dir);
        String message = "Locking " + coord + "…";
        String chip = wedgeCommand == null || wedgeCommand.isBlank() ? "Lock" : wedgeCommand;

        EnginePrewarm.ensure();
        boolean showOwn = ownSpinner
                && spinner == null
                && isInteractiveAuto(global)
                && !global.outputIsJson();
        try {
            EngineClient.LockRequest req = new EngineClient.LockRequest(
                    dir,
                    cache,
                    List.of(),
                    false,
                    false,
                    null,
                    global.offline,
                    global.force,
                    global.verbose);

            EngineClient.LockHandler quiet = new EngineClient.LockHandler() {
                @Override
                public PipelineListener onModuleStart(
                        String moduleDir, String moduleCoord, List<cc.jumpkick.run.Step> steps) {
                    return new PipelineListener() {};
                }
            };

            EngineClient.LockOutcome outcome;
            if (spinner != null) {
                spinner.update(message);
                outcome = EngineClient.runLock(EnginePaths.current(), req, quiet);
            } else if (showOwn) {
                try (Spinner ignored = CommandWedge.analyzing(CliOutput.stdout(), chip, message)) {
                    outcome = EngineClient.runLock(EnginePaths.current(), req, quiet);
                }
            } else {
                outcome = EngineClient.runLock(EnginePaths.current(), req, quiet);
            }

            if (outcome.exitCode() != 0) {
                String err = outcome.errors() == null || outcome.errors().isEmpty()
                        ? "could not refresh jk-lock.toml"
                        : outcome.errors().getFirst();
                CliOutput.err(CommandWedge.fail(chip, err));
                return outcome.exitCode() != 0 ? outcome.exitCode() : Exit.CONFIG;
            }
            return Exit.SUCCESS;
        } catch (Exception e) {
            CliOutput.err(CommandWedge.fail(chip, "could not refresh jk-lock.toml: " + e.getMessage()));
            return Exit.CONFIG;
        }
    }

    /** True when interactive AUTO mode (live spinners allowed). */
    public static boolean isInteractiveAuto(GlobalOptions global) {
        try {
            return cc.jumpkick.cli.run.PipelineConsole.isInteractiveTerminal()
                    && cc.jumpkick.cli.run.PipelineConsole.modeFor(global)
                            == cc.jumpkick.cli.run.PipelineConsole.Mode.AUTO;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Plain {@code group:name} for the lock owner (workspace root or standalone). */
    static String lockCoordLabel(Path projectDir) {
        try {
            Path owner = LockPaths.lockOwnerDir(projectDir);
            Path toml = owner.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) return owner.getFileName().toString();
            JkBuild build = JkBuildParser.parseLocal(toml);
            String g = build.project().group();
            String n = build.project().name();
            if (g != null && !g.isBlank() && n != null && !n.isBlank()) {
                return g + ":" + n;
            }
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {
            // fall through
        }
        return "project";
    }
}
