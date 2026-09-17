// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.ExplodedArchives;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.M2Dirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link Lockfile}'s checksummed packages to on-disk {@code *.jar} paths, filtered by
 * scope. Packages without a checksum (POM-only / path / git) are skipped — they don't contribute
 * to the compile classpath.
 *
 * <p>This is a pure name-resolution step: it doesn't fetch anything. {@code resolve-deps} /
 * {@code jk sync} materializes jars first; build steps then call with {@code requirePresent =
 * true} so a miss fails naming the GAV instead of soft-skipping into javac "package does not
 * exist". Soft-skip remains the default for forecasting / explain on a cold store.
 *
 * <p>Paths are Maven-layout names (local repo or {@code repos/<name>/}), never hash-named CAS
 * blobs. Workspace locks are a <strong>union</strong> of every module's graph — prefer
 * {@link #classpathClosure} / {@link #entriesForClosure} for packaging (assembly, native-image)
 * so a fat jar only embeds the module's runtime closure — not the whole monorepo lock.
 *
 * <p>A classpath handed to a compiler or a JVM is built with the module's manifest ({@link
 * #classpathFor(Lockfile, Set, boolean, JkBuild)}), which puts the module's own declarations
 * first; the manifest-less overloads list the lock's rows in lock order and serve bills of
 * materials and diagnostics.
 */
public final class ClasspathResolver {

    /**
     * Scopes on the test runtime classpath — what a forked test JVM sees. {@code provided} is among
     * them as it is on Maven's test classpath: the container or framework API a test reaches for is
     * there at test time and still absent from {@link #RUNTIME}, which is what ships.
     */
    public static final Set<Scope> TEST =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.TEST_DEV);

    /** Scopes bundled into a runnable app (assembly jar / installed {@code <home>/lib/<bin>/}). */
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
    private final ArtifactLocator locator;

    /**
     * Store-only locator for a lock that opted out of {@code [m2] integration}, built once so
     * {@link #resolved} keys stay stable across calls.
     */
    private volatile @Nullable ArtifactLocator storeOnlyLocator;

    /**
     * Artifact coordinate &rarr; the jar backing it, per locator. A workspace resolves every
     * module's classpath against one lock, so the same few hundred artifacts are located tens of
     * thousands of times per build, and each miss costs a stat plus a checksum-memo read. The
     * answer depends only on the coordinate, its sha and the locator — all fixed for a run.
     *
     * <p>Only hits are memoized: a miss can become a hit when a concurrent sync lands the jar, and
     * caching "absent" would strand the classpath for the rest of the build.
     */
    private final Map<String, Path> resolved = new ConcurrentHashMap<>();

    public ClasspathResolver(Cas cas) {
        this(Objects.requireNonNull(cas, "cas").root(), defaultLocator(cas.root()));
    }

    /** Store-only (no Maven local repo). Tests and callers that already pass a locator. */
    public ClasspathResolver(Path storeRoot) {
        this(storeRoot, new ArtifactLocator(storeRoot));
    }

    private static ArtifactLocator defaultLocator(Path storeRoot) {
        boolean m2 = JkM2Config.resolve().integration();
        return new ArtifactLocator(storeRoot, m2 ? M2Dirs.localRepository() : null, m2);
    }

    public ClasspathResolver(Path storeRoot, ArtifactLocator locator) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    /** Backwards-compat overload: returns every checksummed package. */
    public List<Path> classpathFor(Lockfile lock) {
        return classpathFor(lock, EnumSet.allOf(Scope.class));
    }

    /** Filtered: only packages tagged with one of {@code scopes}. */
    public List<Path> classpathFor(Lockfile lock, Set<Scope> scopes) {
        return classpathFor(lock, scopes, false);
    }

    /**
     * As {@link #classpathFor(Lockfile, Set)}. When {@code requirePresent} is true, every
     * checksummed lock row in {@code scopes} must resolve to an on-disk jar — used after {@code
     * resolve-deps} so a cold store cannot soft-skip into an empty compile classpath.
     */
    public List<Path> classpathFor(Lockfile lock, Set<Scope> scopes, boolean requirePresent) {
        List<Path> result = new ArrayList<>(lock.artifacts().size());
        for (Entry entry : entriesFor(lock, scopes, requirePresent)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /**
     * A module's classpath over {@code scopes}, in the order Maven hands javac and the JVM: the
     * module's own declarations first, in manifest order ({@code [dependencies]} before
     * {@code [provided-dependencies]} and the test tables), then their transitives breadth-first
     * through the lock graph, then every other row of the lock in {@code scopes} in lock order (a
     * workspace lock is the union of its members' graphs). A package two jars carry resolves to
     * the jar the module declared.
     */
    public List<Path> classpathFor(Lockfile lock, Set<Scope> scopes, boolean requirePresent, JkBuild module) {
        List<Path> result = new ArrayList<>(lock.artifacts().size());
        for (Entry entry : entriesFor(lock, scopes, requirePresent, module)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /**
     * The rows of every composite sibling's lock in {@code scopes}, each lock read as its own module
     * reads it (a member's rows of a shared workspace lock) and in the order that module's own
     * classpath has — its declarations first, their transitives breadth-first, then the rest of its
     * lock — so a package a sibling's transitive and its declaration both carry resolves to the jar
     * the sibling declared. Deduplicated across siblings; a caller appends it after the module's
     * own rows.
     */
    public List<Path> siblingClasspath(
            List<WorkspaceClasspath.SiblingLock> siblings, Set<Scope> scopes, boolean requirePresent)
            throws IOException {
        List<Path> out = new ArrayList<>();
        for (WorkspaceClasspath.SiblingLock sibling : siblings) {
            Lockfile lock = MemberRows.view(LockfileReader.read(sibling.lockFile()), sibling.lockFile(), sibling.dir());
            for (Path p : classpathFor(lock, scopes, requirePresent, sibling.build())) {
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /** As {@link #classpathFor(Lockfile, Set, boolean, JkBuild)}, each path paired with its lock row. */
    public List<Entry> entriesFor(Lockfile lock, Set<Scope> scopes, boolean requirePresent, JkBuild module) {
        List<Lockfile.Artifact> rows = ordered(lock, selected(lock, scopes), declaredExternalRoots(module, scopes));
        return resolveEntries(rows, requirePresent, effectiveLocator(lock));
    }

    /**
     * {@code selected} reordered for a module whose direct declarations are {@code directRoots}:
     * the rows the roots reach, in the breadth-first order of {@link #reachableArtifacts}, then
     * the rest in their given order. One row per module name, as {@code selected} already is.
     */
    static List<Lockfile.Artifact> ordered(
            Lockfile lock, List<Lockfile.Artifact> selected, Collection<String> directRoots) {
        Map<String, Lockfile.Artifact> byName = new LinkedHashMap<>();
        for (Lockfile.Artifact row : selected) byName.put(row.name(), row);
        List<Lockfile.Artifact> out = new ArrayList<>(selected.size());
        Set<String> placed = new HashSet<>();
        for (Lockfile.Artifact reached : reachableArtifacts(lock, directRoots)) {
            Lockfile.Artifact row = byName.get(reached.name());
            if (row != null && placed.add(row.name())) out.add(row);
        }
        for (Lockfile.Artifact row : selected) {
            if (placed.add(row.name())) out.add(row);
        }
        return out;
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
     * The external modules a consumer inherits from {@code sibling} in {@code scopes}: {@link
     * #declaredExternalRoots} without the sibling's optional dependencies, which are the sibling's
     * own as a POM's optional edges are.
     */
    public static Set<String> inheritedExternalRoots(JkBuild sibling, Set<Scope> scopes) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency dep : sibling.dependencies().of(scope)) {
                if (dep.optional() || dep.isWorkspace() || dep.isGit() || dep.isPath()) continue;
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
    public record Entry(
            Lockfile.Artifact artifact,
            @Nullable Path jar,
            @Nullable Path container) {

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
        return entriesFor(lock, scopes, false);
    }

    /** As {@link #entriesFor(Lockfile, Set)} with optional post-sync presence enforcement. */
    public List<Entry> entriesFor(Lockfile lock, Set<Scope> scopes, boolean requirePresent) {
        return resolveEntries(selected(lock, scopes), requirePresent, effectiveLocator(lock));
    }

    /**
     * The lock rows a classpath over {@code scopes} is made of, without locating a jar: one per
     * module, dual-scoped rows collapsed as {@link #entriesFor} collapses them, POM-only aliases
     * (rows with no checksum, never classpath jars) left out. What a bill of materials names.
     */
    public static List<Lockfile.Artifact> artifactsFor(Lockfile lock, Set<Scope> scopes) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : selected(lock, scopes)) {
            if (pkg.checksum() != null) out.add(pkg);
        }
        return out;
    }

    /**
     * Matches first, then dual-version rows collapsed (R5/R6 per-scope locks can emit the same
     * module at different versions for main vs test vs processor).
     */
    private static List<Lockfile.Artifact> selected(Lockfile lock, Set<Scope> scopes) {
        List<Lockfile.Artifact> matched = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.inAnyScope(scopes)) matched.add(pkg);
        }
        return selectPerModule(matched, scopes);
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
        return resolveEntries(selectPerModule(matched, scopes), false, effectiveLocator(lock));
    }

    /**
     * Honor the project's {@code [m2] integration = false} recorded in the lock: use a store-only
     * locator so {@code ~/.m2} is not consulted for the compile/runtime classpath, matching sync and
     * the reference gate in {@code MavenRepo}. Any module opting out disables it.
     */
    private ArtifactLocator effectiveLocator(Lockfile lock) {
        boolean projectOptOut = lock.modules().stream().anyMatch(m -> Boolean.FALSE.equals(m.m2integration()));
        if (!projectOptOut) return locator;
        ArtifactLocator storeOnly = storeOnlyLocator;
        if (storeOnly == null) {
            storeOnly = new ArtifactLocator(storeRoot);
            storeOnlyLocator = storeOnly;
        }
        return storeOnly;
    }

    /**
     * BFS from {@code rootModules} through lock {@code deps} edges: the roots in the order given,
     * then each row's edges by name, so the walk is the same however a row's edges were listed.
     * Roots that do not resolve in the lock are skipped (caller may still surface missing-dep
     * diagnostics elsewhere).
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
            List<String> edges = new ArrayList<>(pkg.deps());
            edges.sort(Comparator.naturalOrder());
            for (String depRef : edges) {
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

    private static Lockfile.@Nullable Artifact lookup(Map<String, Lockfile.Artifact> byKey, String moduleOrKey) {
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

    private List<Entry> resolveEntries(
            List<Lockfile.Artifact> selected, boolean requirePresent, ArtifactLocator locator) {
        List<Entry> result = new ArrayList<>(selected.size());
        List<String> missing = new ArrayList<>();
        for (Lockfile.Artifact pkg : selected) {
            String checksum = pkg.checksum();
            if (checksum == null) {
                // POM-only aliases (KMP roots, packaging=pom) legitimately have none — they are
                // not classpath jars. Soft-skip either way; requirePresent only enforces rows
                // that claim a sha256 (a miss there is a sync/store bug).
                Log.warn("jk: warning: lock row "
                        + pkg.name()
                        + "@"
                        + pkg.version()
                        + " has no checksum — skipped from classpath"
                        + " (POM-only alias, or incomplete lock; re-run `jk lock`)");
                continue;
            }
            Path jar = locate(locator, pkg);
            if (jar == null) {
                if (requirePresent) {
                    missing.add(pkg.displayCoord());
                    continue;
                }
                Log.warn("jk: warning: lock row "
                        + pkg.name()
                        + "@"
                        + pkg.version()
                        + " is not on disk — skipped from classpath (run `jk sync`)");
                continue;
            }
            if (pkg.isAar()) {
                String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
                try {
                    Path container = ExplodedArchives.explodeFile(new Cas(storeRoot), jar, hex);
                    Path classesJar = container.resolve("classes.jar");
                    result.add(new Entry(pkg, Files.isRegularFile(classesJar) ? classesJar : null, container));
                } catch (IOException e) {
                    throw new UncheckedIOException(pkg.name() + " v" + pkg.version() + ": " + e.getMessage(), e);
                }
                continue;
            }
            result.add(new Entry(pkg, jar));
        }
        if (!missing.isEmpty()) throw new IllegalStateException(notOnDisk(missing));
        return result;
    }

    /** Every checksummed row the store lacks, named in one line: the whole repair, not its first step. */
    private static String notOnDisk(List<String> coords) {
        return (coords.size() == 1
                        ? "dependency " + coords.get(0) + " is"
                        : "dependencies " + String.join(", ", coords) + " are")
                + " not on disk after sync — run `jk sync -F`";
    }

    /** {@link #resolved}-backed {@code locate}; see that field for why this is worth caching. */
    private @Nullable Path locate(ArtifactLocator loc, Lockfile.Artifact pkg) {
        String key = (loc == locator ? "m|" : "s|")
                + pkg.source()
                + '|'
                + pkg.name()
                + '|'
                + pkg.version()
                + '|'
                + pkg.checksumHex();
        Path hit = resolved.get(key);
        if (hit != null) return hit;
        Path found = loc.locate(pkg).orElse(null);
        if (found != null) resolved.put(key, found);
        return found;
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
