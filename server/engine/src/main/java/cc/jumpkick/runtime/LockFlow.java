// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.LockOrchestrator;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared "resolve jk.toml → write jk-lock.toml" pipeline used by both {@code jk lock} and {@code
 * jk sync} (the latter delegating here when no lockfile exists yet). This is pure logic: failures
 * are returned in {@link Result#error} for the caller to surface — nothing is written to {@code
 * stderr} here, so only the CLI view layer touches the streams.
 *
 * <p>One lock per workspace: members redirect to the workspace root and lock the full merged
 * graph. Standalone projects (not listed in any ancestor workspace) lock themselves.
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
            String error,
            Lockfile lockfile,
            JkBuild build,
            int workspaceModuleCount,
            boolean workspaceLock,
            Path lockDir) {
        public Result(int status, String error, Lockfile lockfile, JkBuild build, int workspaceModuleCount) {
            this(status, error, lockfile, build, workspaceModuleCount, false, null);
        }
    }

    /** Run the lock pipeline against {@code dir}. */
    public static Result run(Path dir, Path cache, List<String> features, boolean noDefaultFeatures, URI repoUrl)
            throws Exception {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            return new Result(2, "no jk.toml in " + dir, null, null, 0);
        }
        Files.createDirectories(cache);

        JkBuild parsed;
        try {
            parsed = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            return new Result(2, e.getMessage(), null, null, 0);
        }

        // Resolve where the lock lives and what to resolve:
        //   • workspace root  → merge all modules, write root/jk-lock.toml
        //   • workspace member → same as root (full union); never a module-local lock
        //   • standalone       → this project's deps only, write dir/jk-lock.toml
        Path lockDir = dir;
        boolean workspaceLock = false;
        JkBuild effective = parsed;
        int moduleCount = 0;
        try {
            if (parsed.isWorkspaceRoot()) {
                var modules = WorkspaceLoader.loadModules(dir, parsed);
                effective = WorkspaceMerge.merge(parsed, modules.values());
                moduleCount = modules.size();
                workspaceLock = true;
                lockDir = dir;
            } else {
                var rootOpt = WorkspaceLocator.findRoot(dir);
                if (rootOpt.isPresent()) {
                    Path root = rootOpt.get();
                    JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
                    var modules = WorkspaceLoader.loadModules(root, rootManifest);
                    // Full workspace union (not applyToModule): one lock for the monorepo.
                    effective = WorkspaceMerge.merge(rootManifest, modules.values());
                    moduleCount = modules.size();
                    workspaceLock = true;
                    lockDir = root;
                }
            }
        } catch (RuntimeException e) {
            return new Result(2, e.getMessage(), null, null, 0);
        }
        Path lockFile = lockDir.resolve(cc.jumpkick.lock.LockPaths.FILE_NAME);
        // Standalone projects union variant dep overlays here; workspace scopes were unioned
        // inside WorkspaceMerge (idempotent either way).
        effective = Variants.unionDependencies(effective);

        Cas cas = JkStores.cas(cache);
        RepoGroup baseRepos =
                RepoGroupBuilder.buildFor(effective, repoUrl, cas, cc.jumpkick.config.BuildEnv.forModule(dir));

        // Git- and path-source deps: materialize each into a local file:// repo and rewrite
        // them to exact coordinate pins before the solver runs (git-source-deps.md).
        GitSourceResolution.Prepared prep;
        PathSourceResolution.Prepared pathPrep;
        try {
            Path javaHome = JavaHomes.resolveJavaHome(dir);
            prep = GitSourceResolution.prepare(
                    effective, baseRepos, cas, javaHome, cc.jumpkick.model.JkVersion.VERSION);
            pathPrep = PathSourceResolution.prepare(
                    prep.project(), prep.repos(), cas, dir, javaHome, cc.jumpkick.model.JkVersion.VERSION);
        } catch (Exception e) {
            return new Result(6, e.getMessage(), null, effective, moduleCount);
        }
        LockOrchestrator orchestrator = new LockOrchestrator(pathPrep.repos())
                .withProjectDir(dir)
                .withJvmEnvironment(cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(effective, dir))
                .withPlatformPolicy(pathPrep.project().build().platformPolicy())
                .withUnmappedPolicy(pathPrep.project().build().unmappedPolicy());

        Lockfile lock;
        try {
            lock = orchestrator.lock(
                    pathPrep.project(), cc.jumpkick.model.JkVersion.VERSION, features, !noDefaultFeatures);
        } catch (IOException e) {
            return new Result(
                    6, e.getMessage() + variantUnionHint(lockDir, parsed), null, effective, moduleCount, workspaceLock, lockDir);
        }
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
        LockfileWriter.write(lock, lockFile);
        cc.jumpkick.task.AccessLedger.atDefaultPath().touchLock(lock);
        return new Result(0, null, lock, effective, moduleCount, workspaceLock, lockDir);
    }

    /**
     * When a resolve fails and {@code [variants]} dependency overlays are in play, say so: the
     * lock resolves the UNION of every value's deps, so the conflict
     * may be between values that never build together — name each value's contributions so the
     * user can align versions across them.
     */
    private static String variantUnionHint(Path dir, JkBuild parsed) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            collectOverlayLines(parsed, null, lines);
            if (parsed.isWorkspaceRoot()) {
                for (var e : WorkspaceLoader.loadModules(dir, parsed).entrySet()) {
                    collectOverlayLines(e.getValue(), e.getValue().project().name(), lines);
                }
            }
        } catch (Exception ignored) {
            // hint construction must never mask the real resolve error
        }
        if (lines.isEmpty()) return "";
        StringBuilder b = new StringBuilder(
                "\nnote: jk-lock.toml resolves the UNION of every variant value's dependencies,"
                        + "\nso this conflict may be between values that never build together — align their"
                        + "\nversions across values (docs/variants.md → Locking). Overlays in play:");
        for (String line : lines) b.append("\n  ").append(line);
        return b.toString();
    }

    private static void collectOverlayLines(JkBuild build, String module, java.util.List<String> lines) {
        for (String line : Variants.describeDependencyOverlays(build)) {
            lines.add(module == null ? line : module + ": " + line);
        }
    }
}
