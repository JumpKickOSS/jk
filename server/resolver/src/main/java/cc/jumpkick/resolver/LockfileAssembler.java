// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Fold the three resolved graphs plus the file dependencies into {@link Lockfile} rows. The same
 * module at the same version accumulates every scope that reaches it; the same module at a
 * different version is a separate row carrying only that graph's scopes — that is the lockfile's
 * row identity. Every Maven row is downloaded (in parallel, through {@link ArtifactMaterializer})
 * before it is written, so a row never lands without the checksum of the file it names.
 */
final class LockfileAssembler {

    private final RepoGroup repos;
    private final Function<String, RepoGroup> reposFor;
    private final KmpRedirects kmp;
    private final EffectivePomBuilder pomBuilder;
    private final PlatformConstraints constraints;
    private final Map<String, List<String>> activatedFeatures;
    private Set<String> workspaceModules = Set.of();

    /**
     * @param reposFor the group a package's artifact is fetched from: {@code repos} plus the
     *     repositories a dependency POM declared for the subtree the package was reached through
     * @param pomBuilder the one POM builder the lock shares with BOM load and the solves
     * @param activatedFeatures cross-package features activated per library module, for {@code pinnedBy}
     */
    LockfileAssembler(
            RepoGroup repos,
            Function<String, RepoGroup> reposFor,
            KmpRedirects kmp,
            EffectivePomBuilder pomBuilder,
            PlatformConstraints constraints,
            Map<String, List<String>> activatedFeatures) {
        this.repos = repos;
        this.reposFor = reposFor;
        this.kmp = kmp;
        this.pomBuilder = pomBuilder;
        this.constraints = constraints;
        this.activatedFeatures = activatedFeatures;
    }

    /** Packages the workspace builds: present in the graph so parents name them, never a lock row. */
    void workspaceModules(Set<String> gas) {
        this.workspaceModules = gas == null ? Set.of() : Set.copyOf(gas);
    }

