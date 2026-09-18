// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.PackageIndex;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Names the coordinate that provides a package javac could not find. A {@code package does not
 * exist} diagnostic gains a {@code provided by:} line, indented the way javac indents {@code
 * symbol:}, naming a lock row whose jar holds the package and is not on this module's compile
 * classpath, or else the library catalog entry whose group prefixes the package. The lookup runs
 * only when such a diagnostic occurs, and lists each lock jar once per store. It reads the lock
 * rows the store holds; a row of a scope this build never synced is not a candidate and not a
 * warning.
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
    static final String IN_CATALOG = "library catalog";

    private static final Pattern PACKAGE = Pattern.compile("package (\\S+) does not exist");

    private final List<ClasspathResolver.Entry> lockEntries;
    private final List<Lockfile.Artifact> withoutFile;
    private final Set<Path> classpath;
    private final Path indexDir;
    private final LibraryCatalog catalog;

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
        this.lockEntries = List.copyOf(lockEntries);
        this.withoutFile = List.copyOf(withoutFile);
        this.classpath = new HashSet<>();
        for (Path p : classpath) this.classpath.add(p.toAbsolutePath().normalize());
        this.indexDir = indexDir;
        this.catalog = catalog;
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
                LibraryCatalog.layered());
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
        return new PackageProviders(
                resolver.entriesOnDisk(lock, EnumSet.allOf(Scope.class)),
                ClasspathResolver.lockedWithoutFile(lock, scopes),
                classpath,
                indexDir,
                catalog);
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
     * {@code g:a (where)}: the lock row off this module's classpath whose jar holds {@code pkg},
     * else the catalog module whose group prefixes it, else null.
     */
    public @Nullable String provider(String pkg) {
        String fromLock = fromLock(pkg);
        if (fromLock != null) return fromLock + " (" + IN_LOCK + ")";
        String fromCatalog = fromCatalog(pkg);
        return fromCatalog == null ? null : fromCatalog + " (" + IN_CATALOG + ")";
    }

    private @Nullable String fromLock(String pkg) {
        for (ClasspathResolver.Entry e : lockEntries) {
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
