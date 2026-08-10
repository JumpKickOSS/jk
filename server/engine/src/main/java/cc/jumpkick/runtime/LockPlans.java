// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Resolve → write {@code jk-lock.toml} for {@code jk lock}/{@code jk update}. Progress via plan
 * listeners and {@link ResolveObserver}; diagnostics are plain (client themes). Engine passes
 * {@code coordLabel=null} and streams structured package events.
 */
public final class LockPlans {

    private LockPlans() {}

    /** Cross-step key: the effective (workspace-merged) manifest the resolve step reads. */
    public static final BuildPlanKey<JkBuild> EFFECTIVE = BuildPlanKey.of("effective-build", JkBuild.class);

    /** Cross-step key: the lockfile as it accumulates through resolve → lock-plugins → write. */
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.of("lockfile", Lockfile.class);

    /**
     * Cross-step key: manifests digest captured at parse time — the write step stamps this instead
     * of re-reading live files, so a manifest edited mid-resolution leaves a stale-reading lock
     * (JK-1357).
     */
    public static final BuildPlanKey<String> MANIFESTS_SHA = BuildPlanKey.of("manifests-sha", String.class);

    /**
     * Build the {@code jk lock} plan for one project directory: {@code parse-build} → {@code
     * resolve} (offline-aware, git-source materialization, PubGrub solve, kotlin pin) → {@code
     * lock-plugins} → {@code write-lockfile}. The offline flag is read off the ambient {@link
     * SessionContext} at step-run time, so both the CLI (which installs the session from its
     * global flags) and the engine (which reconstructs it from the wire request) behave alike.
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
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            boolean sources,
            ResolveObserver observer,
            BiFunction<String, String, String> coordLabel) {
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
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            boolean sources,
            boolean conservative,
            ResolveObserver observer,
            BiFunction<String, String, String> coordLabel) {
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        AtomicInteger resolveEstimate = new AtomicInteger(0);

        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.put(MANIFESTS_SHA, cc.jumpkick.lock.LockManifestDigest.compute(dir));
                    ctx.progress(1);
                })
                .build();

        Task resolve = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .label("Resolving")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(() -> {
                    int estimate = scopeEstimate(effective, lockFile);
                    resolveEstimate.set(estimate);
                    return estimate;
                })
                .execute(ctx -> {
                    ctx.label("Resolving");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = JkStores.cas(cache);
                    // --force / Session force: drop process resolve memos before any POM/metadata work.
                    if (SessionContext.current().config().forceOr(false)) {
                        cc.jumpkick.resolve.ResolveProcessCacheControl.clearAll();
                    }
                    if (SessionContext.current().offline() && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(eff, existing, cas);
                            ctx.progress(existing.artifacts().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error(TaskNames.RESOLVE_DEPS, e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    boolean profile = cc.jumpkick.resolve.ResolveProfile.on();
                    if (profile) cc.jumpkick.resolve.ResolveProfile.reset();
                    long prepT0 = profile ? System.nanoTime() : 0L;
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    Lockfile existing = null;
                    if (Files.exists(lockFile)) {
                        try {
                            existing = LockfileReader.read(lockFile);
                        } catch (Exception ignored) {
                            // unreadable lock — resolve fresh
                        }
                    }
                    Map<String, String> lockedShas =
                            existing != null ? GitSourceResolution.lockedImmutableShas(existing) : Map.of();
                    GitSourceResolution.Prepared prep;
                    PathSourceResolution.Prepared pathPrep;
                    try {
                        Path javaHome = JavaHomes.resolveJavaHome(dir);
                        prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas, javaHome, JkVersion.VERSION, lockedShas);
                        pathPrep = PathSourceResolution.prepare(
                                prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
                    } catch (Exception e) {
                        ctx.error(TaskNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                    if (profile) cc.jumpkick.resolve.ResolveProfile.phasePrep(System.nanoTime() - prepT0);
                    RepoGroup repos = pathPrep.repos();
                    // Deliberately no Diagnostics.Palette here — see the class javadoc.
                    LockOrchestrator orchestrator = new LockOrchestrator(repos)
                            .withProjectDir(dir)
                            .withJvmEnvironment(cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(
                                    pathPrep.project(), dir))
                            .withPlatformPolicy(pathPrep.project().build().platformPolicy())
                            .withUnmappedPolicy(pathPrep.project().build().unmappedPolicy());
                    // Wrap the caller's observer so it also drives ctx.label/progress
                    // (the bar under a console listener; wire progress events when hosted).
                    ResolveObserver wrappedObserver = new ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            int delta = total - resolveEstimate.getAndSet(total);
                            if (delta > 0) ctx.updateTicks(delta);
                            observer.onTotal(total);
                        }

                        @Override
                        public void onPackage(String module, String version) {
                            if (coordLabel != null) {
                                ctx.label("Fetched " + coordLabel.apply(module, version));
                            }
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
                            // Graph phase: advance bar without implying the jar is on disk yet.
                            if (coordLabel != null) {
                                ctx.label("Resolving " + coordLabel.apply(module, version));
                            } else if (module != null) {
                                ctx.label("Resolving " + module + (version != null ? ":" + version : ""));
                            }
                            ctx.progress(1);
                            observer.onGraphPackage(module, version);
                        }
                    };
                    try {
                        boolean keepPins = conservative && !sources && existing != null;
                        Lockfile lock;
                        long resolveT0 = profile ? System.nanoTime() : 0L;
                        if (sources) {
                            lock = orchestrator.lockWithSources(
                                    pathPrep.project(),
                                    JkVersion.VERSION,
                                    features,
                                    withDefaultFeatures,
                                    wrappedObserver);
                        } else if (keepPins) {
                            lock = orchestrator.lockConservative(
                                    pathPrep.project(),
                                    existing,
                                    JkVersion.VERSION,
                                    features,
                                    withDefaultFeatures,
                                    wrappedObserver);
                        } else {
                            // Local maven-metadata within TTL first (default 24h) — do not
                            // force-revalidate every jk lock (conditional GETs still 429 Central
                            // on large graphs / back-to-back dogfood). Fresh indexes: jk update
                            // or -F / --force (Session force → MavenMetadataCache).
                            lock = orchestrator.lock(
                                    pathPrep.project(),
                                    JkVersion.VERSION,
                                    features,
                                    withDefaultFeatures,
                                    wrappedObserver);
                        }
                        if (profile) {
                            cc.jumpkick.resolve.ResolveProfile.phaseResolve(System.nanoTime() - resolveT0);
                        }
                        long postT0 = profile ? System.nanoTime() : 0L;
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        String kotlinVersion = keepPins && existing.kotlin() != null
                                ? existing.kotlin()
                                : resolveKotlinVersion(eff, repos);
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                        if (profile) {
                            cc.jumpkick.resolve.ResolveProfile.phasePost(System.nanoTime() - postT0);
                            System.err.println("jk: " + cc.jumpkick.resolve.ResolveProfile.report());
                        }
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
                .ticks(() ->
                        effective.plugins().isEmpty() ? 0 : effective.plugins().size())
                .execute(ctx -> {
                    var decls = effective.plugins();
                    if (decls.isEmpty()) return;
                    ctx.label("lock plugins");
                    Cas cas = JkStores.cas(cache);
                    RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    var entries = new ArrayList<Lockfile.PluginEntry>();
                    for (var pd : decls) {
                        ctx.label("lock " + pd.coordinate());
                        try {
                            String hex;
                            Path jarPath;
                            if (pd.isPathPin()) {
                                Path jar = resolvePluginPath(dir, pd.path());
                                if (!Files.isRegularFile(jar)) {
                                    throw new RuntimeException("plugins." + pd.alias() + " path `" + pd.path()
                                            + "` is not a readable file (" + jar + ")");
                                }
                                hex = cc.jumpkick.util.Hashing.sha256Hex(jar);
                                if (!hex.equals(pd.sha256())) {
                                    throw new RuntimeException("plugins." + pd.alias()
                                            + " sha256 mismatch: declared " + pd.sha256()
                                            + " but file is " + hex
                                            + " (`" + jar + "`)");
                                }
                                jarPath = cas.putFile(jar, hex);
                            } else {
                                var coord = Coordinate.of(pd.group(), pd.name(), pd.version());
                                var fetched = repos.tryFetchArtifact(coord)
                                        .orElseThrow(() -> new RuntimeException(
                                                pd.coordinateWithVersion() + " not found in any repo"));
                                hex = fetched.fetched().sha256();
                                if (!hex.equals(pd.sha256())) {
                                    throw new RuntimeException("plugins." + pd.alias()
                                            + " sha256 mismatch: declared " + pd.sha256()
                                            + " but resolved jar is " + hex
                                            + " (" + pd.coordinateWithVersion() + ")");
                                }
                                jarPath = fetched.fetched().cachePath();
                            }
                            entries.add(new Lockfile.PluginEntry(pd.coordinate(), pd.version(), "sha256:" + hex));
                            try {
                                PluginDescriptorOps.materialize(dir, hex, jarPath);
                            } catch (java.io.IOException e) {
                                ctx.output("note: " + pd.coordinate() + " has no jk-plugin.toml — locked, but"
                                        + " it will not own a jk.toml table");
                            }
                        } catch (Exception e) {
                            ctx.error("plugin", pd.coordinate() + " — " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                        ctx.progress(1);
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withPlugins(entries));
                })
                .build();

        Task lockSdk = Task.builder(TaskNames.LOCK_SDK)
                .kind(TaskKind.IO)
                .requires(TaskNames.LOCK_PLUGINS)
                .ticks(1)
                .execute(ctx -> {
                    // Lockfile pins for every sdk-component a plugin contributes: installed → on-disk
                    // revision; else feed stable revision when reachable.
                    java.util.LinkedHashSet<String> components = new java.util.LinkedHashSet<>();
                    try {
                        for (var sd :
                                cc.jumpkick.plugin.manifest.PluginContributions.stepDependencies(effective, dir)) {
                            if (sd.sdkComponent() != null && !"root".equals(sd.sdkComponent())) {
                                components.add(sd.sdkComponent());
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // no plugin tables / no contributions — nothing to pin
                    }
                    if (components.isEmpty()) return;
                    ctx.label("pin sdk components");
                    var entries = new ArrayList<cc.jumpkick.lock.Lockfile.SdkEntry>();
                    for (String component : components) {
                        String revision = SdkComponents.installedRevision(component);
                        if (revision == null) {
                            try {
                                var sdk = cc.jumpkick.androidsdk.AndroidSdk.resolve();
                                var feedComponent = new cc.jumpkick.androidsdk.AndroidSdkInstaller(sdk)
                                        .feed()
                                        .find(component);
                                if (feedComponent != null) revision = feedComponent.revision();
                            } catch (Exception ignored) {
                                // offline / feed unreachable — leave unpinned rather than guess
                            }
                        }
                        if (revision != null) {
                            entries.add(new cc.jumpkick.lock.Lockfile.SdkEntry(component, revision));
                        }
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withSdk(entries));
                })
                .build();

        Task write = Task.builder(TaskNames.WRITE_LOCKFILE)
                .requires(TaskNames.LOCK_SDK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    Lockfile stamped = cc.jumpkick.lock.LockfileModules.stamp(ctx.require(LOCKFILE), dir);
                    ctx.put(LOCKFILE, stamped);
                    LockfileWriter.write(stamped, lockFile, ctx.require(MANIFESTS_SHA));
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("lock")
                .addTask(parseBuild)
                .addTask(resolve)
                .addTask(lockPlugins)
                .addTask(lockSdk)
                .addTask(write)
                .build();
    }

    /** {@code jk update}: same as {@link #lockBuildPlan} but always resolves fresh. */
    public static BuildPlan updateBuildPlan(
            Path dir, JkBuild effective, Path cache, URI repoUrl, List<String> features, boolean withDefaultFeatures) {
        return updateBuildPlan(dir, effective, cache, repoUrl, features, withDefaultFeatures, null);
    }