    /** Rows in declaration order: Maven rows first, then the file dependencies. */
    Lockfile assemble(
            JkBuild project,
            ScopeSolves.Solved solved,
            List<Dependency> fileDeps,
            String jkVersion,
            LockProgress progress)
            throws IOException, InterruptedException {
        Map<String, EnumSet<Scope>> mainTags = tagScopes(project, solved.main(), LockRoots.MAIN_SCOPES, false);
        Map<String, EnumSet<Scope>> testTags = tagScopes(project, solved.test(), LockRoots.TEST_SCOPES, true);
        Map<String, EnumSet<Scope>> processorTags =
                tagScopes(project, solved.processor(), LockRoots.PROCESSOR_SCOPES, false);

        MavenRepo first = repos.repos().getFirst();
        String fallbackSource = first.name() + "+" + first.baseUrl();

        List<Lockfile.Artifact> packages = new ArrayList<>();
        // module@version → mutable tag set while merging graphs
        Map<String, EnumSet<Scope>> tagsByKey = new LinkedHashMap<>();
        Map<String, Resolution.ResolvedModule> modByKey = new LinkedHashMap<>();

        mergeGraph(solved.main(), mainTags, Scope.MAIN, tagsByKey, modByKey);
        mergeGraph(solved.test(), testTags, Scope.TEST, tagsByKey, modByKey);
        mergeGraph(solved.processor(), processorTags, Scope.PROCESSOR, tagsByKey, modByKey);
        modByKey.keySet().removeIf(this::builtByWorkspace);

        ArtifactMaterializer materializer =
                new ArtifactMaterializer((mod, tags, abort) -> toArtifact(mod, tags, fallbackSource, abort), progress);
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
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk " + jkVersion, Lockfile.RESOLUTION_ALGORITHM, packages);
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
                for (Dependency d : LockRoots.injectedTestRoots(project)) rootModules.add(d.packageKey());
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

    private boolean builtByWorkspace(String pkg) {
        try {
            return workspaceModules.contains(PackageId.parse(pkg).ga());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Lockfile.Artifact toArtifact(
            Resolution.ResolvedModule mod, EnumSet<Scope> tags, String fallbackSource, BooleanSupplier abort)
            throws IOException, InterruptedException {
        Coordinate coord = mod.coordinate();
        boolean kmpAlias = kmp.selectionFor(mod.module(), mod.version()).isPresent();

        String packageName = mod.module();
        String artifactFile = null;
        String source = fallbackSource;
        String checksum = null;
        RepoGroup group = reposFor.apply(mod.module());
        // A coordinate of type pom or a relocation stub has no artifact to ask for: its POM, read
        // by the solve, already says so. A packaging=pom POM says less than it seems — an assembly
        // publishes one beside its jar, and a type-less dependency means the jar to Maven whatever
        // the packaging — so the jar is asked for from the repository that served the POM, and a
        // miss leaves the file-less row a BOM or aggregator is.
        boolean pomOnly = !kmpAlias && isPomOnlyPackage(coord, pomBuilder);
        boolean pomPackaged = !kmpAlias && !pomOnly && pomPackaged(coord, pomBuilder);
        RepoGroup.RepoFetched hit = null;
        if (!kmpAlias && !pomOnly) hit = group.tryFetchArtifact(coord, abort).orElse(null);
        if (hit == null && pomPackaged) pomOnly = true;
        if (hit == null
                && !kmpAlias
                && !pomOnly
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
                    hit = group.tryFetchArtifact(coord, abort).orElse(null);
                }
            } catch (Exception e) {
                // no POM / unparseable — keep the jar coordinate
                Log.debug("toArtifact: no POM / unparseable", e);
            }
        }
        if (hit != null) {
            source = hit.repo().name() + "+" + hit.repo().baseUrl();
            checksum = "sha256:" + hit.fetched().sha256();
        } else if (!kmpAlias && !pomOnly) {
            // a resolved package whose artifact 404s must not land as a checksum-less
            // lock row that ClasspathResolver silently drops. KMP aliases and packaging=pom
            // (BOMs / aggregators) legitimately have no file; everything else fails the lock.
            throw unfetchableArtifact(coord, fallbackSource);
        } else {
            // No file of its own: the row stands for its POM — or, for a KMP root, its Gradle
            // module file — at the repository that served it. That repository answered for the
            // coordinate; the group's first entry may have served nothing at all. The file is
            // named on the row so a reader can tell a row without a file by design from one whose
            // jar nobody fetched.
            MavenRepo holder = group.pomRepository(coord).orElse(null);
            if (holder != null) source = holder.name() + "+" + holder.baseUrl();
            if (coord.type() == null || !"pom".equalsIgnoreCase(coord.type())) {
                artifactFile = coord.artifact() + "-" + coord.version() + (kmpAlias ? ".module" : ".pom");
            }
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
                pinnedBy,
                null,
                null,
                mod.excluded());
    }

    /**
     * True when this package publishes no primary artifact by its own account: coordinate type
     * {@code pom}, or a relocation stub, whose POM points at the target and stands beside no jar
     * of its own.
     */
    private static boolean isPomOnlyPackage(Coordinate coord, EffectivePomBuilder pomBuilder) {
        if (coord.type() != null && "pom".equalsIgnoreCase(coord.type())) {
            return true;
        }
        try {
            Pom.Relocation moved = pomBuilder.build(coord).relocation();
            return moved != null && moved.redirects(coord);
        } catch (Exception e) {
            Log.debug("isPomOnlyPackage: no POM / unparseable, the jar is asked for", e);
            return false;
        }
    }

    /** True when the POM of {@code coord} declares {@code packaging=pom}: a BOM, an aggregator, or an assembly beside its jar. */
    private static boolean pomPackaged(Coordinate coord, EffectivePomBuilder pomBuilder) {
        try {
            return "pom".equalsIgnoreCase(pomBuilder.build(coord).packaging());
        } catch (Exception e) {
            Log.debug("pomPackaged: no POM / unparseable", e);
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
