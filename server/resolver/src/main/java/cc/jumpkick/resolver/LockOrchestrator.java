// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * End-to-end lock: {@link JkBuild} → three independent scope solves (main / test / processor) →
 * downloads → {@link Lockfile}. Same module at different versions gets multiple artifact rows;
 * classpaths filter by scope.
 */
public final class LockOrchestrator {

    private final RepoGroup repos;
    private final @Nullable Resolver resolverOverride;
    private LockProgress.Timings timings = LockTimings::record;

    /**
     * {@code org.gradle.jvm.environment} for KMP variant selection ({@code "android"} for Android
     * projects, else standard-jvm). Set via {@link #withJvmEnvironment}.
     */
    private String jvmEnvironment = "standard-jvm";

    /** Consuming project directory — language-runtime inference reads its source trees. */
    private @Nullable Path projectDir;

    /** Cross-package features activated per library module, recorded on that library's row. */
    private Map<String, List<String>> activatedFeatures = Map.of();

    private LanguageRuntimeInject.ToolVersions toolVersions = LanguageRuntimeInject.ToolVersions.NONE;

    /** BOM pin policy; default {@link PlatformPolicy#ENFORCED}. */
    private PlatformPolicy platformPolicy = PlatformPolicy.ENFORCED;

    /** Unmapped-fill policy; default {@link cc.jumpkick.model.UnmappedPolicy#MEDIATE}. */
    private UnmappedPolicy unmappedPolicy = UnmappedPolicy.MEDIATE;

    /** How a declared exact pin meets a transitive's constraint; default {@link PinPolicy#EXACT}. */
    private PinPolicy pinPolicy = PinPolicy.EXACT;

    /** The compiler versions this lock pins; the injected stdlibs follow them exactly. */
    public LockOrchestrator withToolVersions(LanguageRuntimeInject.ToolVersions tools) {
        this.toolVersions = tools == null ? LanguageRuntimeInject.ToolVersions.NONE : tools;
        return this;
    }

    public LockOrchestrator withProjectDir(Path projectDir) {
        this.projectDir = projectDir;
        return this;
    }

    /** The features the consumer activated on each path library, keyed by the module its row carries. */
    public LockOrchestrator withActivatedFeatures(Map<String, List<String>> activatedFeatures) {
        this.activatedFeatures = activatedFeatures == null ? Map.of() : Map.copyOf(activatedFeatures);
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

    /** Direct-pin policy (see {@link PinPolicy}). */
    public LockOrchestrator withPinPolicy(PinPolicy policy) {
        if (policy != null) this.pinPolicy = policy;
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

    /** Lock with the project's default feature selection. */
    public Lockfile lock(JkBuild project, String jkVersion) throws IOException, InterruptedException {
        return lock(project, jkVersion, List.of(), true, ResolveObserver.NOOP);
    }

    /**
     * Resolve the {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum} populated where sources exist. Sources that
     * return 404 are silently skipped — not all packages publish sources.
     */
    public Lockfile attachSources(Lockfile lock) throws InterruptedException {
        return new SourcesAttacher(repos).attach(lock);
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
                            && pkg.scopes().stream().noneMatch(LockRoots.MAIN_SCOPES::contains);
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

        LockRoots.Declared declared = LockRoots.partition(project, featuresRequested, withDefaults);
        // one POM builder for BOM load + all scope solves + toArtifact packaging probes.
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        PlatformConstraints constraints = PlatformConstraints.collect(project, repos, pomBuilder, pinPolicy);
        Map<String, String> bomConstraints = constraints.versions();

        // Language runtimes must be lock deps so package-jar / boot-jar nest them.
        // Engine classpath injection alone is not enough for standalone `java -jar`.
        // Runs AFTER BOM collection: a platform that manages the runtime (grails-bom's groovy)
        // owns its version — the inject must not smuggle the scaffold default past it.
        Set<String> injected =
                LanguageRuntimeInject.inject(project, projectDir, bomConstraints, declared.main(), toolVersions);

        LockRoots.Roots roots = constraints.apply(declared.split(), injected);
        for (String line : constraints.renderedOverrides()) observer.onOverride(line);
        // The framework a suite declares is the framework it runs on: an injected engine's own edge
        // onto it takes the declared pin, as a transitive takes a direct dependency's in Maven.
        bomConstraints.putAll(TestEngines.declaredTriggerPins(project));
        List<Dependency> fileDeps = roots.fileDeps();

        KmpRedirects kmp = new KmpRedirects(repos, jvmEnvironment);
        // Shared package source across main/test/processor so version/deps caches survive scope splits.
        MavenPackageSource sharedSource = resolverOverride != null
                ? null
                : new MavenPackageSource(
                        repos, pomBuilder, bomConstraints, lockedVersionPrefs, kmp, platformPolicy, unmappedPolicy);

        progress.graphPhase(roots.declaredCount());
        ScopeSolves.Solved solved = new ScopeSolves(resolverOverride, sharedSource, pomBuilder, kmp, pinPolicy)
                .solve(roots, lockedVersionPrefs, progress);
        if (sharedSource != null) {
            for (String line : sharedSource.nearestOverrides()) observer.onOverride(line);
            for (String line : sharedSource.hostClassifierNotes()) observer.onNote(line);
            for (String line : sharedSource.declaredRepositoryNotes()) observer.onNote(line);
        }
        for (String line : repos.weakChecksumNotes()) observer.onNote(line);
        // A launcher and a Jupiter engine on different Platform lines run nothing and report success.
        JupiterLine.checkAligned(solved.test());

        progress.materializePhase(progress.graphPackages() + fileDeps.size());
        Function<String, RepoGroup> reposFor = sharedSource != null ? sharedSource::reposFor : pkg -> repos;
        Lockfile lockfile = new LockfileAssembler(repos, reposFor, kmp, pomBuilder, constraints, activatedFeatures)
                .assemble(project, solved, fileDeps, jkVersion, progress);
        progress.finished(lockfile.artifacts().size());
        return lockfile;
    }
}
