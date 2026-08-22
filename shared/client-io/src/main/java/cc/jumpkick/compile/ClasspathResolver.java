// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Maps a {@link Lockfile}'s checksummed packages to on-disk {@code *.jar} paths, filtered by
 * scope. Packages without a checksum (POM-only / path / git) are skipped — they don't contribute
 * to the compile classpath.
 *
 * <p>This is a pure name-resolution step: it doesn't fetch anything. {@code jk sync} ensures
 * artifacts are on disk. Paths are Maven-layout names (local repo or {@code repos/<name>/}), never
 * hash-named CAS blobs.
 *
 * <p>Workspace locks are a <strong>union</strong> of every module's graph. Prefer
 * {@link #classpathClosure} / {@link #entriesForClosure} for packaging (assembly, native-image)
 * so a fat jar only embeds the module's runtime closure — not the whole monorepo lock.
 */
public final class ClasspathResolver {

    /** Scopes used to build the runtime / test runtime classpath. */
    public static final Set<Scope> TEST =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.TEST, Scope.TEST_DEV);

    /** Scopes bundled into a runnable app (assembly jar / installed $JK_LIB_DIR/<bin>/). */
    public static final Set<Scope> RUNTIME = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    /**
     * The {@code jk run}/{@code jk dev} exec classpath: production runtime plus the dev-loop
     * scopes ({@code [dev-dependencies]}, {@code [test-dev-dependencies]}) — DevTools, Docker
     * Compose support, and friends ride local runs but never artifacts ({@link #RUNTIME} is what
     * packagers consume).
     */
    public static final Set<Scope> RUN = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.DEV, Scope.TEST_DEV);

    /** Scopes visible while compiling main sources. */
    public static final Set<Scope> COMPILE_MAIN = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED);

    /** Scopes visible while compiling test sources. */
    public static final Set<Scope> COMPILE_TEST =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.TEST, Scope.TEST_DEV);

    private final Path storeRoot;
    private final cc.jumpkick.repo.ArtifactLocator locator;

    public ClasspathResolver(Cas cas) {
        this(Objects.requireNonNull(cas, "cas").root());
    }

    public ClasspathResolver(Path storeRoot) {
        this(storeRoot, new cc.jumpkick.repo.ArtifactLocator(storeRoot));
    }

    public ClasspathResolver(Path storeRoot, cc.jumpkick.repo.ArtifactLocator locator) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    /** Backwards-compat overload: returns every checksummed package. */
    public List<Path> classpathFor(Lockfile lock) {
        return classpathFor(lock, EnumSet.allOf(Scope.class));
    }

    /** Filtered: only packages tagged with one of {@code scopes}. */
    public List<Path> classpathFor(Lockfile lock, Set<Scope> scopes) {
        List<Path> result = new ArrayList<>(lock.artifacts().size());
        for (Entry entry : entriesFor(lock, scopes)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /**
     * Transitive closure of {@code rootModules} walked through the lockfile dependency graph,
     * then resolved to jar paths. Roots may be bare {@code g:a} or full package keys; workspace /
     * git / path refs are ignored. Used by assembly and native-image packaging so a monorepo lock
     * does not dump every module's deps into one fat jar.
     */
    public List<Path> classpathClosure(Lockfile lock, Collection<String> rootModules, Set<Scope> scopes) {
        List<Path> result = new ArrayList<>();
        for (Entry entry : entriesForClosure(lock, rootModules, scopes)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /**
     * Declared external dependency modules on {@code project} in {@code scopes} (skips workspace /
     * git / path). Suitable seeds for {@link #classpathClosure}.
     */
    public static Set<String> declaredExternalRoots(JkBuild project, Set<Scope> scopes) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency dep : project.dependencies().of(scope)) {
                if (dep.isWorkspace() || dep.isGit() || dep.isPath()) continue;
                String module = dep.module();
                if (module != null && !module.isBlank()) roots.add(module);
            }
        }
        return roots;
    }

    /**
     * A resolved classpath element with the lockfile artifact it came from. {@code container} is
     * the exploded archive dir for artifacts whose packaging is a container (an AAR: res/,
     * AndroidManifest.xml, R.txt live there; {@code jar} is its {@code classes.jar}) — null for
     * plain jars. An AAR with no classes.jar yields a null {@code jar} (resources-only library).
     */
    public record Entry(Lockfile.Artifact artifact, Path jar, Path container) {

        /** Plain-jar entry (no sources/javadoc). */
        public Entry(Lockfile.Artifact artifact, Path jar) {
            this(artifact, jar, null);
        }
    }

    /**
     * As {@link #classpathFor(Lockfile, Set)}, but keeping each path paired with its lockfile
     * artifact — packagers that need original coordinates (Boot's {@code BOOT-INF/lib} uses
     * {@code artifact-version.jar} names, never CAS hashes) read these.
     */
    public List<Entry> entriesFor(Lockfile lock, Set<Scope> scopes) {
        // Collect matches first, then collapse dual-version rows (R5/R6 per-scope locks can
        // emit the same module at different versions for main vs test vs processor).
        List<Lockfile.Artifact> matched = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.inAnyScope(scopes)) matched.add(pkg);
        }
        return resolveEntries(selectPerModule(matched, scopes));
    }

    /**
     * As {@link #classpathClosure}, but keeping each path paired with its lockfile artifact.
     */
    public List<Entry> entriesForClosure(Lockfile lock, Collection<String> rootModules, Set<Scope> scopes) {
        List<Lockfile.Artifact> closure = reachableArtifacts(lock, rootModules);
        List<Lockfile.Artifact> matched = new ArrayList<>();
        for (Lockfile.Artifact pkg : closure) {
            if (pkg.inAnyScope(scopes)) matched.add(pkg);
        }
        // Prefer main-scoped dual rows when the walk hit both; same collapse as the full-lock path.
        return resolveEntries(selectPerModule(matched, scopes));
    }

    /**
     * BFS from {@code rootModules} through lock {@code deps} edges. Roots that do not resolve in
     * the lock are skipped (caller may still surface missing-dep diagnostics elsewhere).
     */
    static List<Lockfile.Artifact> reachableArtifacts(Lockfile lock, Collection<String> rootModules) {
        if (rootModules == null || rootModules.isEmpty()) return List.of();
        Map<String, Lockfile.Artifact> byKey = indexArtifacts(lock);
        LinkedHashSet<Lockfile.Artifact> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> enqueued = new HashSet<>();
        for (String root : rootModules) {
            if (root == null || root.isBlank()) continue;
            if (Dependency.isWorkspaceRef(root)
                    || root.startsWith(Dependency.GIT_PREFIX)
                    || root.startsWith(Dependency.PATH_PREFIX)) {
                continue;
            }
            if (enqueued.add(root)) queue.add(root);
        }
        while (!queue.isEmpty()) {
            String key = queue.poll();
            Lockfile.Artifact pkg = lookup(byKey, key);
            if (pkg == null || !visited.add(pkg)) continue;
            for (String depRef : pkg.deps()) {
                String child = stripVersion(depRef);
                if (child.isBlank()) continue;
                if (enqueued.add(child)) queue.add(child);
            }
        }
        return new ArrayList<>(visited);
    }

    /** Index lock rows by package key, bare name, and default-jar GA for declared-root lookup. */
    static Map<String, Lockfile.Artifact> indexArtifacts(Lockfile lock) {
        Map<String, Lockfile.Artifact> result = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            result.put(pkg.name(), pkg);
            result.put(pkg.packageKey(), pkg);
            if (PackageId.isMavenPackageKey(pkg.name())) {
                PackageId id = PackageId.parse(pkg.name());
                if (id.isDefaultJar()) {
                    result.put(id.ga(), pkg);
                } else {
                    result.putIfAbsent(id.ga(), pkg);
                }
            }
        }
        return result;
    }

    private static Lockfile.Artifact lookup(Map<String, Lockfile.Artifact> byKey, String moduleOrKey) {
        Lockfile.Artifact direct = byKey.get(moduleOrKey);
        if (direct != null) return direct;
        if (!PackageId.isMavenPackageKey(moduleOrKey)) return null;
        try {
            PackageId id = PackageId.parse(moduleOrKey);
            Lockfile.Artifact byKeyHit = byKey.get(id.key());
            if (byKeyHit != null) return byKeyHit;
            return byKey.get(id.ga());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code g:a:jar:@1.2.3} / {@code g:a@1.2.3} → package key or GA without version pin. */
    static String stripVersion(String depRef) {
        if (depRef == null) return "";
        int at = depRef.indexOf('@');
        return at > 0 ? depRef.substring(0, at) : depRef;
    }

    private List<Entry> resolveEntries(List<Lockfile.Artifact> selected) {
        List<Entry> result = new ArrayList<>(selected.size());
        cc.jumpkick.task.AccessLedger ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
        for (Lockfile.Artifact pkg : selected) {
            String checksum = pkg.checksum();
            if (checksum == null) {
                // POM-only aliases (KMP roots, packaging=pom) legitimately have none; a jar row
                // without a checksum is an incomplete lock. Either way, never skip silently
                // : a missing classpath entry must not present as "cannot find symbol".
                System.err.println("jk: warning: lock row "
                        + pkg.name()
                        + "@"
                        + pkg.version()
                        + " has no checksum — skipped from classpath"
                        + " (POM-only alias, or incomplete lock; re-run `jk lock`)");
                continue;
            }
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            Path jar = locator.locate(pkg).orElse(null);
            if (jar == null) {
                System.err.println("jk: warning: lock row "
                        + pkg.name()
                        + "@"
                        + pkg.version()
                        + " is not on disk — skipped from classpath (run `jk sync`)");
                continue;
            }
            if (pkg.isAar()) {
                try {
                    Path container = cc.jumpkick.cache.ExplodedArchives.explodeFile(new Cas(storeRoot), jar);
                    Path classesJar = container.resolve("classes.jar");
                    result.add(new Entry(pkg, Files.isRegularFile(classesJar) ? classesJar : null, container));
                } catch (IOException e) {
                    throw new UncheckedIOException(pkg.name() + " v" + pkg.version() + ": " + e.getMessage(), e);
                }
                ledger.touch(hex);
                continue;
            }
            result.add(new Entry(pkg, jar));
            ledger.touch(hex);
        }
        return result;
    }

    /** One jar per module when dual-scoped; prefer processor, then test dual, else main/runtime. */
    static List<Lockfile.Artifact> selectPerModule(List<Lockfile.Artifact> matched, Set<Scope> scopes) {
        Map<String, Lockfile.Artifact> best = new LinkedHashMap<>();
        Map<String, Integer> bestScore = new HashMap<>();
        boolean processorOnlyFilter = scopes.size() == 1 && scopes.contains(Scope.PROCESSOR);
        boolean wantsTest = scopes.contains(Scope.TEST) || scopes.contains(Scope.TEST_DEV);
        for (Lockfile.Artifact pkg : matched) {
            int score = scopePreferenceScore(pkg, processorOnlyFilter, wantsTest);
            Integer prev = bestScore.get(pkg.name());
            if (prev == null || score > prev) {
                best.put(pkg.name(), pkg);
                bestScore.put(pkg.name(), score);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static int scopePreferenceScore(Lockfile.Artifact pkg, boolean processorOnlyFilter, boolean wantsTest) {
        List<Scope> sc = pkg.scopes();
        boolean hasMain = sc.contains(Scope.MAIN)
                || sc.contains(Scope.EXPORT)
                || sc.contains(Scope.RUNTIME)
                || sc.contains(Scope.PROVIDED)
                || sc.contains(Scope.DEV);
        boolean hasTest = sc.contains(Scope.TEST) || sc.contains(Scope.TEST_DEV);
        boolean hasProc = sc.contains(Scope.PROCESSOR);
        boolean procOnly = hasProc && !hasMain && !hasTest;
        boolean testOnly = hasTest && !hasMain && !hasProc;

        if (processorOnlyFilter && procOnly) return 300;
        if (processorOnlyFilter && hasProc) return 200;
        // Main-scoped row wins over a test dual of the same module on mixed classpaths
        // (test compile uses the version main was built against). Test-only modules still win
        // when no main row exists (score 80 > 0).
        if (hasMain) return 100;
        if (wantsTest && testOnly) return 80;
        if (hasTest) return 50;
        if (hasProc) return 25;
        return 0;
    }
}