    /**
     * As {@link #updateBuildPlan(Path, JkBuild, Path, URI, List, boolean)} with optional CLI
     * platform-policy override ({@code enforced}|{@code floor},.
     */
    public static BuildPlan updateBuildPlan(
            Path dir,
            JkBuild effective,
            Path cache,
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            String platformOverride) {
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        PlatformPolicy policy = effectivePlatformPolicy(effective, platformOverride);

        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.put(MANIFESTS_SHA, cc.jumpkick.lock.LockManifestDigest.compute(dir));
                    ctx.progress(1);
                })
                .build();

        Task resolve = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("re-resolve dependencies");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = JkStores.cas(cache);
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    try {
                        // Git deps: re-materialize at current tip (accept movement).
                        Path javaHome = JavaHomes.resolveJavaHome(dir);
                        GitSourceResolution.Prepared prep =
                                GitSourceResolution.prepare(eff, baseRepos, cas, javaHome, JkVersion.VERSION);
                        PathSourceResolution.Prepared pathPrep = PathSourceResolution.prepare(
                                prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
                        // Float-to-latest needs current indexes; revalidate past TTL (conditional
                        // GET). Normal jk lock stays on the warm disk TTL.
                        Lockfile lock = cc.jumpkick.repo.MavenMetadataCache.withForceRevalidate(
                                () -> new LockOrchestrator(pathPrep.repos())
                                        .withProjectDir(dir)
                                        .withJvmEnvironment(
                                                cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(
                                                        pathPrep.project(), dir))
                                        .withPlatformPolicy(policy)
                                        .withUnmappedPolicy(
                                                pathPrep.project().build().unmappedPolicy())
                                        .lock(pathPrep.project(), JkVersion.VERSION, features, withDefaultFeatures));
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        // jk update floats everything — including the Kotlin compiler pin, which
                        // this plan used to drop from the lock entirely (JK-1371).
                        String kotlinVersion = resolveKotlinVersion(eff, pathPrep.repos());
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                    } catch (Exception e) {
                        ctx.error(TaskNames.RESOLVE_DEPS, e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Task write = Task.builder(TaskNames.WRITE_LOCKFILE)
                .requires(TaskNames.RESOLVE_DEPS)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    Lockfile stamped = cc.jumpkick.lock.LockfileModules.stamp(ctx.require(LOCKFILE), dir);
                    ctx.put(LOCKFILE, stamped);
                    LockfileWriter.write(stamped, lockFile, ctx.require(MANIFESTS_SHA));
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("update")
                .addTask(parseBuild)
                .addTask(resolve)
                .addTask(write)
                .build();
    }

    // ---- jk update --git ----------------------------------------------------

    /**
     * Outcome of a {@code jk update --git} pass. {@code exitCode == 0} means success and {@code
     * refreshed} counts the git artifacts actually re-pinned; non-zero means the caller should
     * surface {@code error} (a bare, uncolored message — no command prefix) and exit with that code.
     */
    public record GitUpdateOutcome(int exitCode, int refreshed, String error) {}

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
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            String targetLibrary)
            throws Exception {
        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);
        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
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
     * Re-resolve {@code effective}'s full dependency set (the normal plan — every git dep
     * accepts upstream movement, no tag-rewrite check), then splice the result against the existing
     * lock so only {@code targeted}'s git artifact(s) actually change; every other artifact keeps
     * its previously-locked value. Returns how many of {@code targeted} were actually refreshed.
     */
    private static int updateGitOnlyForScope(
            Path dir,
            JkBuild effective,
            Path cache,
            URI repoUrl,
            List<String> features,
            boolean withDefaultFeatures,
            List<Dependency> targeted)
            throws Exception {
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        Lockfile oldLock = Files.exists(lockFile) ? LockfileReader.read(lockFile) : null;

        // Digest captured before resolving (JK-1357).
        String manifestsSha = cc.jumpkick.lock.LockManifestDigest.compute(dir);
        Cas cas = JkStores.cas(cache);
        RepoGroup baseRepos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
        Path javaHome = JavaHomes.resolveJavaHome(dir);
        GitSourceResolution.Prepared prep =
                GitSourceResolution.prepare(effective, baseRepos, cas, javaHome, JkVersion.VERSION);
        PathSourceResolution.Prepared pathPrep =
                PathSourceResolution.prepare(prep.project(), prep.repos(), cas, dir, javaHome, JkVersion.VERSION);
        Lockfile newLock = new LockOrchestrator(pathPrep.repos())
                .withProjectDir(dir)
                .withJvmEnvironment(
                        cc.jumpkick.plugin.manifest.PluginContributions.jvmEnvironment(pathPrep.project(), dir))
                .withPlatformPolicy(pathPrep.project().build().platformPolicy())
                .withUnmappedPolicy(pathPrep.project().build().unmappedPolicy())
                .lock(pathPrep.project(), JkVersion.VERSION, features, withDefaultFeatures);
        newLock = GitSourceResolution.stamp(newLock, prep.gitInfoByKey());

        java.util.Set<String> targetKeys = new java.util.LinkedHashSet<>();
        for (Dependency d : targeted) targetKeys.add(gitKey(d.gitSource()));

        Map<String, Lockfile.Artifact> oldByName = new java.util.LinkedHashMap<>();
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
        Lockfile finalLock = new Lockfile(
                newLock.version(),
                newLock.generatedBy(),
                newLock.resolutionAlgorithm(),
                newLock.jdk(),
                newLock.kotlin(),
                spliced,
                oldLock != null ? oldLock.plugins() : newLock.plugins(),
                oldLock != null ? oldLock.sdk() : newLock.sdk(),
                List.of(),
                newLock.jk());
        finalLock = cc.jumpkick.lock.LockfileModules.stamp(finalLock, dir);
        LockfileWriter.write(finalLock, lockFile, manifestsSha);
        return refreshed;
    }

    /**
     * CLI {@code --platform} override wins; else {@code [resolve] platform} from the project
     * (default enforced).
     */
    static PlatformPolicy effectivePlatformPolicy(JkBuild project, String override) {
        if (override != null && !override.isBlank()) {
            return PlatformPolicy.parse(override.trim());
        }
        return project != null && project.build() != null ? project.build().platformPolicy() : PlatformPolicy.ENFORCED;
    }

    private static String gitKey(GitSource s) {
        return s.canonicalUrl() + "|" + s.ref().token();
    }

    /** Every git-sourced dependency directly declared across all scopes, deduped by library name. */
    private static List<Dependency> declaredGitDeps(JkBuild project) {
        List<Dependency> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
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
            if (rootOpt.isEmpty()) return cc.jumpkick.model.Variants.unionDependencies(project);
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return cc.jumpkick.model.Variants.unionDependencies(project);
            var siblings = WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToModule(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            return cc.jumpkick.model.Variants.unionDependencies(project);
        }
    }

    /**
     * The single lock scope for {@code entryDir}: workspace root (merged model) or standalone
     * project. A workspace <em>member</em> redirects to its root so any lock entry point — CLI
     * cascade, HTTP/MCP job — resolves the full workspace union and writes the root
     * {@code jk-lock.toml}, never one module's closure over it.
     */
    public record LockScope(Path lockDir, JkBuild effective, String coord) {}

    /** Resolve the {@link LockScope} for {@code entryDir}. Throws like {@link JkBuildParser#parse}. */
    public static LockScope lockScope(Path entryDir) throws java.io.IOException {
        // Ensure libs.global.toml exists before short-name expansion (closes race with the engine's
        // background StoreFeedRefresh on first start of a host).
        cc.jumpkick.repo.LibraryRegistrySync.ensurePresent(
                SessionContext.current().offline());
        JkBuild root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
        if (root.isWorkspaceRoot()) {
            var modules = WorkspaceLoader.loadModules(entryDir, root);
            return new LockScope(entryDir, WorkspaceMerge.merge(root, modules.values()), coordLabel(root, entryDir));
        }
        var rootOpt = WorkspaceLocator.findRoot(entryDir);
        if (rootOpt.isPresent()) {
            Path wsRoot = rootOpt.get();
            JkBuild rootManifest = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            var modules = WorkspaceLoader.loadModules(wsRoot, rootManifest);
            return new LockScope(
                    wsRoot, WorkspaceMerge.merge(rootManifest, modules.values()), coordLabel(rootManifest, wsRoot));
        }
        JkBuild effective = applyWorkspaceContextIfModule(entryDir, root);
        return new LockScope(entryDir, effective, coordLabel(effective, entryDir));
    }

    /**
     * Display coordinate for a module: {@code group:artifact} from its {@code [project]}, falling
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

    /**
     * Resolve the project's {@code kotlin} version selector to a concrete Kotlin compiler release.
     * Returns {@code null} for a Java project or when resolution can't complete. Shared with
     * {@link LockFlow} and the update plan so every lock-write path stamps the pin (JK-1371).
     */
    static String resolveKotlinVersion(JkBuild effective, RepoGroup repos) {
        if (!effective.project().isKotlin()) return null;
        VersionSelector selector = effective.project().kotlin();
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        Coordinate coord = Coordinate.of("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any");
        List<String> available;
        try {
            available = repos.availableVersions(coord);
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }

    /**
     * Extra diagnosis for the offline miss above: when the mirror <em>does</em> hold this
     * coordinate but under different bytes than the lock pins, "isn't cached" is misleading — the
     * artifact is right there, it simply is not the one the lockfile named. Say so and name the
     * escape hatch, since `jk sync` alone will not resolve a first-write-wins mirror entry
     * (JK-1462; see docs/mirror-verification-decision.md).
     *
     * @return a clause to append to the message, or "" when the mirror has nothing to say
     */
    private static String mirrorMismatchHint(Cas cas, Lockfile.Artifact pkg, String lockedHex) {
        try {
            if (pkg.name().indexOf(':') < 0) return "";
            String repoName = cc.jumpkick.repo.RepoArtifactResolver.repoName(pkg.source());
            if (!cc.jumpkick.repo.RepoArtifactResolver.isNamedRemote(repoName)) return "";
            String m2Path = cc.jumpkick.repo.MavenLayout.artifactPath(pkg.coordinate());
            var store = cc.jumpkick.repo.RepoArtifactStore.forRepoName(cas.root(), repoName);
            String stored = store.storedSha256(m2Path).orElse(null);
            if (stored == null || stored.equalsIgnoreCase(lockedHex)) return "";
            return " (the " + repoName + " mirror holds different bytes for it — sha256 " + shortSha(stored)
                    + " vs the locked " + shortSha(lockedHex)
                    + "; `jk repo refresh " + pkg.coordinate() + "` once online drops the stale entry)";
        } catch (RuntimeException e) {
            return ""; // diagnosis is a nicety — never let it replace the real error
        }
    }

    private static String shortSha(String hex) {
        return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12);
    }

    /**
     * Throw if an existing lockfile can't be honored entirely from the local CAS while offline.
     */
    private static void requireOfflineSatisfiable(JkBuild effective, Lockfile lock, Cas cas) {
        java.util.Set<String> locked = new java.util.HashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Index package key and GA — declared deps use GA; lock rows use g:a:type:classifier.
            locked.add(pkg.name());
            locked.add(pkg.packageKey());
            try {
                if (cc.jumpkick.model.PackageId.isMavenPackageKey(pkg.name())) {
                    locked.add(cc.jumpkick.model.PackageId.parse(pkg.name()).ga());
                }
            } catch (RuntimeException ignored) {
                // non-Maven lock name
            }
        }
        for (var entry : effective.dependencies().byScope().entrySet()) {
            if (entry.getKey() == Scope.PLATFORM) continue;
            for (var dep : entry.getValue()) {
                if (!locked.contains(dep.module())) {
                    throw new IllegalStateException("offline: "
                            + dep.module()
                            + " is declared in jk.toml but not in jk-lock.toml; run `jk lock` online first");
                }
            }
        }
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null) {
                // Nothing to materialize for POM-only rows. Still say so — a checksum-less
                // jar row is how JK-1649 used to hide a missing artifact.
                System.err.println("jk: note: lock row "
                        + pkg.name()
                        + "@"
                        + pkg.version()
                        + " has no checksum — offline check skipped it"
                        + " (POM-only alias, or incomplete lock)");
                continue;
            }
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (!cas.contains(hex)) {
                throw new IllegalStateException("offline: "
                        + pkg.name()
                        + ":"
                        + pkg.version()
                        + " is locked but its artifact isn't cached"
                        + mirrorMismatchHint(cas, pkg, hex)
                        + "; run `jk sync` online first");
            }
        }
    }

    /** Resolve a plugin path relative to the project dir (or absolute). */
    private static Path resolvePluginPath(Path projectDir, String raw) {
        Path p = Path.of(raw);
        if (p.isAbsolute()) return p.normalize();
        return projectDir.resolve(p).normalize();
    }
}
