// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.JkThreads;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * End-to-end lock: {@link JkBuild} → three independent scope solves (main / test / processor) →
 * downloads → {@link Lockfile}. Same module at different versions gets multiple artifact rows;
 * classpaths filter by scope.
 */
public final class LockOrchestrator {

    private static final List<Scope> SCOPES = List.of(
            Scope.EXPORT,
            Scope.MAIN,
            Scope.RUNTIME,
            Scope.PROVIDED,
            Scope.TEST,
            Scope.PROCESSOR,
            Scope.DEV,
            Scope.TEST_DEV);

    private static final List<Scope> MAIN_SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.DEV);

    private static final List<Scope> TEST_SCOPES = List.of(Scope.TEST, Scope.TEST_DEV);

    private static final List<Scope> PROCESSOR_SCOPES = List.of(Scope.PROCESSOR);

    private enum GraphGroup {
        MAIN,
        TEST,
        PROCESSOR
    }

    /**
     * jk test infrastructure: always injected into the TEST classpath via {@code putIfAbsent} so
     * {@code jk test} (which forks {@code jk-test-runner} over the JUnit Platform Launcher API)
     * works regardless of which test framework the user chose.
     */
    private static final Dependency JUNIT_LAUNCHER =
            new Dependency("org.junit.platform:junit-platform-launcher", VersionSelector.parse("latest"));

    /**
     * Passive JUnit 5 default: injected only when the user declared no {@code [test-dependencies]}
     * section, so that a bare project gets a working test framework out of the box. Once the user
     * owns the section — even if they don't list JUnit — jk leaves the framework choice to them.
     *
     * <p>Declared as {@code latest}: {@code jk lock} pins today's latest stable release (reproducible
     * builds), and {@code jk update} advances it — "jk defaults to the latest stable JUnit" stays
     * evergreen without manual bumps.
     */
    private static final Dependency JUNIT_JUPITER =
            new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("latest"));

    private final RepoGroup repos;
    private final Resolver resolverOverride;

    /**
     * {@code org.gradle.jvm.environment} for KMP variant selection ({@code "android"} for Android
     * projects, else standard-jvm). Set via {@link #withJvmEnvironment}.
     */
    private String jvmEnvironment = "standard-jvm";

    /** Consuming project directory — resolves path= deps for cross-package features. */
    private Path projectDir;

    private cc.jumpkick.resolver.pubgrub.Diagnostics.Palette diagnosticPalette;

    /** BOM pin policy; default {@link PlatformPolicy#ENFORCED}. */
    private PlatformPolicy platformPolicy = PlatformPolicy.ENFORCED;

    /** Unmapped-fill policy; default {@link cc.jumpkick.model.UnmappedPolicy#MEDIATE}. */
    private cc.jumpkick.model.UnmappedPolicy unmappedPolicy = cc.jumpkick.model.UnmappedPolicy.MEDIATE;

    /** Directory of the consuming {@code jk.toml} (path= feature expansion). */
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
    public LockOrchestrator withUnmappedPolicy(cc.jumpkick.model.UnmappedPolicy policy) {
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

    /** Select KMP platform variants for {@code env} ({@code "android"} / {@code "standard-jvm"}). */
    public LockOrchestrator withJvmEnvironment(String env) {
        if (env != null && !env.isBlank()) this.jvmEnvironment = env;
        return this;
    }

    public LockOrchestrator withDiagnosticPalette(cc.jumpkick.resolver.pubgrub.Diagnostics.Palette palette) {
        this.diagnosticPalette = palette;
        return this;
    }

    private PubGrubResolver buildResolver(
            cc.jumpkick.repo.RepoGroup repos,
            java.util.Map<String, String> bomConstraints,
            java.util.Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        PubGrubResolver r =
                new PubGrubResolver(repos, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);
        if (diagnosticPalette != null) r.palette = diagnosticPalette;
        return r;
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

        Set<String> activated = project.features().activate(new LinkedHashSet<>(featuresRequested), withDefaults);

        // Partition declared deps into main / test / processor (R5 + test isolation).
        LinkedHashMap<String, Dependency> mainDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> testDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> processorDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> optionalByLib = new LinkedHashMap<>();
        Map<String, Scope> optionalScopeByLib = new HashMap<>();
        for (Scope scope : SCOPES) {
            if (scope == Scope.PLATFORM) continue;
            for (Dependency dep : project.dependencies().of(scope)) {
                if (dep.optional()) {
                    optionalByLib.putIfAbsent(dep.library(), dep);
                    optionalScopeByLib.putIfAbsent(dep.library(), scope);
                } else {
                    switch (graphGroup(scope)) {
                        case PROCESSOR -> processorDeduped.putIfAbsent(dep.module(), dep);
                        case TEST -> testDeduped.putIfAbsent(dep.module(), dep);
                        case MAIN -> mainDeduped.putIfAbsent(dep.module(), dep);
                    }
                }
            }
        }
        for (String depName : project.features().requestedDepNames(activated)) {
            Dependency opt = optionalByLib.get(depName);
            if (opt == null) {
                throw new IllegalArgumentException("feature dependency '"
                        + depName
                        + "' is not a declared optional dependency"
                        + " — declare it under [dependencies.*] with `optional = true`");
            }
            Scope optScope = optionalScopeByLib.getOrDefault(depName, Scope.MAIN);
            switch (graphGroup(optScope)) {
                case PROCESSOR -> processorDeduped.putIfAbsent(opt.module(), opt);
                case TEST -> testDeduped.putIfAbsent(opt.module(), opt);
                case MAIN -> mainDeduped.putIfAbsent(opt.module(), opt);
            }
        }
        // Cross-package features on path= libraries: pull their optional deps.
        CrossPackageFeatures.Result cross = CrossPackageFeatures.expand(projectDir, mainDeduped.values());
        this.crossPackageActivatedFeatures = cross.activatedFeaturesByModule();
        for (Dependency extra : cross.extrasList()) {
            mainDeduped.putIfAbsent(extra.module(), extra);
        }
        // junit infrastructure rides the test graph only.
        testDeduped.putIfAbsent(JUNIT_LAUNCHER.module(), JUNIT_LAUNCHER);
        if (project.dependencies().of(Scope.TEST).isEmpty()) {
            testDeduped.putIfAbsent(JUNIT_JUPITER.module(), JUNIT_JUPITER);
        }
        Map<String, String> bomConstraints = new LinkedHashMap<>();
        Map<String, String> constraintProvenance = new LinkedHashMap<>();
        // one POM builder for BOM load + all scope solves + toArtifact packaging probes.
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        collectBomConstraints(project, pomBuilder, bomConstraints, constraintProvenance);

        // Language runtimes must be lock deps so package-jar / boot-jar nest them.
        // Engine classpath injection alone is not enough for standalone `java -jar`.
        // Runs AFTER BOM collection: a platform that manages the runtime (grails-bom's groovy)
        // owns its version — the inject must not smuggle the scaffold default past it.
        Set<String> injected = injectLanguageRuntimes(project, projectDir, bomConstraints, mainDeduped);

        List<Dependency> fileDeps = new ArrayList<>();
        List<Dependency> mainDeclared = splitFile(mainDeduped, fileDeps);
        List<Dependency> testDeclared = splitFile(testDeduped, fileDeps);
        List<Dependency> processorDeclared = splitFile(processorDeduped, fileDeps);

        stripBomForExactRoots(mainDeclared, bomConstraints, constraintProvenance, injected);
        stripBomForExactRoots(testDeclared, bomConstraints, constraintProvenance, injected);
        stripBomForExactRoots(processorDeclared, bomConstraints, constraintProvenance, injected);

        List<Dependency> mainRoots = materializePlatformManaged(mainDeclared, bomConstraints);
        List<Dependency> testRoots = materializePlatformManaged(testDeclared, bomConstraints);
        List<Dependency> processorRoots = materializePlatformManaged(processorDeclared, bomConstraints);

        KmpRedirects kmp = new KmpRedirects(repos, jvmEnvironment);
        // Shared package source across main/test/processor so version/deps caches survive scope splits.
        MavenPackageSource sharedSource = resolverOverride != null
                ? null
                : new MavenPackageSource(
                        repos, pomBuilder, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);

        // Progress budget: graph phase + materialize phase (≈2× package count). Grow estimate as we go.
        int declared = mainRoots.size() + testRoots.size() + processorRoots.size() + fileDeps.size();
        int estimate = Math.max(10, declared * 12);
        observer.onTotal(estimate * 2);
        observer.onPhase("Resolving dependency graph…");

        // live graph ticks during PubGrub decisions (not only post-scope).
        Set<String> graphSeen = new LinkedHashSet<>();
        Resolution mainResolution = resolveGroup(
                mainRoots,
                bomConstraints,
                lockedVersionPrefs,
                kmp,
                sharedSource,
                pomBuilder,
                observer,
                graphSeen,
                estimate);
        noteGraph(observer, mainResolution, graphSeen, estimate);
        Map<String, String> testPrefs = new HashMap<>(lockedVersionPrefs);
        putVersions(testPrefs, mainResolution);
        Resolution testResolution = resolveGroup(
                testRoots, bomConstraints, testPrefs, kmp, sharedSource, pomBuilder, observer, graphSeen, estimate);
        noteGraph(observer, testResolution, graphSeen, estimate);
        Map<String, String> processorPrefs = new HashMap<>(lockedVersionPrefs);
        putVersions(processorPrefs, mainResolution);
        putVersions(processorPrefs, testResolution);
        Resolution processorResolution = resolveGroup(
                processorRoots,
                bomConstraints,
                processorPrefs,
                kmp,
                sharedSource,
                pomBuilder,
                observer,
                graphSeen,
                estimate);
        noteGraph(observer, processorResolution, graphSeen, estimate);

        int uniquePackages = graphSeen.size() + fileDeps.size();
        // Exact remaining budget for jar materialization (+ any under-estimated graph ticks).
        observer.onTotal(Math.max(estimate * 2, graphSeen.size() + uniquePackages));
        observer.onPhase("Downloading " + uniquePackages + " artifacts…");

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

        // Parallel jar materialize (io pool). Progress ticks on *completion* order
        // via a queue drained on this thread so wedge/UI stays single-threaded; lock rows are
        // still assembled in declaration order.
        // no HostRateLimiter around toArtifact itself — warm re-locks serve
        // immutable GAVs from the local mirror (no HTTP), and capping those to 6 concurrent
        // turned a ~1s CAS walk into multi-minute wall time. The per-host cap lives inside
        // MavenRepo.fetch around the network leg only, so cold-lock fan-out stays polite.
        List<Map.Entry<String, Resolution.ResolvedModule>> ordered = new ArrayList<>(modByKey.entrySet());
        int n = ordered.size();
        Lockfile.Artifact[] arts = new Lockfile.Artifact[n];
        BlockingQueue<MaterializeDone> doneQ = new LinkedBlockingQueue<>();
        // First failure wins: tasks still waiting on a permit/queue skip their download instead
        // of hammering the host for a lock that is already dead.
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            var e = ordered.get(i);
            EnumSet<Scope> tags = tagsByKey.get(e.getKey());
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    if (failed.get()) {
                                        throw new CompletionException(new IOException("lock already failed — skipped"));
                                    }
                                    return toArtifact(
                                            e.getValue(),
                                            tags,
                                            kmp,
                                            pomBuilder,
                                            fallbackSource,
                                            bomConstraints,
                                            constraintProvenance,
                                            ResolveObserver.NOOP);
                                } catch (IOException | InterruptedException ex) {
                                    throw new CompletionException(ex);
                                }
                            },
                            JkThreads.io())
                    .whenComplete((art, ex) -> {
                        if (ex != null) {
                            failed.set(true);
                            doneQ.offer(MaterializeDone.fail(ex));
                        } else {
                            var mod = e.getValue();
                            doneQ.offer(MaterializeDone.ok(idx, art, displayModule(mod.module()), mod.version()));
                        }
                    });
        }
        int received = 0;
        while (received < n) {
            MaterializeDone d;
            try {
                d = doneQ.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (d.error != null) {
                Throwable c = d.error.getCause() != null ? d.error.getCause() : d.error;
                if (c instanceof CompletionException ce && ce.getCause() != null) c = ce.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (c instanceof RuntimeException re) throw re;
                if (c instanceof Error err) throw err;
                throw new IOException(c);
            }
            arts[d.index] = d.artifact;
            observer.onPackage(d.module, d.version);
            received++;
        }
        for (Lockfile.Artifact art : arts) packages.add(art);

        for (Dependency dep : fileDeps) {
            String version = dep.version() instanceof VersionSelector.Exact ex
                    ? ex.version()
                    : dep.version().raw();
            observer.onPackage(dep.module(), version);
            EnumSet<Scope> tags = EnumSet.noneOf(Scope.class);
            for (Scope scope : SCOPES) {
                for (Dependency d : project.dependencies().of(scope)) {
                    if (d.isFile() && d.module().equals(dep.module())) tags.add(scope);
                }
            }
            if (tags.isEmpty()) tags.add(Scope.MAIN);
            packages.add(new Lockfile.Artifact(
                    dep.module(),
                    version,
                    "local",
                    "sha256:" + dep.sha256(),
                    null,
                    new ArrayList<>(tags),
                    List.of(),
                    null));
        }

        return new Lockfile(Lockfile.CURRENT_VERSION, "jk " + jkVersion, Lockfile.RESOLUTION_ALGORITHM, packages);
    }

    private static GraphGroup graphGroup(Scope scope) {
        return switch (scope) {
            case PROCESSOR -> GraphGroup.PROCESSOR;
            case TEST, TEST_DEV -> GraphGroup.TEST;
            default -> GraphGroup.MAIN;
        };
    }

    private static List<Dependency> splitFile(LinkedHashMap<String, Dependency> deduped, List<Dependency> fileDeps) {
        List<Dependency> out = new ArrayList<>();
        for (Dependency d : deduped.values()) {
            if (d.isFile()) {
                if (fileDeps.stream().noneMatch(f -> f.module().equals(d.module()))) fileDeps.add(d);
            } else {
                out.add(d);
            }
        }
        return out;
    }

    private static void putVersions(Map<String, String> prefs, Resolution resolution) {
        for (var e : resolution.modules().entrySet()) {
            prefs.putIfAbsent(e.getKey(), e.getValue().version());
        }
    }

    /**
     * Catch-up graph progress for any packages not already ticked live during the solve /
     * . {@code seen} keys are display modules (same as live decision ticks).
     */
    private static void noteGraph(ResolveObserver observer, Resolution resolution, Set<String> seen, int estimate) {
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            String display = displayModule(mod.module());
            if (!seen.add(display)) continue;
            observer.onGraphPackage(display, mod.version());
            // Grow denominator if the graph outruns the initial estimate.
            if (seen.size() > estimate) {
                observer.onTotal(seen.size() * 2 + 16);
            }
        }
    }

    private Resolution resolveGroup(
            List<Dependency> roots,
            Map<String, String> bomConstraints,
            Map<String, String> prefs,
            KmpRedirects kmp,
            MavenPackageSource sharedSource,
            EffectivePomBuilder sharedPomBuilder,
            ResolveObserver observer,
            Set<String> graphSeen,
            int estimate)
            throws IOException, InterruptedException {
        if (roots.isEmpty()) return new Resolution(Map.of());
        if (resolverOverride != null) return resolverOverride.resolve(roots);
        java.util.function.BiConsumer<String, String> liveGraph = (pkg, ver) -> {
            // Solver keys are package-id; display as module for progress.
            String mod = displayModule(pkg);
            if (!graphSeen.add(mod)) return;
            observer.onGraphPackage(mod, ver);
            if (graphSeen.size() > estimate) {
                observer.onTotal(graphSeen.size() * 2 + 16);
            }
        };
        if (sharedSource != null && sharedPomBuilder != null) {
            sharedSource.setLockedVersionPrefs(prefs);
            sharedSource.setSnapshotPackages(snapshotModules(roots));
            PubGrubResolver r = new PubGrubResolver(sharedSource, sharedPomBuilder, kmp).withOnDecision(liveGraph);
            if (diagnosticPalette != null) r.palette = diagnosticPalette;
            return r.resolve(roots);
        }
        PubGrubResolver r = buildResolver(repos, bomConstraints, prefs, kmp).withOnDecision(liveGraph);
        if (diagnosticPalette != null) r.palette = diagnosticPalette;
        return r.resolve(roots);
    }

    /** Completion event for parallel jar materialize (progress on complete, rows ordered). */
    private record MaterializeDone(
            int index, Lockfile.Artifact artifact, String module, String version, Throwable error) {
        static MaterializeDone ok(int index, Lockfile.Artifact art, String module, String version) {
            return new MaterializeDone(index, art, module, version, null);
        }

        static MaterializeDone fail(Throwable error) {
            return new MaterializeDone(-1, null, null, null, error);
        }
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

    private void collectBomConstraints(
            JkBuild project,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance)
            throws IOException, InterruptedException {
        for (Dependency platformDep : project.dependencies().of(Scope.PLATFORM)) {
            String bomVersion = versionLiteral(platformDep.version());
            if (bomVersion == null) {
                // R6b: platform BOMs must pin a concrete version (exact / caret / tilde anchor).
                throw new IllegalStateException("platform dependency `"
                        + platformDep.module()
                        + "` must use an exact or caret/tilde version (got `"
                        + platformDep.version().raw()
                        + "`). Floating selectors like `latest` or open ranges are not supported for"
                        + " [platform-dependencies] BOMs — pin e.g. `=3.4.0` or `3.4.0`.");
            }
            Coordinate bomCoord = Coordinate.of(platformDep.group(), platformDep.name(), bomVersion);
            EffectivePom bomPom = pomBuilder.build(bomCoord);
            String bomLabel = bomCoord.toGav();
            for (Pom.Dep m : bomPom.managedDependencies()) {
                if (m.version() == null || m.version().isBlank()) continue;
                String existing = bomConstraints.get(m.module());
                if (existing == null) {
                    bomConstraints.put(m.module(), m.version());
                    constraintProvenance.put(m.module(), bomLabel);
                } else if (!existing.equals(m.version())) {
                    throw new IllegalStateException("platform BOM conflict on `"
                            + m.module()
                            + "`: "
                            + constraintProvenance.get(m.module())
                            + " constrains to "
                            + existing
                            + ", but "
                            + bomLabel
                            + " constrains to "
                            + m.version()
                            + ". Pick one BOM or pin the coord explicitly.");
                }
            }
            // Quarkus (and other) BOMs pin maven-resolver-api/impl via dependencyManagement but
            // often omit named-locks. Bare edges are exact under a platform (EffectivePom fill),
            // but keep the family in the platform map for preferredVersion / pinned-by when an
            // edge arrives without a fill.
            alignMavenResolverFamily(bomConstraints, constraintProvenance, bomPom, bomLabel);
        }
    }

    /**
     * Artifacts that must share one {@code maven-resolver} line. When a platform BOM manages any
     * core resolver jar (or declares {@code maven-resolver.version}), pin the rest of the family
     * to that line if still unconstrained.
     */
    private static final List<String> MAVEN_RESOLVER_FAMILY = List.of(
            "maven-resolver-api",
            "maven-resolver-spi",
            "maven-resolver-util",
            "maven-resolver-impl",
            "maven-resolver-named-locks",
            "maven-resolver-connector-basic",
            "maven-resolver-transport-wagon",
            "maven-resolver-transport-http",
            "maven-resolver-transport-file");

    /**
     * Fill gaps in {@code bomConstraints} for the maven-resolver family so named-locks cannot
     * float to a major line that breaks {@code NamedLockFactory.getLock(String)}.
     */
    static void alignMavenResolverFamily(
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance,
            EffectivePom bomPom,
            String bomLabel) {
        String line = bomPom.properties().get("maven-resolver.version");
        if (line == null || line.isBlank()) {
            // Prefer the BOM's own managed api/impl pin over a line already present from an
            // earlier BOM (those are already in bomConstraints; we only fill gaps).
            for (Pom.Dep m : bomPom.managedDependencies()) {
                if (m.version() == null || m.version().isBlank() || m.module() == null) continue;
                if ("org.apache.maven.resolver:maven-resolver-api".equals(m.module())
                        || "org.apache.maven.resolver:maven-resolver-impl".equals(m.module())) {
                    line = m.version();
                    if (m.module().endsWith(":maven-resolver-api")) break;
                }
            }
        }
        if (line == null || line.isBlank()) return;
        String provenance = bomLabel + " (maven-resolver family)";
        for (String art : MAVEN_RESOLVER_FAMILY) {
            String mod = "org.apache.maven.resolver:" + art;
            if (bomConstraints.putIfAbsent(mod, line) == null) {
                constraintProvenance.put(mod, provenance);
            }
        }
    }

    private static void stripBomForExactRoots(
            List<Dependency> declared,
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance,
            Set<String> injectedRuntimes) {
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) continue;
            if (!(d.version() instanceof VersionSelector.Exact)) continue;
            // An INJECTED runtime root is jk's own bookkeeping, not a user override — it
            // already carries the BOM's managed version, and stripping the BOM here would
            // flip every other edge of the GA to raw POM fills (grails-core declares a
            // groovy NEWER than grails-bom manages → unsat,.
            if (injectedRuntimes.contains(d.module())) continue;
            if (bomConstraints.containsKey(d.module())) {
                bomConstraints.remove(d.module());
                constraintProvenance.remove(d.module());
            }
        }
    }

    private static List<Dependency> materializePlatformManaged(
            List<Dependency> declared, Map<String, String> bomConstraints) {
        List<Dependency> roots = new ArrayList<>(declared.size());
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) {
                String managed = bomConstraints.get(d.module());
                if (managed == null) {
                    throw new IllegalStateException("`" + d.module()
                            + "` is declared without a version, but no [platform-dependencies] BOM manages it"
                            + " — add a `version`, or import the BOM that pins it.");
                }
                roots.add(new Dependency(
                        d.library(), d.module(), VersionSelector.parse("=" + managed), null, null, true, d.optional()));
            } else {
                roots.add(d);
            }
        }
        return roots;
    }

    private Map<String, EnumSet<Scope>> tagScopes(
            JkBuild project, Resolution resolution, List<Scope> scopes, boolean includeJunitSeeds) {
        Map<String, EnumSet<Scope>> tagsByModule = new HashMap<>();
        if (resolution.modules().isEmpty()) return tagsByModule;
        for (Scope scope : scopes) {
            Set<String> rootModules = new HashSet<>();
            for (Dependency d : project.dependencies().of(scope)) {
                // Resolution keys are package ids (g:a:type:classifier); declared modules are GA.
                rootModules.add(PackageId.ofGa(d.module()).key());
            }
            if (includeJunitSeeds && scope == Scope.TEST) {
                rootModules.add(PackageId.ofGa(JUNIT_LAUNCHER.module()).key());
                if (project.dependencies().of(Scope.TEST).isEmpty()) {
                    rootModules.add(PackageId.ofGa(JUNIT_JUPITER.module()).key());
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
            Map<String, String> bomConstraints,
            Map<String, String> constraintProvenance,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        Coordinate coord = mod.coordinate();
        // Stream GA to lock-package events for human-readable UI; lock row name stays package key.
        observer.onPackage(displayModule(mod.module()), mod.version());

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
                kmpAlias ? null : repos.tryFetchArtifact(coord).orElse(null);
        if (hit == null
                && !kmpAlias
                && (coord.type() == null || "jar".equals(coord.type()))
                && (coord.classifier() == null || coord.classifier().isEmpty())) {
            // Jar miss for a bare-GA dep whose POM packaging is aarprobe packaging
            // only on miss — the warm path stays probe-free — and rewrite to.aar
            // instead of silently writing a checksum-less row.
            try {
                if ("aar".equals(pomBuilder.build(coord).packaging())) {
                    coord = new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "aar");
                    packageName = PackageId.of(coord.group(), coord.artifact(), "aar", "")
                            .key();
                    artifactFile = coord.artifact() + "-" + coord.version() + ".aar";
                    hit = repos.tryFetchArtifact(coord).orElse(null);
                }
            } catch (Exception ignored) {
                // no POM / unparseable — keep the jar coordinate
            }
        }
        if (hit != null) {
            source = hit.repo().name() + "+" + hit.repo().baseUrl();
            checksum = "sha256:" + hit.fetched().sha256();
        }

        if (tags.isEmpty()) tags = EnumSet.of(Scope.MAIN);

        String pinnedBy = null;
        String ga = PackageId.isMavenPackageKey(mod.module())
                ? PackageId.parse(mod.module()).ga()
                : mod.module();
        String constrained = bomConstraints.get(ga);
        if (constrained != null && constrained.equals(mod.version())) {
            pinnedBy = constraintProvenance.get(ga);
        }
        // Record activated cross-package features on the library row when present.
        List<String> feat = crossPackageActivatedFeatures == null ? null : crossPackageActivatedFeatures.get(ga);
        if (feat == null && crossPackageActivatedFeatures != null) {
            feat = crossPackageActivatedFeatures.get(mod.module());
        }
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

    /** Filled during {@link #lock}; read by {@link #toArtifact}. */
    private Map<String, List<String>> crossPackageActivatedFeatures;

    /** Human-facing module id: {@code group:artifact} for Maven package keys. */
    private static String displayModule(String moduleOrKey) {
        if (moduleOrKey == null) return "";
        if (PackageId.isMavenPackageKey(moduleOrKey)) {
            try {
                return PackageId.parse(moduleOrKey).ga();
            } catch (RuntimeException ignored) {
                return moduleOrKey;
            }
        }
        return moduleOrKey;
    }

    /**
     * Extract a concrete version literal from a platform-dep's selector. Platform BOMs must be pinned
     * (Exact) or anchored (Caret/Tilde) to a specific version — they're an authoritative pin, not a
     * search. Returns {@code null} for selectors with no resolvable literal (Range, Latest,
     * Snapshot).
     */
    /**
     * The {@code group:artifact} keys among {@code roots} that were declared {@code snapshot} — the
     * only selector that opts into pre-releases.
     */
    private static Set<String> snapshotModules(List<Dependency> roots) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Dependency d : roots) {
            if (d.version() instanceof VersionSelector.Snapshot) out.add(d.module());
        }
        return out;
    }

    private static String versionLiteral(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range ignored -> null;
            case VersionSelector.Latest ignored -> null;
            case VersionSelector.Snapshot ignored -> null;
        };
    }

    /**
     * When the project is Groovy/Kotlin, ensure the language runtime lands in the <em>main</em>
     * lock graph. Engine-side classpath injection covers {@code jk run}/tests but not
     * boot-jar nesting — packaging only sees lock artifacts.
     *
     * <p>{@code putIfAbsent}: an explicit user/Grails BOM dep wins. Version follows the project's
     * {@code kotlin}/{@code groovy} pin when it has a literal; otherwise a floating major of the
     * current jk default so PubGrub still picks a concrete release at lock time.
     */
    /** @return the module keys this call added (skip-list for the exact-root BOM strip). */
    static Set<String> injectLanguageRuntimes(
            JkBuild project,
            Path projectDir,
            Map<String, String> bomConstraints,
            LinkedHashMap<String, Dependency> mainDeduped) {
        Set<String> added = new LinkedHashSet<>();
        JkBuild.Project p = project.project();
        // Same inference the engine uses to enable lanesan unpinned project with
        // src/main/groovy compiles the groovy lane, so its runtime must land in the lock too
        // jk run and packaging read the lock only. Pin-only keying shipped jars that died with
        // NoClassDefFoundError: groovy/lang/GroovyObject.
        cc.jumpkick.layout.Languages langs = projectDir != null
                ? cc.jumpkick.layout.Languages.resolve(p, projectDir)
                : new cc.jumpkick.layout.Languages(true, p.isKotlin(), p.isGroovy());
        // Only when the language has actual sources (src/ or plugin-contributed roots like
        // grails-app/): a bare `kotlin = "2.1.0"` pin on a sourceless module pins the COMPILER
        // (lock.kotlin) but produces no classes — injecting its runtime made such locks fail
        // against repos that don't host the stdlib.
        if (langs.groovy() && hasLangSources(projectDir, ".groovy")) {
            addRuntime(bomConstraints, mainDeduped, added, "org.apache.groovy:groovy", p.groovy(), "5");
        }
        if (langs.kotlin() && hasLangSources(projectDir, ".kt")) {
            addRuntime(bomConstraints, mainDeduped, added, "org.jetbrains.kotlin:kotlin-stdlib", p.kotlin(), "2");
        }
        return added;
    }

    /** True when any {@code ext} source exists under src/ or a plugin-contributed root. */
    private static boolean hasLangSources(Path projectDir, String ext) {
        if (projectDir == null) return true; // no dir context — keep the inject (fail-safe)
        if (cc.jumpkick.layout.Languages.anySourceUnder(projectDir.resolve("src"), ext)) return true;
        for (var root : cc.jumpkick.layout.ModuleLayout.pluginContributedRoots(projectDir)) {
            if (cc.jumpkick.layout.Languages.anySourceUnder(projectDir.resolve(root.relative()), ext)) {
                return true;
            }
        }
        return false;
    }

    /** Inject one runtime; BOM-following injects (no exact pin) join the strip skip-list. */
    private static void addRuntime(
            Map<String, String> bomConstraints,
            LinkedHashMap<String, Dependency> mainDeduped,
            Set<String> added,
            String module,
            VersionSelector declared,
            String fallbackMajor) {
        String pinLit = declared != null ? versionLiteral(declared) : null;
        boolean pinned = pinLit != null && !pinLit.isBlank();
        Dependency dep = new Dependency(module, runtimeSelector(bomConstraints, module, declared, fallbackMajor));
        if (mainDeduped.putIfAbsent(module, dep) == null && !pinned) {
            added.add(module);
        }
    }

    /**
     * An explicit exact pin literal wins (the user's — or a framework scaffold's — deliberate
     * choice; it strips the BOM entry via the normal exact-root rule, which Grails needs: its
     * M4 bom manages a groovy OLDER than grails-core requires). Without a literal, a platform
     * that manages the GA owns the version (Maven parity — the inject then skips the strip so
     * every edge agrees); else floating major.
     */
    private static VersionSelector runtimeSelector(
            Map<String, String> bomConstraints, String module, VersionSelector declared, String fallbackMajor) {
        String lit = declared != null ? versionLiteral(declared) : null;
        if (lit != null && !lit.isBlank()) {
            // Any [project] version literal (bare/caret/tilde all carry one) is a deliberate
            // choice — same contract as the original inject.
            return VersionSelector.parse("=" + lit);
        }
        String managed = bomConstraints.get(module);
        if (managed != null && !managed.isBlank()) {
            return VersionSelector.parse("=" + managed);
        }
        return languageRuntimeSelector(declared, fallbackMajor);
    }

    /** Exact pin when the project declared a version literal; else floating major of {@code fallbackMajor}. */
    private static VersionSelector languageRuntimeSelector(VersionSelector declared, String fallbackMajor) {
        if (declared != null) {
            String lit = versionLiteral(declared);
            if (lit != null && !lit.isBlank()) {
                return VersionSelector.parse("=" + lit);
            }
        }
        return VersionSelector.parse("@" + fallbackMajor);
    }

    /**
     * Try to fetch {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum} populated where sources exist. Packages that
     * return 404, have a non-maven source, or already have a sources checksum are left unchanged.
     */
    public Lockfile attachSources(Lockfile lock) throws InterruptedException {
        List<Lockfile.Artifact> updated = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (!pkg.source().contains("maven") && !pkg.source().startsWith("central")
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
        return new Lockfile(
                lock.version(),
                lock.generatedBy(),
                lock.resolutionAlgorithm(),
                lock.jdk(),
                lock.kotlin(),
                updated,
                lock.plugins(),
                lock.sdk(),
                lock.modules(),
                lock.jk());
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
