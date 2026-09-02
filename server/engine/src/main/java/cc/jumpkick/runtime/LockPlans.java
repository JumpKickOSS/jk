// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.repo.LibraryRegistrySync;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * {@link LockPipeline} as a {@link BuildPlan} for {@code jk lock} / {@code jk update}: the same
 * five stages, wrapped in steps so the bar and {@link ResolveObserver} can report them. Progress
 * comes from plan listeners; diagnostics are plain (client themes). Engine passes {@code
 * coordLabel=null} and streams structured package events.
 */
public final class LockPlans {

    private LockPlans() {}

    /** Cross-step key: the lockfile as it accumulates through resolve → lock-plugins → write. */
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.scalar("lockfile", Lockfile.class);

    /**
     * Cross-step key: manifests digest captured at parse time — the write step stamps this instead
     * of re-reading live files, so a manifest edited mid-resolution leaves a stale-reading lock.
     */
    public static final BuildPlanKey<String> MANIFESTS_SHA = BuildPlanKey.scalar("manifests-sha", String.class);

    /**
     * Build the {@code jk lock} plan for one project directory: {@code parse-build} → {@code
     * resolve} → {@code lock-plugins} → {@code lock-sdk} → {@code write-lockfile}. The offline flag
     * is read off the ambient {@link SessionContext} at step-run time, so both the CLI (which
     * installs the session from its global flags) and the engine (which reconstructs it from the
     * wire request) behave alike.
     *
     * @param observer per-package resolution events (never {@code null}; use {@link
     * ResolveObserver#NOOP})
     * @param coordLabel formats a {@code module, version} pair for progress labels, or {@code null}
     * to emit no per-package labels (the engine-hosted path — the client synthesizes them from
     * {@code lock-package} events so coloring stays client-side)
     */
    public static BuildPlan lockBuildPlan(
            Path dir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            boolean sources,
            ResolveObserver observer,
            @Nullable BiFunction<String, String, String> coordLabel) {
        return lockBuildPlan(
                dir, effective, cache, repoUrl, features, withDefaultFeatures, sources, false, observer, coordLabel);
    }

    /**
     * As {@link #lockBuildPlan(Path, JkBuild, Path, URI, List, boolean, boolean, ResolveObserver,
     * BiFunction)} with a {@code conservative} switch: an invisible freshen ({@code
     * EnsureFreshLock}) keeps every pin from the existing lock as a solver preference — only
     * coordinates a new or changed constraint rules out move. Explicit {@code jk lock} passes
     * {@code false} and floats to latest.
     */
    public static BuildPlan lockBuildPlan(
            Path dir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            boolean sources,
            boolean conservative,
            ResolveObserver observer,
            @Nullable BiFunction<String, String, String> coordLabel) {
        LockMode mode = conservative && !sources ? new LockMode.Freshen() : new LockMode.Explicit(sources);
        return plan(dir, effective, cache, repoUrl, features, withDefaultFeatures, mode, observer, coordLabel);
    }

    /**
     * {@code jk update}: as {@link #lockBuildPlan} but always resolves fresh, past the
     * maven-metadata TTL. {@code platformOverride} is the CLI {@code --platform}
     * ({@code enforced}|{@code floor}). Preflight (parse) owns ~10% of the bar; resolve owns the
     * rest via per-package graph/materialize ticks so the last dep lands near 100%.
     */
    public static BuildPlan updateBuildPlan(
            Path dir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            @Nullable String platformOverride,
            ResolveObserver observer) {
        return plan(
                dir,
                effective,
                cache,
                repoUrl,
                features,
                withDefaultFeatures,
                new LockMode.Update(platformOverride),
                observer,
                null);
    }

    /** Plan name, resolve wording and preflight budget — the only things a mode changes here. */
    private record PlanShape(String planName, String resolveLabel, IntUnaryOperator preflightTicks) {}

    private static PlanShape shapeFor(LockMode mode) {
        return switch (mode) {
            // A single preflight tick; resolve owns the whole bar.
            case LockMode.Explicit ignored -> new PlanShape("lock", "Resolving", resolveTicks -> 1);
            case LockMode.Freshen ignored -> new PlanShape("lock", "Resolving", resolveTicks -> 1);
            // ~10% of the bar for parse/preflight, so the last resolve tick lands near 100% rather
            // than stuck at an equal split.
            case LockMode.Update ignored ->
                new PlanShape(
                        "update",
                        "re-resolve dependencies",
                        resolveTicks -> Math.max(1, (int) Math.round(resolveTicks / 9.0)));
        };
    }

