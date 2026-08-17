// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanListener;
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
 * missing or stale ({@link LockFreshness}), this runs the engine lock plan under a live
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
        boolean showOwn = ownSpinner && spinner == null && isInteractiveAuto(global) && !global.outputIsJson();
        try {
            // Conservative: a freshen must never float pinned versions — that is `jk lock`'s job.
            EngineRequests.LockRequest req = new EngineRequests.LockRequest(
                    dir, cache, List.of(), false, false, null, global.offline, global.force, global.verbose, true);

            EngineRequests.LockHandler quiet = new EngineRequests.LockHandler() {
                @Override
                public BuildPlanListener onModuleStart(
                        String moduleDir, String moduleCoord, List<cc.jumpkick.run.Task> steps) {
                    return new BuildPlanListener() {};
                }
            };

            EngineRequests.LockOutcome outcome;
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
                return failSoftOrHard(dir, chip, err, outcome.exitCode(), spinner);
            }
            return Exit.SUCCESS;
        } catch (Exception e) {
            return failSoftOrHard(dir, chip, "could not refresh jk-lock.toml: " + e.getMessage(), Exit.CONFIG, spinner);
        }
    }

    /**
     * Failure policy, mirroring the engine's {@code AutoLock}/{@code BuildService} guards: with an
     * existing readable lock, a freshen failure is soft — warn and proceed on the stale lock —
     * unless resolution is genuinely unsatisfiable (the manifest itself is broken). No lock at all
     * stays a hard failure: the command has nothing to read. Any live wedge spinner is settled
     * before writing, so error lines never interleave with repaints.
     */
    static int failSoftOrHard(Path dir, String chip, String err, int exitCode, Spinner spinner) {
        if (spinner != null) spinner.close(); // idempotent; caller's try-with-resources may close again
        boolean unsatisfiable = err != null && err.contains("Cannot resolve dependencies");
        Path lockFile = LockPaths.lockFile(lockOwnerOrSelf(dir));
        if (!unsatisfiable && Files.isRegularFile(lockFile)) {
            CliOutput.err("‼ jk: lock refresh failed — using existing jk-lock.toml: " + err);
            CliOutput.err("    Run `jk lock` to resolve manually.");
            return Exit.SUCCESS;
        }
        CommandWedge.printFail(chip, err);
        return exitCode != 0 ? exitCode : Exit.CONFIG;
    }

    private static Path lockOwnerOrSelf(Path dir) {
        try {
            return LockPaths.lockOwnerDir(dir);
        } catch (Exception e) {
            return dir;
        }
    }

    /** True when interactive AUTO mode (live spinners allowed). */
    public static boolean isInteractiveAuto(GlobalOptions global) {
        try {
            return cc.jumpkick.cli.run.BuildPlanConsole.isInteractiveTerminal()
                    && cc.jumpkick.cli.run.BuildPlanConsole.modeFor(global)
                            == cc.jumpkick.cli.run.BuildPlanConsole.Mode.AUTO;
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
            var info = cc.jumpkick.command.BuildCommand.projectInfoOrNull(owner);
            if (info != null && info.error() == null) {
                String g = info.group();
                String n = info.name();
                if (g != null && !g.isBlank() && n != null && !n.isBlank()) return g + ":" + n;
                if (n != null && !n.isBlank()) return n;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "project";
    }
}
