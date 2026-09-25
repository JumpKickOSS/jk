// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.PackageIndex;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Names the coordinate that provides a package javac could not find. A {@code package does not
 * exist} diagnostic gains a {@code provided by:} line, indented the way javac indents {@code
 * symbol:}. The coordinate is, in order: a direct dependency the previous lock carried in this
 * compile's scopes and the current lock does not, when that dependency's closure holds the
 * package; a lock row whose jar holds the package and is not on this module's compile classpath;
 * a jar in the local artifact store; the Boot starter a Boot project would declare; else the
 * library catalog. The lookup runs only when such a diagnostic occurs. It reads jars the store
 * holds and does not fetch; a row of a scope this build never synced is not a candidate and not a
 * warning. At most two coordinates are named.
 */
public final class PackageProviders {

    /** The detail label the hint reads the coordinate from. */
    public static final String LABEL = "provided by:";

    /**
     * The detail label under which a missing-package error names the rows of this module's lock
     * that stand for a POM alone — a {@code packaging=pom} module whose jar no repository served
     * when the lock was written — and so put nothing on the classpath the package was missing from.
     */
    public static final String WITHOUT_FILE = "locked without a file:";

    static final String IN_LOCK = "in the lock, not on this module's compile classpath";
    static final String REMOVED = "removed from this module's dependencies";
    static final String IN_STORE = "in the local artifact store";
    static final String IN_CATALOG = "library catalog";

    private static final Pattern PACKAGE = Pattern.compile("package (\\S+) does not exist");

    private final List<ClasspathResolver.Entry> lockEntries;
    private final List<ClasspathResolver.Entry> previousEntries;
    private final List<Lockfile.Artifact> previousArtifacts;
    private final List<Lockfile.Artifact> currentArtifacts;
    private final Set<Scope> compileScopes;
    private final List<Lockfile.Artifact> withoutFile;
    private final Set<Path> classpath;
    private final Path indexDir;
    private final LibraryCatalog catalog;
    private final @Nullable Path store;
    private final int bootMajor;
    private @Nullable List<String> removedRoots;
    private @Nullable Map<String, List<String>> previousEdges;

    /**
     * @param lockEntries every lock row with the jar it resolves to in the store
     * @param withoutFile the jar-typed lock rows that stand for a POM alone ({@link
     *     ClasspathResolver#lockedWithoutFile}), named under every missing-package error
     * @param classpath this module's compile classpath; a row whose jar is on it is never named
     * @param indexDir where {@link PackageIndex} keeps each jar's package list
     */
    public PackageProviders(
            List<ClasspathResolver.Entry> lockEntries,
            List<Lockfile.Artifact> withoutFile,
            List<Path> classpath,
            Path indexDir,
            LibraryCatalog catalog) {
        this(
                lockEntries,
                List.of(),
                List.of(),
                List.of(),
                Set.of(),
                withoutFile,
                classpath,
                indexDir,
                catalog,
                null,
                0);
    }

    /**
     * @param previousEntries jars of the lock this one replaced, for a package the current lock no
     *     longer carries
     * @param previousArtifacts every row of that lock, including a POM-only starter
     * @param currentArtifacts every row of the lock this compile resolved
     * @param compileScopes the scopes this compile read; a coordinate that left them is the removal
     * @param store the artifact store to search when neither lock knows the package; null skips it
     * @param bootMajor the project's Spring Boot major, or 0 when it does not use Boot
     */
    public PackageProviders(
            List<ClasspathResolver.Entry> lockEntries,
            List<ClasspathResolver.Entry> previousEntries,
            List<Lockfile.Artifact> previousArtifacts,
            List<Lockfile.Artifact> currentArtifacts,
            Set<Scope> compileScopes,
            List<Lockfile.Artifact> withoutFile,
            List<Path> classpath,
            Path indexDir,
            LibraryCatalog catalog,
            @Nullable Path store,
            int bootMajor) {
        this.lockEntries = List.copyOf(lockEntries);
        this.previousEntries = List.copyOf(previousEntries);
        this.previousArtifacts = List.copyOf(previousArtifacts);
        this.currentArtifacts = List.copyOf(currentArtifacts);
        this.compileScopes = EnumSet.noneOf(Scope.class);
        this.compileScopes.addAll(compileScopes);
        this.withoutFile = List.copyOf(withoutFile);
        this.classpath = new HashSet<>();
        for (Path p : classpath) this.classpath.add(p.toAbsolutePath().normalize());
        this.indexDir = indexDir;
        this.catalog = catalog;
        this.store = store;
        this.bootMajor = bootMajor;
    }