    private static BuildPlan plan(
            Path dir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            LockMode mode,
            ResolveObserver observer,
            @Nullable BiFunction<String, String, String> coordLabel) {
        LockPipeline pipeline = new LockPipeline(
                dir, effective, cache, repoUrl, features, withDefaultFeatures, mode, JkVersion.VERSION);
        PlanShape shape = shapeFor(mode);
        Path lockFile = pipeline.lockFile();
        int resolveTicks = scopeEstimate(effective, lockFile);
        int preflightTicks = shape.preflightTicks().applyAsInt(resolveTicks);
        AtomicInteger resolveEstimate = new AtomicInteger(0);

        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(preflightTicks)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(MANIFESTS_SHA, pipeline.manifestsSha());
                    ctx.progress(preflightTicks);
                })
                .build();

        Task resolve = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .label(shape.resolveLabel())
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(() -> {
                    resolveEstimate.set(resolveTicks);
                    return resolveTicks;
                })
                .execute(ctx -> {
                    ctx.label(shape.resolveLabel());
                    try {
                        ctx.put(
                                LOCKFILE,
                                pipeline.resolve(
                                        LockPipeline.readIfPresent(dir),
                                        barObserver(ctx, observer, resolveEstimate, coordLabel),
                                        LockPipeline.of(ctx)));
                    } catch (UnsatisfiableException e) {
                        ctx.error("verbatim", e.getMessage());
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        ctx.error(TaskNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();

        Task lockPlugins = Task.builder(TaskNames.LOCK_PLUGINS)
                .kind(TaskKind.IO)
                .requires(TaskNames.RESOLVE_DEPS)
                .ticks(Math.max(1, effective.plugins().size()))
                .execute(ctx -> {
                    try {
                        ctx.put(LOCKFILE, pipeline.pinPlugins(ctx.require(LOCKFILE), LockPipeline.of(ctx)));
                    } catch (RuntimeException e) {
                        ctx.error("plugin", e.getMessage());
                        throw e;
                    }
                })
                .build();

        Task lockSdk = Task.builder(TaskNames.LOCK_SDK)
                .kind(TaskKind.IO)
                .requires(TaskNames.LOCK_PLUGINS)
                .ticks(1)
                .execute(ctx -> ctx.put(LOCKFILE, pipeline.pinSdk(ctx.require(LOCKFILE), LockPipeline.of(ctx))))
                .build();

        Task write = Task.builder(TaskNames.WRITE_LOCKFILE)
                .requires(TaskNames.LOCK_SDK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    ctx.put(LOCKFILE, pipeline.write(ctx.require(LOCKFILE), ctx.require(MANIFESTS_SHA)));
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder(shape.planName())
                .stateKeys(LOCKFILE, MANIFESTS_SHA)
                .addTask(parseBuild)
                .addTask(resolve)
                .addTask(lockPlugins)
                .addTask(lockSdk)
                .addTask(write)
                .build();
    }

    /**
     * Wrap {@code observer} so it also drives the bar under a console listener (and the wire when
     * hosted): ticks grow to the solver's real total, and every graph/materialize event advances
     * them.
     */
    private static ResolveObserver barObserver(
            TaskContext ctx,
            ResolveObserver observer,
            AtomicInteger estimate,
            @Nullable BiFunction<String, String, String> coordLabel) {
        return new ResolveObserver() {
            @Override
            public void onTotal(int total) {
                int delta = total - estimate.getAndSet(total);
                if (delta > 0) ctx.updateTicks(delta);
                observer.onTotal(total);
            }

            @Override
            public void onPackage(String module, String version) {
                if (coordLabel != null) ctx.label("Fetched " + coordLabel.apply(module, version));
                ctx.progress(1);
                observer.onPackage(module, version);
            }

            @Override
            public void onPhase(String label) {
                if (label != null && !label.isBlank()) ctx.label(label);
                observer.onPhase(label);
            }

            @Override
            public void onGraphPackage(String module, String version) {
                // Graph phase: advance the bar without implying the jar is on disk yet.
                if (coordLabel != null) {
                    ctx.label("Resolving " + coordLabel.apply(module, version));
                } else if (module != null) {
                    ctx.label("Resolving " + module + (version != null ? ":" + version : ""));
                }
                ctx.progress(1);
                observer.onGraphPackage(module, version);
            }
        };
    }

    // ---- jk update --git ----------------------------------------------------

    /**
     * Outcome of a {@code jk update --git} pass. {@code exitCode == 0} means success and {@code
     * refreshed} counts the git artifacts actually re-pinned; non-zero means the caller should
     * surface {@code error} (a bare, uncolored message — no command prefix) and exit with that code.
     */
    public record GitUpdateOutcome(
            int exitCode, int refreshed, @Nullable String error) {}

    /**
     * {@code jk update --git [<name>]}: re-resolve git dependencies only, in {@code root}'s project
     * and (for a workspace root) each declared module — one dependency by its declared name, or
     * every git dependency when {@code targetLibrary} is {@code null}. Every scope with no matching
     * git dependency is left untouched entirely (its {@code jk-lock.toml} isn't even read).
     */
    public static GitUpdateOutcome updateGitOnly(
            Path dir,
            JkBuild root,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            @Nullable String targetLibrary)
            throws Exception {
        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);
        var scopes = new LinkedHashMap<Path, JkBuild>();
        scopes.put(dir, effectiveRoot);
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                return new GitUpdateOutcome(Exit.CONFIG, 0, e.getMessage());
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                scopes.put(
                        entry.getKey(),
                        WorkspaceMerge.applyToModule(effectiveRoot, entry.getValue(), modules.values()));
            }
        }

        int totalRefreshed = 0;
        for (Map.Entry<Path, JkBuild> scope : scopes.entrySet()) {
            List<Dependency> gitDeps = declaredGitDeps(scope.getValue());
            List<Dependency> targeted = targetLibrary == null
                    ? gitDeps
                    : gitDeps.stream()
                            .filter(d -> d.library().equals(targetLibrary))
                            .toList();
            if (targeted.isEmpty()) continue;

            try {
                totalRefreshed += updateGitOnlyForScope(
                        scope.getKey(), scope.getValue(), cache, repoUrl, features, withDefaultFeatures, targeted);
            } catch (Exception e) {
                return new GitUpdateOutcome(6, 0, e.getMessage());
            }
        }

        if (targetLibrary != null && totalRefreshed == 0) {
            return new GitUpdateOutcome(Exit.CONFIG, 0, "no git dependency named `" + targetLibrary + "` found.");
        }
        return new GitUpdateOutcome(0, totalRefreshed, null);
    }

    /**
     * Re-resolve {@code effective}'s full dependency set (the normal update pass — every git dep
     * accepts upstream movement, no tag-rewrite check), then splice the result against the existing
     * lock so only {@code targeted}'s git artifact(s) actually change; every other artifact, plugin
     * pin and sdk pin keeps its previously-locked value. Returns how many of {@code targeted} were
     * actually refreshed.
     */
    private static int updateGitOnlyForScope(
            Path dir,
            JkBuild effective,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            List<Dependency> targeted)
            throws Exception {
        LockPipeline pipeline = new LockPipeline(
                dir,
                effective,
                cache,
                repoUrl,
                features,
                withDefaultFeatures,
                new LockMode.Update(null),
                JkVersion.VERSION);
        // Digest captured before resolving.
        String manifestsSha = pipeline.manifestsSha();
        Lockfile oldLock = LockPipeline.readIfPresent(dir);
        Lockfile newLock = pipeline.resolve(null, ResolveObserver.NOOP, LockPipeline.Progress.SILENT);

        Set<String> targetKeys = new LinkedHashSet<>();
        for (Dependency d : targeted) targetKeys.add(gitKey(d.gitSource()));

        Map<String, Lockfile.Artifact> oldByName = new LinkedHashMap<>();
        if (oldLock != null) for (Lockfile.Artifact a : oldLock.artifacts()) oldByName.put(a.name(), a);

        List<Lockfile.Artifact> spliced = new ArrayList<>();
        int refreshed = 0;
        for (Lockfile.Artifact a : newLock.artifacts()) {
            boolean isTargeted = a.git() != null
                    && targetKeys.contains(a.git().url() + "|" + a.git().ref());
            if (isTargeted) {
                spliced.add(a);
                refreshed++;
                continue;
            }
            Lockfile.Artifact old = oldByName.get(a.name());
            spliced.add(old != null ? old : a);
        }
        String scalaPin = newLock.scala() != null ? newLock.scala() : (oldLock != null ? oldLock.scala() : null);
        Lockfile finalLock = new Lockfile(
                newLock.version(),
                newLock.generatedBy(),
                newLock.resolutionAlgorithm(),
                newLock.jdk(),
                newLock.graal(),
                newLock.kotlin(),
                scalaPin,
                spliced,
                oldLock != null ? oldLock.plugins() : newLock.plugins(),
                oldLock != null ? oldLock.sdk() : newLock.sdk(),
                List.of(),
                newLock.jkMin(),
                newLock.manifestsSha256(),
                newLock.projectId(),
                // A targeted git refresh does not re-resolve the metadata repository: whatever the
                // previous lock pinned is still what this image should be built against.
                oldLock != null && oldLock.nativeMetadata() != null
                        ? oldLock.nativeMetadata()
                        : newLock.nativeMetadata());
        pipeline.write(finalLock, manifestsSha);
        return refreshed;
    }

    private static String gitKey(GitSource s) {
        return s.canonicalUrl() + "|" + s.ref().token();
    }

    /** Every git-sourced dependency directly declared across all scopes, deduped by library name. */
    private static List<Dependency> declaredGitDeps(JkBuild project) {
        List<Dependency> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isGit() && seen.add(d.library())) out.add(d);
            }
        }
        return out;
    }

    // ---- shared helpers ------------------------------------------------------

    /**
     * Map a failed lock/update plan to its exit code: a failed {@code resolve} step (unsatisfiable
     * deps, unreachable repos) exits 6; anything else is a config problem ({@link Exit#CONFIG}).
     */
    public static int failureExitCode(BuildPlanResult result) {
        boolean resolveFailed = result.steps().stream()
                .filter(p -> p.status() == TaskStatus.FAIL)
                .map(BuildPlanResult.StepReport::name)
                .anyMatch(TaskNames.RESOLVE_DEPS::equals);
        return resolveFailed ? 6 : Exit.CONFIG;
    }

    /**
     * When invoked from a workspace module (not the root), discover the enclosing workspace and apply
     * module context: resolve {@code workspace:} placeholders and filter out sibling-internal dep
     * coords so the solver only sees external Maven coordinates. Returns {@code project} unchanged if
     * it is a workspace root or no enclosing workspace is found.
     */
    public static JkBuild applyWorkspaceContextIfModule(Path dir, JkBuild project) {
        if (project.isWorkspaceRoot()) return project;
        try {
            var rootOpt = WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return Variants.unionDependencies(project);
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve(ManifestPaths.MANIFEST));
            if (!wsRootBuild.isWorkspaceRoot()) return Variants.unionDependencies(project);
            var siblings = WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToModule(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            return Variants.unionDependencies(project);
        }
    }

    /**
     * The single lock scope for {@code entryDir}: workspace root (merged model) or standalone
     * project. A workspace <em>member</em> redirects to its root so any lock entry point — CLI
     * cascade, HTTP/MCP job, auto-lock — resolves the full workspace union and writes the root
     * {@code jk-lock.toml}, never one module's closure over it.
     *
     * @param workspace true when the written lock is the workspace-wide root lock
     * @param moduleCount declared workspace modules behind that lock (0 when standalone)
     */
    public record LockScope(Path lockDir, JkBuild effective, String coord, boolean workspace, int moduleCount) {}

    /** Resolve the {@link LockScope} for {@code entryDir}. Throws like {@link JkBuildParser#parse}. */
    public static LockScope lockScope(Path entryDir) throws IOException {
        // Ensure libs.global.toml exists before short-name expansion (closes race with the engine's
        // background StoreFeedRefresh on first start of a host).
        LibraryRegistrySync.ensurePresent(SessionContext.current().offline());
        JkBuild root = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
        if (root.isWorkspaceRoot()) return workspaceScope(entryDir, root);
        var rootOpt = WorkspaceLocator.findRoot(entryDir);
        if (rootOpt.isPresent()) {
            Path wsRoot = rootOpt.get();
            return workspaceScope(wsRoot, JkBuildParser.parse(wsRoot.resolve(ManifestPaths.MANIFEST)));
        }
        // Standalone: variant dep overlays union here (workspace scopes union inside WorkspaceMerge).
        JkBuild effective = applyWorkspaceContextIfModule(entryDir, root);
        return new LockScope(entryDir, effective, coordLabel(effective, entryDir), false, 0);
    }

    private static LockScope workspaceScope(Path wsRoot, JkBuild rootManifest) throws IOException {
        var modules = WorkspaceLoader.loadModules(wsRoot, rootManifest);
        JkBuild effective = Variants.unionDependencies(WorkspaceMerge.merge(rootManifest, modules.values()));
        return new LockScope(wsRoot, effective, coordLabel(rootManifest, wsRoot), true, modules.size());
    }

    /**
     * Display coordinate for a module: {@code group:artifact} from its project identity, falling
     * back to the directory name.
     */
    public static String coordLabel(JkBuild build, Path dir) {
        try {
            var p = build.project();
            return p.group() + ":" + p.name();
        } catch (Exception e) {
            return dir.getFileName() == null
                    ? dir.toString()
                    : dir.getFileName().toString();
        }
    }

    /**
     * Best-effort ticks estimate for a module's resolve step: the existing lockfile size (re-run)
     * or declared deps × a transitive expansion factor.
     */
    private static int scopeEstimate(JkBuild effective, Path lockFile) {
        // Dual-phase budget (graph + materialize) ≈ 2× packages.
        try {
            int n = LockfileReader.read(lockFile).artifacts().size();
            if (n > 0) return Math.max(10, n * 2);
        } catch (Exception ignored) {
        }
        try {
            int declared = effective.dependencies().byScope().values().stream()
                    .mapToInt(List::size)
                    .sum();
            return Math.max(10, declared * 12 * 2);
        } catch (Exception ignored) {
        }
        return 40;
    }
}
