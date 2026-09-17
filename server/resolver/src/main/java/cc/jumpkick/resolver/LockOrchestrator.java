// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.LockTimings;
import cc.jumpkick.http.CentralMirror;
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
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolve.ResolveProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** The workspace members behind a merged manifest, each with its own effective manifest. */
    private List<Member> members = List.of();

    /** URL → the repository a dependency POM declared during {@link #lock}, with the policy the POM wrote. */
    private final Map<String, Pom.Repository> declaredRepositories = new ConcurrentHashMap<>();

    /**
     * One workspace member as the lock sees it: its {@code [[module]]} path and its manifest with
     * workspace placeholders resolved, sibling externals and platform tables folded in.
     */
    public record Member(String path, JkBuild manifest) {
        public Member {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    /**
     * The members of the workspace {@code project} merges; empty for a standalone project. A member
     * the merged answer cannot serve is solved on its own and its rows carry {@code members}.
     */
    public LockOrchestrator withMembers(List<Member> members) {
        this.members = members == null ? List.of() : List.copyOf(members);
        return this;
    }

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
     * return 404 are silently skipped — not all packages publish sources. A row's repository outside
     * the project's set is rebuilt with the policy the POM that declared it wrote during this
     * orchestrator's {@link #lock}.
     */
    public Lockfile attachSources(Lockfile lock) throws InterruptedException {
        return new SourcesAttacher(repos, Map.copyOf(declaredRepositories)).attach(lock);
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
     * everything else stays pinned. A member partition row seeds only the member it lists.
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
        Map<String, Map<String, String>> memberPrefs = new HashMap<>();
        for (Lockfile.Artifact pkg : existing.artifacts()) {
            String key = pkg.packageKey();
            String ga = PackageId.isMavenPackageKey(pkg.name())
                    ? PackageId.parse(pkg.name()).ga()
                    : pkg.name();
            if (pkg.isPartition()) {
                for (String member : pkg.members()) {
                    Map<String, String> mine = memberPrefs.computeIfAbsent(member, k -> new HashMap<>());
                    mine.put(key, pkg.version());
                    mine.put(ga, pkg.version());
                }
                continue;
            }
            // Prefer main-scoped rows over test-only / processor-only duals.
            boolean specializedOnly = pkg.scopes().stream()
                            .allMatch(s -> s == Scope.PROCESSOR
                                    || s == Scope.TEST_PROCESSOR
                                    || s == Scope.TEST
                                    || s == Scope.TEST_DEV)
                    && pkg.scopes().stream().noneMatch(LockRoots.MAIN_SCOPES::contains);
            if (specializedOnly) {
                prefs.putIfAbsent(key, pkg.version());
                prefs.putIfAbsent(ga, pkg.version());
            } else {
                prefs.put(key, pkg.version());
                prefs.put(ga, pkg.version());
            }
        }
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, prefs, memberPrefs);
    }

    private Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer,
            Map<String, String> lockedVersionPrefs)
            throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, lockedVersionPrefs, Map.of());
    }

    private Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer,
            Map<String, String> lockedVersionPrefs,
            Map<String, Map<String, String>> memberPrefs)
            throws IOException, InterruptedException {
        LockProgress progress = new LockProgress(observer, timings);
        // one POM builder for BOM load + all scope solves + toArtifact packaging probes.
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        // ... and one table per BOM, shared by the merged manifest's platform table and every member's.
        PlatformConstraints.BomTables bomTables = new PlatformConstraints.BomTables();
        PlatformConstraints constraints =
                PlatformConstraints.collect(sharedPlatform(project), repos, pomBuilder, bomTables, pinPolicy);
        adoptVersionlessRoots(project, constraints, pomBuilder, bomTables);
        Solve union = solveManifest(
                project,
                featuresRequested,
                withDefaults,
                lockedVersionPrefs,
                progress,
                observer,
                pomBuilder,
                constraints);
        for (String line : repos.weakChecksumNotes()) observer.onNote(line);
        for (String line : repos.mirrorNotes()) observer.onNote(line);
        // A launcher and a Jupiter engine on different Platform lines run nothing and report success.
        JupiterLine.checkAligned(union.solved().test());

        progress.materializePhase(
                progress.graphPackages() + union.roots().fileDeps().size());
        Lockfile lockfile = assemble(union, project, jkVersion, progress, pomBuilder);
        // Which POM-declared repositories served a row is known once the rows are assembled.
        if (union.source() != null) {
            for (String line : union.source().declaredRepositoryNotes(lockfile.artifacts())) observer.onNote(line);
        }
        if (!members.isEmpty()) {
            MemberPartitions.MemberSolver solver = (manifest, features, prefs, own) -> {
                // A member solved on its own: its rows, assembled against its own platform table.
                LockProgress silent = new LockProgress(ResolveObserver.NOOP, (a, b, c, d, e) -> {});
                Solve solve = solveManifest(
                        manifest, features, withDefaults, prefs, silent, ResolveObserver.NOOP, pomBuilder, own);
                silent.materializePhase(0);
                return assemble(solve, manifest, jkVersion, silent, pomBuilder);
            };
            MemberPartitions partitions = new MemberPartitions(
                    union, repos, pomBuilder, bomTables, pinPolicy, featuresRequested, withDefaults);
            ResolveProfile.Phases pass = ResolveProfile.phases();
            pass.begin(ResolveProfile::phasePartition);
            try {
                lockfile = partitions.apply(lockfile, members, memberPrefs, solver, observer);
            } finally {
                pass.end();
            }
            // Said on the rows each member reads once the partitions are in: a family a member's
            // own platform table would have aligned, mixed on the plain rows it has instead.
            for (String line : FamilyLines.warnings(lockfile, members)) observer.onNote(line);
        }
        // Said after every leg has run: a refusal Central gives during materialize opens the window
        // as much as one during the solve, and the results name it either way.
        CentralMirror.standard().note().ifPresent(observer::onNote);
        progress.finished(lockfile.artifacts().size());
        return lockfile;
    }

    /**
     * The manifest whose platform table the merged solve runs under: {@code project} with its
     * {@code [platform-dependencies]} cut to the BOMs every member's table holds — the root's, which
     * each member folds first, and one every member declares or depends into. A BOM only some
     * members hold constrains those members' own solves ({@link MemberPartitions}) and never the
     * workspace's rows. A standalone project's table is its own.
     */
    private JkBuild sharedPlatform(JkBuild project) {
        List<Dependency> declared = project.dependencies().of(Scope.PLATFORM);
        if (members.isEmpty() || declared.isEmpty()) return project;
        Set<String> shared = heldBoms(members.getFirst());
        for (Member member : members.subList(1, members.size())) shared.retainAll(heldBoms(member));
        List<Dependency> kept = new ArrayList<>(declared.size());
        for (Dependency bom : declared) if (shared.contains(bom.module())) kept.add(bom);
        if (kept.size() == declared.size()) return project;
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(project.dependencies().byScope());
        if (kept.isEmpty()) {
            byScope.remove(Scope.PLATFORM);
        } else {
            byScope.put(Scope.PLATFORM, kept);
        }
        return project.withDependencies(new JkBuild.Dependencies(byScope));
    }

    /** The modules of the BOMs one member's table holds. */
    private static Set<String> heldBoms(Member member) {
        Set<String> held = new HashSet<>();
        for (Dependency bom : member.manifest().dependencies().of(Scope.PLATFORM)) held.add(bom.module());
        return held;
    }

    /**
     * A versionless root of the merged manifest that only some members' BOMs manage takes their
     * version in the merged solve, as the exact pin the declaring member could have written would:
     * the shared table adopts that module's say from the table folding every BOM the manifest
     * declares, which is collected only when such a root exists.
     */
    private void adoptVersionlessRoots(
            JkBuild project,
            PlatformConstraints shared,
            EffectivePomBuilder pomBuilder,
            PlatformConstraints.BomTables bomTables)
            throws IOException, InterruptedException {
        if (members.isEmpty()) return;
        PlatformConstraints whole = null;
        for (Map.Entry<Scope, List<Dependency>> scope :
                project.dependencies().byScope().entrySet()) {
            if (scope.getKey() == Scope.PLATFORM || scope.getKey() == Scope.MANAGED) continue;
            for (Dependency root : scope.getValue()) {
                if (!root.isPlatformManaged() || shared.versions().containsKey(root.module())) continue;
                if (whole == null)
                    whole = PlatformConstraints.collect(project, repos, pomBuilder, bomTables, pinPolicy);
                shared.adopt(root.module(), whole);
            }
        }
    }

    /**
     * One manifest's platform table, roots and three solved graphs.
     *
     * @param source the package source the graphs were solved over; {@code null} under a test's
     *     resolver override
     */
    record Solve(
            PlatformConstraints constraints,
            LockRoots.Roots roots,
            ScopeSolves.Solved solved,
            @Nullable MavenPackageSource source,
            KmpRedirects kmp) {}

    /**
     * @param constraints the manifest's platform table, collected by the caller; the solve edits it
     *     (exact roots strip their BOM say, the runtime inject and the test-framework pins add to it)
     */
    private Solve solveManifest(
            JkBuild project,
            Collection<String> featuresRequested,
            boolean withDefaults,
            Map<String, String> prefs,
            LockProgress progress,
            ResolveObserver observer,
            EffectivePomBuilder pomBuilder,
            PlatformConstraints constraints)
            throws IOException, InterruptedException {
        LockRoots.Declared declared = LockRoots.partition(project, featuresRequested, withDefaults);
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

        KmpRedirects kmp = new KmpRedirects(repos, jvmEnvironment);
        // Shared package source across main/test/processor so version/deps caches survive scope splits.
        MavenPackageSource sharedSource = resolverOverride != null
                ? null
                : new MavenPackageSource(repos, pomBuilder, bomConstraints, prefs, kmp, platformPolicy, unmappedPolicy);
        if (sharedSource != null) sharedSource.setManagedExclusions(constraints.managedExclusions());

        progress.graphPhase(roots.declaredCount());
        ScopeSolves scopeSolves = new ScopeSolves(resolverOverride, sharedSource, pomBuilder, kmp, pinPolicy);
        ScopeSolves.Solved solved = scopeSolves.solve(roots, prefs, progress);
        for (String line : scopeSolves.overrides()) observer.onOverride(line);
        if (sharedSource != null) {
            for (String line : sharedSource.nearestOverrides()) observer.onOverride(line);
            for (String line : sharedSource.hostClassifierNotes()) observer.onNote(line);
            declaredRepositories.putAll(sharedSource.declaredRepositoriesByUrl());
        }
        return new Solve(constraints, roots, solved, sharedSource, kmp);
    }

    private Lockfile assemble(
            Solve solve, JkBuild project, String jkVersion, LockProgress progress, EffectivePomBuilder pomBuilder)
            throws IOException, InterruptedException {
        Function<String, RepoGroup> reposFor = solve.source() != null ? solve.source()::reposFor : pkg -> repos;
        return new LockfileAssembler(repos, reposFor, solve.kmp(), pomBuilder, solve.constraints(), activatedFeatures)
                .assemble(project, solve.solved(), solve.roots().fileDeps(), jkVersion, progress);
    }
}
