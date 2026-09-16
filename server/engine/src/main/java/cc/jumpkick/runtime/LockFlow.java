// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@link LockPipeline} run to completion, without a {@link cc.jumpkick.run.BuildPlan} around it —
 * the first lock of {@code jk sync}, the first lock of a build, and the pre-build workspace
 * freshen. This is pure logic: failures are returned in {@link
 * Result#error} for the caller to surface — nothing is written to {@code stderr} here, so only the
 * CLI view layer touches the streams.
 *
 * <p>One lock per workspace: {@link LockPlans#lockScope} redirects members to the workspace root
 * and locks the full merged graph.
 */
public final class LockFlow {

    private LockFlow() {}

    /**
     * Outcome of one lock pass. {@code status == 0} means success, {@link #error} is {@code null},
     * and {@link #lockfile} / {@link #build} are populated. Non-zero means the caller should return
     * that exit code and surface {@link #error} (a bare message, no command prefix).
     *
     * @param workspaceLock true when the written lock is the workspace-wide root lock (member or
     *     root entry), so the CLI can announce that clearly
     * @param lockDir directory that owns the written {@code jk-lock.toml}
     */
    public record Result(
            int status,
            @Nullable String error,
            @Nullable Lockfile lockfile,
            @Nullable JkBuild build,
            int workspaceModuleCount,
            boolean workspaceLock,
            @Nullable Path lockDir) {
        public Result(
                int status,
                @Nullable String error,
                @Nullable Lockfile lockfile,
                @Nullable JkBuild build,
                int workspaceModuleCount) {
            this(status, error, lockfile, build, workspaceModuleCount, false, null);
        }
    }

    /**
     * Run the lock plan against {@code dir}. Every caller here is a lock the user did not ask for
     * by name, so pins from the existing lock are fed to the solver as soft preferences and only
     * coordinates a new or changed constraint rules out move. An already-current lock is returned
     * as it stands. Floating within the declared ranges is {@code jk lock -F} / {@code jk update},
     * which run the {@link LockPlans} plan instead.
     */
    public static Result run(
            Path dir, Path cache, List<String> features, boolean noDefaultFeatures, @Nullable URI repoUrl)
            throws Exception {
        if (!Files.exists(dir.resolve(ManifestPaths.MANIFEST))) {
            return new Result(Exit.CONFIG, "no jk.toml in " + dir, null, null, 0);
        }
        Files.createDirectories(cache);

        // Workspace root or member → the merged union at the root; standalone → itself.
        LockPlans.LockScope scope;
        try {
            scope = LockPlans.lockScope(dir);
        } catch (IOException | RuntimeException e) {
            return new Result(Exit.CONFIG, e.getMessage(), null, null, 0);
        }
        Path lockDir = scope.lockDir();
        Path lockFile = LockPaths.lockFile(lockDir);

        // Serialize per lock dir. A freshen that waited here may find the lock already
        // fresh — a concurrent job won the flight; skip the duplicate resolve.
        synchronized (LockGate.monitorFor(lockDir)) {
            if (Files.exists(lockFile) && !LockFreshness.isStale(lockDir, lockFile)) {
                try {
                    Lockfile current = LockfileReader.read(lockFile);
                    return ok(current, scope);
                } catch (Exception e) {
                    // unreadable — fall through and re-lock
                    Log.debug("run: unreadable", e);
                }
            }
            return resolveAndWrite(scope, cache, features, noDefaultFeatures, repoUrl);
        }
    }

    private static Result resolveAndWrite(
            LockPlans.LockScope scope,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            @Nullable URI repoUrl) {
        LockPipeline pipeline = new LockPipeline(
                scope.lockDir(),
                scope.effective(),
                cache,
                repoUrl,
                features,
                !noDefaultFeatures,
                new LockMode.Freshen());
        try {
            Lockfile lock = pipeline.run(
                    LockPipeline.readIfPresent(scope.lockDir()), ResolveObserver.NOOP, LockPipeline.Progress.SILENT);
            return ok(lock, scope);
        } catch (UnsatisfiableException e) {
            throw e; // the solver's own explanation is the message; callers render it verbatim
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("interrupted", scope);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof UnsatisfiableException unsat) throw unsat;
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            return failed(Errors.text(cause) + variantUnionHint(scope.lockDir()), scope);
        }
    }

    private static Result ok(Lockfile lock, LockPlans.LockScope scope) {
        return new Result(0, null, lock, scope.effective(), scope.moduleCount(), scope.workspace(), scope.lockDir());
    }

    /** Exit 6: the resolve could not complete (unsatisfiable graph, unreachable repo, bad source). */
    private static Result failed(String error, LockPlans.LockScope scope) {
        return new Result(6, error, null, scope.effective(), scope.moduleCount(), scope.workspace(), scope.lockDir());
    }

    /**
     * When a resolve fails and {@code [variants]} dependency overlays are in play, say so: the
     * lock resolves the UNION of every value's deps, so the conflict
     * may be between values that never build together — name each value's contributions so the
     * user can align versions across them.
     */
    private static String variantUnionHint(Path lockDir) {
        List<String> lines = new ArrayList<>();
        try {
            JkBuild owner = JkBuildParser.parse(lockDir.resolve(ManifestPaths.MANIFEST));
            collectOverlayLines(owner, null, lines);
            if (owner.isWorkspaceRoot()) {
                for (var e : WorkspaceLoader.loadModules(lockDir, owner).entrySet()) {
                    collectOverlayLines(e.getValue(), e.getValue().project().name(), lines);
                }
            }
        } catch (Exception e) {
            // hint construction must never mask the real resolve error
            Log.debug("variantUnionHint: hint construction must never mask the real resolve error", e);
        }
        if (lines.isEmpty()) return "";
        StringBuilder b =
                new StringBuilder("\nnote: jk-lock.toml resolves the UNION of every variant value's dependencies,"
                        + "\nso this conflict may be between values that never build together — align their"
                        + "\nversions across values (docs/variants.md → Locking). Overlays in play:");
        for (String line : lines) b.append("\n  ").append(line);
        return b.toString();
    }

    private static void collectOverlayLines(JkBuild build, @Nullable String module, List<String> lines) {
        for (String line : Variants.describeDependencyOverlays(build)) {
            lines.add(module == null ? line : module + ": " + line);
        }
    }
}
