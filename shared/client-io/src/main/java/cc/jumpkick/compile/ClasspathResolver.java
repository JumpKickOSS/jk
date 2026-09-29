// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.ExplodedArchives;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
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
 * blobs. A workspace lock holds every member's rows, so a classpath handed to a compiler, a JVM or
 * a packager is a module's ({@link #classpathFor(Lockfile, Set, boolean, JkBuild, Path)}): the rows
 * its own roots and its siblings' reach ({@link ModuleRoots}), never the whole lock. The
 * manifest-less overloads list every row in the scopes, in lock order, and serve bills of
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
     * A module's classpath over {@code scopes}: the rows the module's own roots reach, in the
     * order Maven hands javac and the JVM — its declarations first, in manifest order ({@code
     * [dependencies]} before {@code [provided-dependencies]} and the test tables), then their
     * transitives breadth-first through the lock graph — followed by the rows its workspace
     * siblings pass on, walked the same way. A row nothing the module depends on reaches is not
     * on it, though another member of the workspace put it in the lock. A package two jars carry
     * resolves to the jar the module declared.
     *
     * @param moduleDir the module's directory, which places it in its workspace and names the
     *     language runtime its sources need
     */
    public List<Path> classpathFor(
            Lockfile lock, Set<Scope> scopes, boolean requirePresent, JkBuild module, Path moduleDir) {
        List<Path> result = new ArrayList<>(lock.artifacts().size());
        for (Entry entry : entriesFor(lock, scopes, requirePresent, module, moduleDir)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /** As {@link #classpathFor(Lockfile, Set, boolean, JkBuild, Path)}, each path paired with its lock row. */
    public List<Entry> entriesFor(
            Lockfile lock, Set<Scope> scopes, boolean requirePresent, JkBuild module, Path moduleDir) {
        return resolveEntries(
                lock, moduleRows(lock, scopes, module, moduleDir), Missing.of(requirePresent), effectiveLocator(lock));
    }

    /**
     * The rows of a module's classpath over {@code scopes}, one per module name: its own roots'
     * closure within the rows {@code scopes} select, then its siblings' closure within the rows of
     * the scopes a sibling passes on ({@link ModuleRoots#inheritedScopes}).
     */
    static List<Lockfile.Artifact> moduleRows(Lockfile lock, Set<Scope> scopes, JkBuild module, Path moduleDir) {
        Map<String, Lockfile.Artifact> rows = new LinkedHashMap<>();
        for (Lockfile.Artifact row :
                reachableArtifacts(selected(lock, scopes), ModuleRoots.own(module, moduleDir, scopes))) {
            rows.putIfAbsent(row.name(), row);
        }
        Set<String> inherited = ModuleRoots.inherited(module, moduleDir, scopes);
        if (!inherited.isEmpty()) {
            List<Lockfile.Artifact> passed = selected(lock, ModuleRoots.inheritedScopes(scopes));
            for (Lockfile.Artifact row : reachableArtifacts(passed, inherited)) rows.putIfAbsent(row.name(), row);
        }
        return new ArrayList<>(rows.values());
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
        return resolveEntries(lock, selected(lock, scopes), Missing.of(requirePresent), effectiveLocator(lock));
    }

    /**
     * The rows in {@code scopes} whose jar the store holds, and nothing said about the rest. For a
     * reader that only consults what is on disk — a diagnostic naming the jar that carries a
     * package — a row of a scope this build never synced is not a shortfall worth a warning.
     */
    public List<Entry> entriesOnDisk(Lockfile lock, Set<Scope> scopes) {
        return resolveEntries(lock, selected(lock, scopes), Missing.SKIP, effectiveLocator(lock));
    }

    /** What a checksummed row the store lacks does to the resolve. */
    private enum Missing {
        /** Skipped with a warning naming the row: a forecast or explain on a cold store. */
        WARN,
        /** Fails the resolve naming every such row: a classpath built after {@code resolve-deps}. */
        FAIL,
        /** Left out silently: a reader of what is on disk. */
        SKIP;

        static Missing of(boolean requirePresent) {
            return requirePresent ? FAIL : WARN;
        }
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
     * BFS from {@code rootModules} through the {@code deps} edges of {@code rows}: the roots in the
     * order given, then each row's edges by name, so the walk is the same however a row's edges
     * were listed. A root or edge with no row among {@code rows} is skipped (caller may still
     * surface missing-dep diagnostics elsewhere).
     */
    static List<Lockfile.Artifact> reachableArtifacts(List<Lockfile.Artifact> rows, Collection<String> rootModules) {
        if (rootModules.isEmpty()) return List.of();
        Map<String, Lockfile.Artifact> byKey = indexArtifacts(rows);
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
    static Map<String, Lockfile.Artifact> indexArtifacts(List<Lockfile.Artifact> rows) {
        Map<String, Lockfile.Artifact> result = new HashMap<>();
        for (Lockfile.Artifact pkg : rows) {
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
            Lockfile lock, List<Lockfile.Artifact> selected, Missing missing, ArtifactLocator locator) {
        List<Entry> result = new ArrayList<>(selected.size());
        List<String> absent = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();
        for (Lockfile.Artifact pkg : selected) {
            String checksum = pkg.checksum();
            if (checksum == null) {
                // A row without a file by design — a BOM, an aggregator, a relocation stub, a KMP
                // root — is not a classpath jar and says so on the row. One that says nothing pins
                // a jar nobody fetched: a classpath built to compile against fails on it by name.
                if (pkg.pomOnly()) continue;
                if (missing == Missing.SKIP) continue;
                if (missing == Missing.FAIL) {
                    unpinned.add(pkg.displayCoord());
                    continue;
                }
                Log.warn("jk: warning: lock row " + pkg.name() + "@" + pkg.version()
                        + " pins no checksum and names no POM-only file — skipped from classpath; re-run `jk lock`");
                continue;
            }
            Path jar = locate(locator, pkg);
            if (jar == null) {
                if (missing == Missing.SKIP) continue;
                if (missing == Missing.FAIL) {
                    absent.add(pkg.displayCoord());
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
        if (!unpinned.isEmpty()) throw new IllegalStateException(noFile(unpinned));
        if (!absent.isEmpty()) throw new IllegalStateException(notOnDisk(absent));
        return result;
    }

    /**
     * Every row that pins no checksum and is not a POM-only row, named in one line: no jar was
     * fetched for it when the lock was written, so the compile would run without it.
     */
    private static String noFile(List<String> coords) {
        return (coords.size() == 1
                        ? "dependency " + coords.get(0)
                                + " has no file: its lock row pins no checksum and names no POM-only file"
                        : "dependencies " + String.join(", ", coords)
                                + " have no file: their lock rows pin no checksum and name no POM-only file")
                + " — run `jk lock`: a lock written before rows named the POM or module file they stand for"
                + " is rewritten with them, and a jar the lock still cannot find fails the lock naming the"
                + " repositories asked";
    }

    /**
     * The jar-typed rows in {@code scopes} that stand for a POM alone — a {@code packaging=pom}
     * module or aggregator with no jar beside it, a relocation stub — and so put nothing on a
     * classpath. A compile that fails on a package one of them was expected to provide names them.
     * A BOM ({@code pom} type) and a Kotlin multiplatform root (whose {@code -jvm} row holds the
     * bytes) are not among them.
     */
    public static List<Lockfile.Artifact> lockedWithoutFile(Lockfile lock, Set<Scope> scopes) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : selected(lock, scopes)) {
            String path = pkg.path();
            if (pkg.checksum() == null && path != null && path.endsWith(".pom")) out.add(pkg);
        }
        return out;
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

    /** The processor-path scopes: a filter of these alone prefers the processor graph's rows. */
    public static final Set<Scope> PROCESSOR_PATH = EnumSet.of(Scope.PROCESSOR, Scope.TEST_PROCESSOR);

    /** One jar per module when dual-scoped; prefer processor, then test dual, else main/runtime. */
    static List<Lockfile.Artifact> selectPerModule(List<Lockfile.Artifact> matched, Set<Scope> scopes) {
        Map<String, Lockfile.Artifact> best = new LinkedHashMap<>();
        Map<String, Integer> bestScore = new HashMap<>();
        boolean processorOnlyFilter = !scopes.isEmpty() && PROCESSOR_PATH.containsAll(scopes);
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
        boolean hasProc = sc.contains(Scope.PROCESSOR) || sc.contains(Scope.TEST_PROCESSOR);
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
