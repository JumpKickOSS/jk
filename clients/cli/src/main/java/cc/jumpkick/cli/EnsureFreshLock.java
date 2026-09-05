// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Invisible lock freshen for every command that needs a current {@code jk-lock.toml}.
 *
 * <p>Users should never have to think about the lock: clones arrive with matching
 * {@code jk.toml}/{@code jk-lock.toml}, and any local manifest edit (or rare out-of-sync pair)
 * is repaired automatically the next time a lock-dependent command runs. When the lock is
 * missing or stale ({@code projectInfo.lockStale}), this runs the engine lock plan under a live
 * CommandWedge spinner ({@code Locking g:n…}). Fresh locks are a no-op. The client does not
 * parse {@code jk.toml} to decide staleness.
 *
 * <p>Call sites: explain, tree, why, audit, deny, outdated, jshell, status, export, ide, sync,
 * install, and anything else that reads the lock. Build already freshes engine-side.
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
    public static int ensure(
            @Nullable Path projectDir, Path cacheDir, @Nullable GlobalOptions global, String wedgeCommand) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, /* spinner */ null, /* ownSpinner */ true);
    }

    /**
     * Like {@link #ensure(Path, Path, GlobalOptions, String)} but never creates a spinner — the
     * caller owns progress UI (e.g. {@code jk explain}'s shared prep wedge). Still prints a fail
     * wedge on error.
     */
    public static int ensureQuiet(Path projectDir, Path cacheDir, @Nullable GlobalOptions global, String wedgeCommand) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, null, false);
    }

    /**
     * @param spinner optional existing live wedge; when non-null, {@code ownSpinner} is ignored and
     *     this spinner is left open (caller may {@link Spinner#update} before/after)
     * @param ownSpinner when true and {@code spinner} is null, show a short-lived lock spinner
     */
    public static int ensure(
            @Nullable Path projectDir,
            Path cacheDir,
            @Nullable GlobalOptions global,
            String wedgeCommand,
            Spinner spinner,
            boolean ownSpinner) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, spinner, ownSpinner, null);
    }

    /**
     * As {@link #ensure(Path, Path, GlobalOptions, String)} honoring the command's
     * {@code --repo-url} override: the invisible freshen resolves against the SAME repo the
     * command will use — dropping it made `jk outdated --repo-url …` on a lockless project
     * fail its freshen against the declared repos.
     */
    public static int ensure(
            @Nullable Path projectDir,
            Path cacheDir,
            @Nullable GlobalOptions global,
            String wedgeCommand,
            @Nullable URI repoUrl) {
        return ensure(projectDir, cacheDir, global, wedgeCommand, null, true, repoUrl);
    }

    /**
     * Best-effort freshen for read verbs that resolve independently of the lock
     * ({@code jk outdated}): attempt the invisible freshen, but a failure — even with no
     * lock at all — is a warning, never an exit.
     */
    public static void ensureBestEffort(
            @Nullable Path projectDir,
            Path cacheDir,
            @Nullable GlobalOptions global,
            @Nullable String wedgeCommand,
            @Nullable URI repoUrl) {
        int code = ensure(projectDir, cacheDir, global, wedgeCommand, null, true, repoUrl);
        if (code != Exit.SUCCESS) {
            CliOutput.err("‼ jk: lock freshen failed — continuing without jk-lock.toml");
        }
    }

    private static int ensure(
            @Nullable Path projectDir,
            @Nullable Path cacheDir,
            @Nullable GlobalOptions global,
            @Nullable String wedgeCommand,
            @Nullable Spinner spinner,
            boolean ownSpinner,
            @Nullable URI repoUrl) {
        if (projectDir == null) return Exit.SUCCESS; // nothing to lock outside a project
        GlobalOptions opts = global == null ? new GlobalOptions() : global;
        Path dir = projectDir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) {
            return Exit.SUCCESS; // caller already validated project
        }
        if (!needsRefresh(dir)) {
            return Exit.SUCCESS;
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        String coord = lockCoordLabel(dir);
        String message = "Locking " + coord + "…";
        String chip = wedgeCommand == null || wedgeCommand.isBlank() ? "Lock" : wedgeCommand;

        EnginePrewarm.ensure();
        boolean showOwn = ownSpinner && spinner == null && isInteractiveAuto(opts) && !opts.outputIsJson();
        try {
            // freshen=true: an invisible freshen must never float pinned versions, not even under
            // -F — that is `jk lock -F` / `jk update`'s job.
            EngineRequests.LockRequest req = new EngineRequests.LockRequest(
                    dir, cache, List.of(), false, false, repoUrl, opts.offline, opts.force, opts.verbose, true);

            EngineRequests.LockHandler quiet = new EngineRequests.LockHandler() {
                @Override
                public BuildPlanListener onModuleStart(String moduleDir, String moduleCoord, List<Task> steps) {
                    return new BuildPlanListener() {};
                }
            };

            EngineRequests.LockOutcome outcome;
            if (spinner != null) {
                spinner.update(message);
                outcome = EngineClient.runLock(EnginePaths.current(), req, quiet);
            } else if (showOwn) {
                try (Spinner ignored = CommandWedge.analyzingStdout(chip, message)) {
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
            // The lock just changed on disk — memoized summaries (hasLock/lockJdk/lockStale)
            // are stale for the rest of this invocation.
            ProjectInfos.forget();
            return Exit.SUCCESS;
        } catch (Exception e) {
            return failSoftOrHard(dir, chip, "could not refresh jk-lock.toml: " + e.getMessage(), Exit.CONFIG, spinner);
        }
    }

    /**
     * Engine-side staleness: {@link cc.jumpkick.wire.protocol.ProjectInfo#lockStale()} plus a
     * missing lock file. A down/errored info is treated as stale so we still try to freshen.
     */
    public static boolean needsRefresh(Path projectDir) {
        Path owner = lockOwnerOrSelf(projectDir);
        if (!Files.isRegularFile(LockPaths.lockFile(owner))) return true;
        var info = ProjectInfos.orNull(owner);
        return info == null || info.lockStale();
    }

    /**
     * Failure policy, mirroring the engine's {@code AutoLock}/{@code BuildService} guards: with an
     * existing readable lock, a freshen failure is soft — warn and proceed on the stale lock —
     * unless resolution is genuinely unsatisfiable (the manifest itself is broken). No lock at all
     * stays a hard failure: the command has nothing to read. Any live wedge spinner is settled
     * before writing, so error lines never interleave with repaints.
     */
    static int failSoftOrHard(Path dir, String chip, String err, int exitCode, @Nullable Spinner spinner) {
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
    public static boolean isInteractiveAuto(@Nullable GlobalOptions global) {
        try {
            return BuildPlanConsole.isInteractiveTerminal()
                    && BuildPlanConsole.modeFor(global) == BuildPlanConsole.Mode.AUTO;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Plain {@code group:name} for the lock owner (workspace root or standalone). */
    static String lockCoordLabel(Path projectDir) {
        try {
            Path owner = LockPaths.lockOwnerDir(projectDir);
            Path toml = owner.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(toml)) return owner.getFileName().toString();
            var info = ProjectInfos.orNull(owner);
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