    /** As above for a lock with no row standing for a POM alone. */
    public PackageProviders(
            List<ClasspathResolver.Entry> lockEntries, List<Path> classpath, Path indexDir, LibraryCatalog catalog) {
        this(lockEntries, List.of(), classpath, indexDir, catalog);
    }

    /**
     * The providers a compile step can answer from: the plan's lock resolved against the artifact
     * store, {@code classpath} as what the step compiled against, the store's index directory and
     * the layered catalog. Null when the plan has no lock, in which case no diagnostic is enriched.
     */
    public static @Nullable PackageProviders forContext(TaskContext ctx, List<Path> classpath, Set<Scope> scopes) {
        Optional<Lockfile> lock = ctx.get(BuildPlanner.LOCKFILE);
        if (lock.isEmpty()) return null;
        return of(
                new ClasspathResolver(JkStores.storeCas()),
                lock.get(),
                classpath,
                scopes,
                JkStores.resolve(PackageIndex.DIR),
                LibraryCatalog.layered(),
                projectId(ctx, lock.get()));
    }

    /**
     * The id the previous lock was filed under. The lock object on the plan sometimes has none —
     * the resolve that just wrote the file returns the graph without the id the writer recorded —
     * so the lock file's own head is the fallback.
     */
    private static @Nullable String projectId(TaskContext ctx, Lockfile lock) {
        if (lock.projectId() != null && !lock.projectId().isBlank()) return lock.projectId();
        Optional<BuildLayout> layout = ctx.get(BuildPlanner.LAYOUT);
        if (layout.isEmpty()) return null;
        return ProjectIdentity.recordedId(LockPaths.lockFile(layout.get().moduleRoot()))
                .orElse(null);
    }

    /**
     * The providers over every scope of {@code lock} as {@code resolver}'s store holds it — a row
     * the store lacks, a scope this build never synced, is simply not a candidate, and nothing is
     * logged about it since the lookup answers a diagnostic and never materializes anything — and
     * the rows without a file in {@code scopes}, the ones this compile read.
     */
    public static PackageProviders of(
            ClasspathResolver resolver,
            Lockfile lock,
            List<Path> classpath,
            Set<Scope> scopes,
            Path indexDir,
            LibraryCatalog catalog) {
        return of(resolver, lock, classpath, scopes, indexDir, catalog, lock.projectId());
    }

    private static PackageProviders of(
            ClasspathResolver resolver,
            Lockfile lock,
            List<Path> classpath,
            Set<Scope> scopes,
            Path indexDir,
            LibraryCatalog catalog,
            @Nullable String projectId) {
        Lockfile previous = LockHistory.read(projectId);
        List<Lockfile.Artifact> previousArtifacts = previous == null ? List.of() : previous.artifacts();
        List<String> roots = RemovedRoots.of(previousArtifacts, lock.artifacts(), scopes);
        List<ClasspathResolver.Entry> previousEntries = roots.isEmpty() || previous == null
                ? List.of()
                : resolver.entriesOnDisk(previous, EnumSet.allOf(Scope.class));
        return new PackageProviders(
                resolver.entriesOnDisk(lock, EnumSet.allOf(Scope.class)),
                previousEntries,
                previousArtifacts,
                lock.artifacts(),
                scopes,
                ClasspathResolver.lockedWithoutFile(lock, scopes),
                classpath,
                indexDir,
                catalog,
                JkStores.store(),
                bootMajor(lock.artifacts(), previousArtifacts));
    }

