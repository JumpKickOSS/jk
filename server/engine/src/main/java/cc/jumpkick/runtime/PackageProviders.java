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
 * only when such a diagnostic occurs, and lists each lock jar once per store.
 */
public final class PackageProviders {

    /** The detail label the hint reads the coordinate from. */
    public static final String LABEL = "provided by:";

    static final String IN_LOCK = "in the lock, not on this module's compile classpath";
    static final String IN_CATALOG = "library catalog";

    private static final Pattern PACKAGE = Pattern.compile("package (\\S+) does not exist");

    private final List<ClasspathResolver.Entry> lockEntries;
    private final Set<Path> classpath;
    private final Path indexDir;
    private final LibraryCatalog catalog;

    /**
     * @param lockEntries every lock row with the jar it resolves to in the store
     * @param classpath this module's compile classpath; a row whose jar is on it is never named
     * @param indexDir where {@link PackageIndex} keeps each jar's package list
     */
    public PackageProviders(
            List<ClasspathResolver.Entry> lockEntries, List<Path> classpath, Path indexDir, LibraryCatalog catalog) {
        this.lockEntries = List.copyOf(lockEntries);
        this.classpath = new HashSet<>();
        for (Path p : classpath) this.classpath.add(p.toAbsolutePath().normalize());
        this.indexDir = indexDir;
        this.catalog = catalog;
    }

    /**
     * The providers a compile step can answer from: the plan's lock resolved against the artifact
     * store, {@code classpath} as what the step compiled against, the store's index directory and
     * the layered catalog. Null when the plan has no lock, in which case no diagnostic is enriched.
     */
    public static @Nullable PackageProviders forContext(TaskContext ctx, List<Path> classpath) {
        Optional<Lockfile> lock = ctx.get(BuildPlanner.LOCKFILE);
        if (lock.isEmpty()) return null;
        List<ClasspathResolver.Entry> entries =
                new ClasspathResolver(JkStores.storeCas()).entriesFor(lock.get(), EnumSet.allOf(Scope.class));
        return new PackageProviders(entries, classpath, JkStores.resolve(PackageIndex.DIR), LibraryCatalog.layered());
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
        String provider = provider(pkg);
        if (provider == null) return d;
        return new CompileResult.Diagnostic(
                d.severity(), d.source(), d.line(), d.column(), d.message() + "\n  " + LABEL + " " + provider, d.key());
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
     * The catalog module whose group is the longest dotted prefix of {@code pkg}; among modules of
     * that group, the one whose artifact name shares the most segments with the package.
     */
    private @Nullable String fromCatalog(String pkg) {
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
