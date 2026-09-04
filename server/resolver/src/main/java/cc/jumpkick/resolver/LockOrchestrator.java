// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * End-to-end lock: {@link JkBuild} → three independent scope solves (main / test / processor) →
 * downloads → {@link Lockfile}. Same module at different versions gets multiple artifact rows;
 * classpaths filter by scope.
 */
public final class LockOrchestrator {

    private static final List<Scope> MAIN_SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.DEV);

    private static final List<Scope> TEST_SCOPES = List.of(Scope.TEST, Scope.TEST_DEV);

    private static final List<Scope> PROCESSOR_SCOPES = List.of(Scope.PROCESSOR);

    private final RepoGroup repos;
    private final Resolver resolverOverride;
    private LockProgress.Timings timings = LockTimings::record;

    /**
     * {@code org.gradle.jvm.environment} for KMP variant selection ({@code "android"} for Android
     * projects, else standard-jvm). Set via {@link #withJvmEnvironment}.
     */
    private String jvmEnvironment = "standard-jvm";

    /** Consuming project directory — resolves path= deps for cross-package features. */
    private Path projectDir;

    private LanguageRuntimeInject.ToolVersions toolVersions = LanguageRuntimeInject.ToolVersions.NONE;

    /** BOM pin policy; default {@link PlatformPolicy#ENFORCED}. */
    private PlatformPolicy platformPolicy = PlatformPolicy.ENFORCED;

    /** Unmapped-fill policy; default {@link cc.jumpkick.model.UnmappedPolicy#MEDIATE}. */
    private UnmappedPolicy unmappedPolicy = UnmappedPolicy.MEDIATE;

    /** The compiler versions this lock pins; the injected stdlibs follow them exactly. */
    public LockOrchestrator withToolVersions(LanguageRuntimeInject.ToolVersions tools) {
        this.toolVersions = tools == null ? LanguageRuntimeInject.ToolVersions.NONE : tools;
        return this;
    }

    public LockOrchestrator withProjectDir(Path projectDir) {
        this.projectDir = projectDir;
        return this;
    }

    /** Platform BOM edge policy (see {@link PlatformPolicy}). */
    public LockOrchestrator withPlatformPolicy(PlatformPolicy policy) {
        if (policy != null) this.platformPolicy = policy;
        return this;
    }

    /** Unmapped-fill policy under a platform (see {@link cc.jumpkick.model.UnmappedPolicy}). */
    public LockOrchestrator withUnmappedPolicy(UnmappedPolicy policy) {
        if (policy != null) this.unmappedPolicy = policy;
        return this;
    }

    public PlatformPolicy platformPolicy() {
        return platformPolicy;
    }

    public LockOrchestrator(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public LockOrchestrator(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolverOverride = null;
    }

    /** Test seam: lets tests inject a different resolver (e.g. NaiveResolver). */
    LockOrchestrator(RepoGroup repos, Resolver resolver) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolverOverride = Objects.requireNonNull(resolver, "resolver");
    }

    /** Test seam: where phase timings go instead of the user's state directory. */
    LockOrchestrator withTimings(LockProgress.Timings timings) {
        this.timings = Objects.requireNonNull(timings, "timings");
        return this;
    }

    /** Select KMP platform variants for {@code env} ({@code "android"} / {@code "standard-jvm"}). */
    public LockOrchestrator withJvmEnvironment(String env) {
        if (env != null && !env.isBlank()) this.jvmEnvironment = env;
        return this;
    }

    private PubGrubResolver buildResolver(
            RepoGroup repos,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        return new PubGrubResolver(repos, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);
    }

    /** Lock with the project's default feature selection. */
    public Lockfile lock(JkBuild project, String jkVersion) throws IOException, InterruptedException {
        return lock(project, jkVersion, List.of(), true, ResolveObserver.NOOP);
    }

    /**
     * Lock and additionally attempt to resolve the {@code -sources.jar} for every Maven package,
     * populating {@link Lockfile.Artifact#sourcesChecksum} when found. Sources that return 404 are
     * silently skipped — not all packages publish sources.
     */
    public Lockfile lockWithSources(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        Lockfile base = lock(project, jkVersion, featuresRequested, withDefaults, observer, Map.of());
        return attachSources(base);
    }

    public Lockfile lock(JkBuild project, String jkVersion, Collection<String> featuresRequested, boolean withDefaults)
            throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, ResolveObserver.NOOP);
    }

    /**
     * Lock with an explicit feature selection and a progress observer. {@link
     * ResolveObserver#onTotal} fires once after the solver returns the full decision map; {@link
     * ResolveObserver#onPackage} fires once per package as each artifact is fetched and recorded.
     */
    public Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, Map.of());
    }

    /**
     * Conservative re-lock: same as {@link #lock} but seeds the solver with the exact versions from
     * {@code existing} as <em>soft preferences</em>. The solver selects each locked version first; if
     * a new or changed dep's constraint rules it out, the solver backtracks to the next candidate
     * automatically. Only versions that genuinely conflict with new constraints are bumped
     * everything else stays pinned.
     */
    public Lockfile lockConservative(
            JkBuild project,
            Lockfile existing,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        Map<String, String> prefs = new HashMap<>();
        for (Lockfile.Artifact pkg : existing.artifacts()) {
            // Prefer main-scoped rows over test-only / processor-only duals.
            boolean specializedOnly =
                    pkg.scopes().stream().allMatch(s -> s == Scope.PROCESSOR || s == Scope.TEST || s == Scope.TEST_DEV)
                            && pkg.scopes().stream().noneMatch(MAIN_SCOPES::contains);
            String key = pkg.packageKey();
            String ga = PackageId.isMavenPackageKey(pkg.name())
                    ? PackageId.parse(pkg.name()).ga()
                    : pkg.name();
            if (specializedOnly) {
                prefs.putIfAbsent(key, pkg.version());
                prefs.putIfAbsent(ga, pkg.version());
            } else {
                prefs.put(key, pkg.version());
                prefs.put(ga, pkg.version());
            }
        }
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, prefs);
    }

    private Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer,
            Map<String, String> lockedVersionPrefs)
            throws IOException, InterruptedException {
        LockProgress progress = new LockProgress(observer, timings);

        LockRoots.Declared declared = LockRoots.partition(project, featuresRequested, withDefaults, projectDir);
        Map<String, List<String>> activatedFeatures = declared.activatedFeatures();
        // one POM builder for BOM load + all scope solves + toArtifact packaging probes.
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        PlatformConstraints constraints = PlatformConstraints.collect(project, repos, pomBuilder);
        Map<String, String> bomConstraints = constraints.versions();

        // Language runtimes must be lock deps so package-jar / boot-jar nest them.
        // Engine classpath injection alone is not enough for standalone `java -jar`.
        // Runs AFTER BOM collection: a platform that manages the runtime (grails-bom's groovy)
        // owns its version — the inject must not smuggle the scaffold default past it.
        Set<String> injected =
                LanguageRuntimeInject.inject(project, projectDir, bomConstraints, declared.main(), toolVersions);

        LockRoots.Roots roots = constraints.apply(declared.split(), injected);
        List<Dependency> fileDeps = roots.fileDeps();
        List<Dependency> mainRoots = roots.main();
        List<Dependency> testRoots = roots.test();
        List<Dependency> processorRoots = roots.processor();

        KmpRedirects kmp = new KmpRedirects(repos, jvmEnvironment);
        // Shared package source across main/test/processor so version/deps caches survive scope splits.
        MavenPackageSource sharedSource = resolverOverride != null
                ? null
                : new MavenPackageSource(
                        repos, pomBuilder, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);

        progress.graphPhase(roots.declaredCount());
        Resolution mainResolution =
                resolveGroup(mainRoots, bomConstraints, lockedVersionPrefs, kmp, sharedSource, pomBuilder, progress);
        progress.noteGraph(mainResolution);
        Map<String, String> testPrefs = new HashMap<>(lockedVersionPrefs);
        putVersions(testPrefs, mainResolution);
        Resolution testResolution =
                resolveGroup(testRoots, bomConstraints, testPrefs, kmp, sharedSource, pomBuilder, progress);
        progress.noteGraph(testResolution);
        Map<String, String> processorPrefs = new HashMap<>(lockedVersionPrefs);
        putVersions(processorPrefs, mainResolution);
        putVersions(processorPrefs, testResolution);
        Resolution processorResolution =
                resolveGroup(processorRoots, bomConstraints, processorPrefs, kmp, sharedSource, pomBuilder, progress);
        progress.noteGraph(processorResolution);

        progress.materializePhase(progress.graphPackages() + fileDeps.size());

        Map<String, EnumSet<Scope>> mainTags = tagScopes(project, mainResolution, MAIN_SCOPES, false);
        Map<String, EnumSet<Scope>> testTags = tagScopes(project, testResolution, TEST_SCOPES, true);
        Map<String, EnumSet<Scope>> processorTags = tagScopes(project, processorResolution, PROCESSOR_SCOPES, false);

        MavenRepo first = repos.repos().getFirst();
        String fallbackSource = first.name() + "+" + first.baseUrl();

        List<Lockfile.Artifact> packages = new ArrayList<>();
        // module@version → mutable tag set while merging graphs
        Map<String, EnumSet<Scope>> tagsByKey = new LinkedHashMap<>();
        Map<String, Resolution.ResolvedModule> modByKey = new LinkedHashMap<>();

        mergeGraph(mainResolution, mainTags, Scope.MAIN, tagsByKey, modByKey);
        mergeGraph(testResolution, testTags, Scope.TEST, tagsByKey, modByKey);
        mergeGraph(processorResolution, processorTags, Scope.PROCESSOR, tagsByKey, modByKey);

        ArtifactMaterializer materializer = new ArtifactMaterializer(
                (mod, tags, abort) ->
                        toArtifact(mod, tags, kmp, pomBuilder, fallbackSource, constraints, activatedFeatures, abort),
                progress);
        packages.addAll(materializer.materialize(new ArrayList<>(modByKey.entrySet()), tagsByKey));

        for (Dependency dep : fileDeps) {
            String version = dep.version() instanceof VersionSelector.Exact ex
                    ? ex.version()
                    : dep.version().raw();
            progress.materialized(dep.module(), version);
            EnumSet<Scope> tags = EnumSet.noneOf(Scope.class);
            for (Scope scope : LockRoots.SCOPES) {
                for (Dependency d : project.dependencies().of(scope)) {
                    if (d.isFile() && d.module().equals(dep.module())) tags.add(scope);
                }
            }
            if (tags.isEmpty()) tags.add(Scope.MAIN);
            packages.add(new Lockfile.Artifact(
                    dep.module(),
                    version,
                    RepoArtifactResolver.JK_LOCAL,
                    "sha256:" + dep.sha256(),
                    null,
                    new ArrayList<>(tags),
                    List.of(),
                    null));
        }
        progress.finished(packages.size());
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk " + jkVersion, Lockfile.RESOLUTION_ALGORITHM, packages);
    }

    private static void putVersions(Map<String, String> prefs, Resolution resolution) {
        for (var e : resolution.modules().entrySet()) {
            prefs.putIfAbsent(e.getKey(), e.getValue().version());
        }
    }

    private Resolution resolveGroup(
            List<Dependency> roots,
            Map<String, String> bomConstraints,
            Map<String, String> prefs,
            KmpRedirects kmp,
            MavenPackageSource sharedSource,
            EffectivePomBuilder sharedPomBuilder,
            LockProgress progress)
            throws IOException, InterruptedException {
        if (roots.isEmpty()) return new Resolution(Map.of());
        if (resolverOverride != null) return resolverOverride.resolve(roots);
        if (sharedSource != null && sharedPomBuilder != null) {
            sharedSource.setLockedVersionPrefs(prefs);
            sharedSource.setSnapshotPackages(snapshotModules(roots));
            // exclusion state is per-graph; main's clean paths must not bleed into
            // the test/processor solves.
            sharedSource.resetSolveScopedState();
            return new PubGrubResolver(sharedSource, sharedPomBuilder, kmp)
                    .withOnDecision(progress::graphPackage)
                    .resolve(roots);
        }
        return buildResolver(repos, bomConstraints, prefs, kmp)
                .withOnDecision(progress::graphPackage)
                .resolve(roots);
    }

    /**
     * Fold a graph's modules into the multi-row lock map. Same module@version accumulates scopes;
     * a different version of the same module becomes a separate row with only this graph's scopes.
     */
    private static void mergeGraph(
            Resolution resolution,
            Map<String, EnumSet<Scope>> graphTags,
            Scope defaultScope,
            Map<String, EnumSet<Scope>> tagsByKey,
            Map<String, Resolution.ResolvedModule> modByKey) {
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            EnumSet<Scope> tags = graphTags.getOrDefault(mod.module(), EnumSet.of(defaultScope));
            if (tags.isEmpty()) tags = EnumSet.of(defaultScope);
            String key = mod.module() + "@" + mod.version();
            EnumSet<Scope> existing = tagsByKey.get(key);
            if (existing != null) {
                existing.addAll(tags);
            } else {
                tagsByKey.put(key, EnumSet.copyOf(tags));
                modByKey.put(key, mod);
            }
        }
    }

    private Map<String, EnumSet<Scope>> tagScopes(
            JkBuild project, Resolution resolution, List<Scope> scopes, boolean includeJunitSeeds) {
        Map<String, EnumSet<Scope>> tagsByModule = new HashMap<>();
        if (resolution.modules().isEmpty()) return tagsByModule;
        for (Scope scope : scopes) {
            Set<String> rootModules = new HashSet<>();
            for (Dependency d : project.dependencies().of(scope)) {
                // packageKey: kind=tests → g:a:test-jar:tests; else g:a:jar:
                rootModules.add(d.packageKey());
            }
            if (includeJunitSeeds && scope == Scope.TEST) {
                rootModules.add(LockRoots.JUNIT_LAUNCHER.packageKey());
                if (project.dependencies().of(Scope.TEST).isEmpty()) {
                    rootModules.add(LockRoots.JUNIT_JUPITER.packageKey());
                }
            }
            if (rootModules.isEmpty()) continue;
            for (String module : reachableFrom(rootModules, resolution)) {
                tagsByModule
                        .computeIfAbsent(module, k -> EnumSet.noneOf(Scope.class))
                        .add(scope);
            }
        }
        return tagsByModule;
    }

    private Lockfile.Artifact toArtifact(
            Resolution.ResolvedModule mod,
            EnumSet<Scope> tags,
            KmpRedirects kmp,
            EffectivePomBuilder pomBuilder,
            String fallbackSource,
            PlatformConstraints constraints,
            Map<String, List<String>> activatedFeatures,
            BooleanSupplier abort)
            throws IOException, InterruptedException {
        Coordinate coord = mod.coordinate();
        boolean kmpAlias = kmp.selectionFor(mod.module(), mod.version()).isPresent();

        String packageName = mod.module();
        String artifactFile = null;
        // only probe packaging when the solver package type is not already a plain jar.
        // Building EffectivePom for every package on materialize dominated warm re-lock wall time
        // (hundreds of POM expansions for Quarkus-sized graphs).
        try {
            PackageId id = PackageId.isMavenPackageKey(mod.module()) ? PackageId.parse(mod.module()) : null;
            boolean maybeAar = id != null && "aar".equals(id.type());
            if (!kmpAlias && maybeAar && "aar".equals(pomBuilder.build(coord).packaging())) {
                coord = new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "aar");
                packageName =
                        PackageId.of(coord.group(), coord.artifact(), "aar", "").key();
                artifactFile = coord.artifact() + "-" + coord.version() + ".aar";
            }
        } catch (Exception ignored) {
            // no POM / unparseable
        }

        String source = fallbackSource;
        String checksum = null;
        RepoGroup.RepoFetched hit =
                kmpAlias ? null : repos.tryFetchArtifact(coord, abort).orElse(null);
        if (hit == null
                && !kmpAlias
                && (coord.type() == null || "jar".equals(coord.type()))
                && (coord.classifier() == null || coord.classifier().isEmpty())) {
            // Jar miss for a bare-GA dep whose POM packaging is aar: probe packaging
            // only on miss — the warm path stays probe-free — and rewrite to .aar
            // instead of silently writing a checksum-less row.
            try {
                if ("aar".equals(pomBuilder.build(coord).packaging())) {
                    coord = new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "aar");
                    packageName = PackageId.of(coord.group(), coord.artifact(), "aar", "")
                            .key();
                    artifactFile = coord.artifact() + "-" + coord.version() + ".aar";
                    hit = repos.tryFetchArtifact(coord, abort).orElse(null);
                }
            } catch (Exception ignored) {
                // no POM / unparseable — keep the jar coordinate
            }
        }
        if (hit != null) {
            source = hit.repo().name() + "+" + hit.repo().baseUrl();
            checksum = "sha256:" + hit.fetched().sha256();
        } else if (!kmpAlias && !isPomOnlyPackage(coord, pomBuilder)) {
            // a resolved package whose artifact 404s must not land as a checksum-less
            // lock row that ClasspathResolver silently drops. KMP aliases and packaging=pom
            // (BOMs / aggregators) legitimately have no file; everything else fails the lock.
            throw unfetchableArtifact(coord, fallbackSource);
        }

        if (tags.isEmpty()) tags = EnumSet.of(Scope.MAIN);

        String ga = PackageId.isMavenPackageKey(mod.module())
                ? PackageId.parse(mod.module()).ga()
                : mod.module();
        String pinnedBy = constraints.pinnedBy(ga, mod.version());
        // Record activated cross-package features on the library row when present.
        List<String> feat = activatedFeatures.get(ga);
        if (feat == null) feat = activatedFeatures.get(mod.module());
        if (feat != null && !feat.isEmpty() && pinnedBy == null) {
            pinnedBy = "features:" + String.join(",", feat);
        }

        return new Lockfile.Artifact(
                packageName, // full package key (g:a:type:classifier)
                mod.version(),
                source,
                checksum,
                artifactFile,
                new ArrayList<>(tags),
                mod.deps(),
                pinnedBy);
    }

    /**
     * True when this package is not expected to publish a primary artifact: coordinate type
     * {@code pom}, or POM {@code packaging=pom} (BOM / aggregator).
     */
    private static boolean isPomOnlyPackage(Coordinate coord, EffectivePomBuilder pomBuilder) {
        if (coord.type() != null && "pom".equalsIgnoreCase(coord.type())) {
            return true;
        }
        try {
            return "pom".equalsIgnoreCase(pomBuilder.build(coord).packaging());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Fail lock when a package resolved to a version but no repo served its artifact.
     * Names the coordinate, the Maven layout path tried, and the repositories consulted.
     */
    private IllegalStateException unfetchableArtifact(Coordinate coord, String fallbackSource) {
        String rel = MavenLayout.artifactPath(coord);
        StringBuilder reposTried = new StringBuilder();
        for (MavenRepo r : repos.repos()) {
            if (reposTried.length() > 0) reposTried.append(", ");
            String base = r.baseUrl().toString();
            if (!base.endsWith("/")) base = base + "/";
            reposTried.append(r.name()).append('+').append(base).append(rel);
        }
        if (reposTried.length() == 0) {
            reposTried.append(fallbackSource).append('/').append(rel);
        }
        String display = coord.group() + ":" + coord.artifact() + ":" + coord.version();
        if (coord.type() != null && !coord.type().isBlank() && !"jar".equals(coord.type())) {
            display = display + " type=" + coord.type();
        }
        if (coord.classifier() != null && !coord.classifier().isEmpty()) {
            display = display + " classifier=" + coord.classifier();
        }
        return new IllegalStateException("could not fetch artifact "
                + display
                + " at "
                + rel
                + " (tried: "
                + reposTried
                + ") — the POM resolved but the artifact is missing; check the coordinate and repositories");
    }

    /**
     * The {@code group:artifact} keys among {@code roots} that were declared {@code snapshot} — the
     * only selector that opts into pre-releases.
     */
    private static Set<String> snapshotModules(List<Dependency> roots) {
        Set<String> out = new LinkedHashSet<>();
        for (Dependency d : roots) {
            if (d.version() instanceof VersionSelector.Snapshot) out.add(d.module());
        }
        return out;
    }

    /**
     * Try to fetch {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum} populated where sources exist. Packages that
     * return 404, have a non-maven source, or already have a sources checksum are left unchanged.
     */
    public Lockfile attachSources(Lockfile lock) throws InterruptedException {
        List<Lockfile.Artifact> updated = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (!pkg.source().contains("maven") && !pkg.source().startsWith(RepositorySpec.CENTRAL)
                    || pkg.sourcesChecksum() != null) {
                updated.add(pkg);
                continue;
            }
            if (pkg.name().indexOf(':') < 0) {
                updated.add(pkg);
                continue;
            }
            Coordinate sourcesCoord =
                    new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
            try {
                RepoGroup.RepoFetched hit = repos.tryFetchArtifact(sourcesCoord).orElse(null);
                if (hit != null) {
                    updated.add(new Lockfile.Artifact(
                            pkg.name(),
                            pkg.version(),
                            pkg.source(),
                            pkg.checksum(),
                            pkg.path(),
                            pkg.scopes(),
                            pkg.deps(),
                            pkg.pinnedBy(),
                            pkg.git(),
                            "sha256:" + hit.fetched().sha256()));
                    continue;
                }
            } catch (Exception ignored) {
                /* sources not available for this package */
            }
            updated.add(pkg);
        }
        return lock.withArtifacts(updated);
    }

    /** BFS through the resolved graph starting from {@code roots}. */
    private static Set<String> reachableFrom(Set<String> roots, Resolution resolution) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String module = queue.poll();
            if (!visited.add(module)) continue;
            Resolution.ResolvedModule resolved = resolution.modules().get(module);
            if (resolved == null) continue;
            for (String depRef : resolved.deps()) {
                int at = depRef.indexOf('@');
                queue.add(at > 0 ? depRef.substring(0, at) : depRef);
            }
        }
        return visited;
    }
}