    /** Boot's major from {@code spring-boot} or a starter, preferring the versioned platform artifact. */
    private static int bootMajor(List<Lockfile.Artifact> current, List<Lockfile.Artifact> previous) {
        int fromPlatform = 0;
        int fromStarter = 0;
        for (List<Lockfile.Artifact> rows : List.of(current, previous)) {
            for (Lockfile.Artifact a : rows) {
                String ga = RemovedRoots.coordinate(a.name());
                if (ga.equals("org.springframework.boot:spring-boot")
                        || ga.equals("org.springframework.boot:spring-boot-dependencies")) {
                    fromPlatform = Math.max(fromPlatform, BootStarters.major(a.version()));
                } else if (ga.startsWith("org.springframework.boot:spring-boot-starter") && fromStarter == 0) {
                    fromStarter = BootStarters.major(a.version());
                }
            }
        }
        return fromPlatform > 0 ? fromPlatform : fromStarter;
    }

    /** {@code diagnostics} with a {@code provided by:} line under each missing-package error that has a provider. */
    public List<CompileResult.Diagnostic> enrich(List<CompileResult.Diagnostic> diagnostics) {
        List<CompileResult.Diagnostic> out = new ArrayList<>(diagnostics.size());
        for (CompileResult.Diagnostic d : diagnostics) out.add(enrich(d));
        return out;
    }

    private CompileResult.Diagnostic enrich(CompileResult.Diagnostic d) {
        String pkg = missingPackage(d);
        if (pkg == null) return d;
        StringBuilder details = new StringBuilder();
        String provider = provider(pkg);
        if (provider != null) details.append("\n  ").append(LABEL).append(' ').append(provider);
        if (!withoutFile.isEmpty())
            details.append("\n  ").append(WITHOUT_FILE).append(' ').append(withoutFile());
        if (details.isEmpty()) return d;
        return new CompileResult.Diagnostic(
                d.severity(), d.source(), d.line(), d.column(), d.message() + details, d.key());
    }

    /**
     * {@code g:a:v (source), …}: every row of this module's lock that stands for a POM alone. A
     * package one of them was expected to provide is missing because its jar was never fetched —
     * the repository that publishes it was not asked, or answered not-found when the lock was
     * written.
     */
    String withoutFile() {
        List<String> named = new ArrayList<>();
        for (Lockfile.Artifact a : withoutFile) named.add(a.displayCoord() + " (" + a.source() + ")");
        return String.join(", ", named);
    }

    /** The package a {@code compiler.err.doesnt.exist} error names, or null for any other diagnostic. */
    static @Nullable String missingPackage(CompileResult.Diagnostic d) {
        if (d.severity() != CompileResult.Severity.ERROR) return null;
        if (!d.key().isEmpty() && !d.key().equals("compiler.err.doesnt.exist")) return null;
        Matcher m = PACKAGE.matcher(d.message());
        return m.find() ? m.group(1) : null;
    }

    /**
     * {@code g:a (where)}, or two coordinates joined by {@code ", "}. A dependency the previous
     * lock had in this compile's scopes wins over the jar that holds the package, then a jar in
     * the store, then a Boot starter when the project uses Boot, then the catalog.
     */
    public @Nullable String provider(String pkg) {
        String leaf = fromLock(lockEntries, pkg);
        String removed = fromRemoved(pkg, leaf);
        if (removed != null) return removed + " (" + REMOVED + ")";
        if (leaf != null) return leaf + " (" + IN_LOCK + ")";
        if (store != null) {
            List<String> stored = StorePackages.find(store, pkg, indexDir);
            if (!stored.isEmpty()) return String.join(", ", stored) + " (" + IN_STORE + ")";
        }
        if (bootMajor > 0) {
            String starter = BootStarters.coordinate(pkg, bootMajor);
            if (starter != null) return starter + " (" + IN_CATALOG + ")";
        }
        String fromCatalog = fromCatalog(pkg);
        return fromCatalog == null ? null : fromCatalog + " (" + IN_CATALOG + ")";
    }

    /**
     * The removed root whose closure holds {@code pkg}. {@code leaf} is the current lock's jar, when
     * one does; otherwise the previous lock's jars are listed.
     */
    private @Nullable String fromRemoved(String pkg, @Nullable String leaf) {
        List<String> roots = removedRoots();
        if (roots.isEmpty()) return null;
        List<String> leaves = new ArrayList<>();
        if (leaf != null) leaves.add(leaf);
        if (leaves.isEmpty()) {
            String previous = fromLock(previousEntries, pkg);
            if (previous != null) leaves.add(previous);
        }
        if (leaves.isEmpty()) return null;
        Map<String, List<String>> edges = edges();
        List<String> hit = new ArrayList<>();
        for (String root : roots) {
            boolean reaches = false;
            for (String candidate : leaves) {
                if (RemovedRoots.reaches(root, candidate, edges)) {
                    reaches = true;
                    break;
                }
            }
            if (reaches) hit.add(root);
        }
        if (hit.isEmpty()) return null;
        hit.sort((a, b) -> Integer.compare(rank(b, pkg), rank(a, pkg)));
        if (hit.size() > 2) hit = hit.subList(0, 2);
        return String.join(", ", hit);
    }

    /** A starter outranks the jar it brings in, and a non-test starter outranks a test one. */
    private static int rank(String coordinate, String pkg) {
        int colon = coordinate.indexOf(':');
        String artifact = colon < 0 ? coordinate : coordinate.substring(colon + 1);
        int score = 0;
        if (artifact.contains("starter") && !artifact.endsWith("-test")) score += 100;
        else if (artifact.contains("starter")) score += 40;
        for (String piece : artifact.split("-")) {
            if (piece.length() > 2 && pkg.contains(piece)) score += 5;
        }
        return score;
    }

    private List<String> removedRoots() {
        if (removedRoots == null) {
            removedRoots = RemovedRoots.of(previousArtifacts, currentArtifacts, compileScopes);
        }
        return removedRoots;
    }

    private Map<String, List<String>> edges() {
        if (previousEdges == null) previousEdges = RemovedRoots.edges(previousArtifacts);
        return previousEdges;
    }

    private @Nullable String fromLock(List<ClasspathResolver.Entry> entries, String pkg) {
        for (ClasspathResolver.Entry e : entries) {
            Path jar = e.jar();
            if (jar == null || classpath.contains(jar.toAbsolutePath().normalize())) continue;
            Lockfile.Artifact a = e.artifact();
            if (PackageIndex.packagesOf(jar, PackageIndex.hex(a.checksum()), indexDir)
                    .contains(pkg)) {
                return a.moduleGroup() + ":" + a.moduleArtifact();
            }
        }
        return null;
    }

    /**
     * The catalog's {@code [packages]} answer when a table names a prefix of {@code pkg}; else the
     * catalog module whose group is the longest dotted prefix of {@code pkg} — among modules of
     * that group, the one whose artifact name shares the most segments with the package.
     */
    private @Nullable String fromCatalog(String pkg) {
        LibraryCatalog.Module named = catalog.moduleForPackage(pkg).orElse(null);
        if (named != null) return named.moduleKey();
        LibraryCatalog.Module best = null;
        int bestGroup = -1;
        int bestShared = -1;
        Set<String> segments = new HashSet<>(List.of(pkg.split("\\.")));
        for (String name : catalog.names()) {
            LibraryCatalog.Module m = catalog.lookup(name).orElse(null);
            if (m == null || !(pkg.equals(m.group()) || pkg.startsWith(m.group() + "."))) continue;
            int shared = 0;
            for (String piece : m.artifact().split("[-_.]")) if (segments.contains(piece)) shared++;
            if (m.group().length() > bestGroup || (m.group().length() == bestGroup && shared > bestShared)) {
                best = m;
                bestGroup = m.group().length();
                bestShared = shared;
            }
        }
        return best == null ? null : best.moduleKey();
    }
}
