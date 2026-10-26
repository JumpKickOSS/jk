// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;

/**
 * One {@link StandardJavaFileManager} per thread, held across compiles rather than opened and closed
 * around each one.
 *
 * <p>A manager opened per compile re-opens and re-indexes every classpath entry, and that cost is
 * fixed per invocation — it does not shrink with the module. Measured on a 20-cpu host, a
 * <em>one-source</em> compile against 200 real jars takes 364 ms on Windows and 138 ms on Linux,
 * against 23 ms and 21 ms with an empty classpath: roughly 1.7 ms per jar on Windows against 0.6 ms
 * on Linux. Holding the manager keeps those jars open, so the next compile pays only for entries it
 * has not seen. A compile whose classpath shares 190 of its 200 entries with its predecessor drops
 * from 276 ms to 56 ms.
 *
 * <p>Reuse is bounded by what is on disk, not by the job: the engine keeps a worker for a short
 * linger after its job, so the next build's compile may get the manager the last one left. javac
 * lists a directory entry afresh on every task, so a class added to an upstream output directory
 * resolves, and one removed stops resolving. Everything else about an entry the manager remembers
 * as it first found it: an archive stays open on the file it opened, and an entry that was missing
 * stays missing (javac caches the absence until the manager closes). So an entry rewritten since (a
 * snapshot re-resolved, a local jar rebuilt) or one that has appeared since (a sibling's output
 * directory created after a compile named it) discards the manager. {@code
 * ReusedJavacFileManagerTest} pins these.
 *
 * <p>Per thread rather than static because the worker is single-threaded today but the protocol is
 * specified to allow concurrent items; {@link StandardJavaFileManager} is not thread-safe, so a
 * second compile thread must get its own rather than silently share this one.
 */
final class ReusedJavacFileManager {

    private static final ThreadLocal<ReusedJavacFileManager> PER_THREAD =
            ThreadLocal.withInitial(ReusedJavacFileManager::new);

    /**
     * The listener the manager is constructed with. A manager outlives any one compile, so it cannot
     * be handed that compile's collector directly — it forwards to whichever compile is running.
     */
    private final Relay relay = new Relay();

    private @Nullable StandardJavaFileManager fm;
    private @Nullable Charset encoding;

    /** Every option-set location a compile has declared on the held manager since it was built. */
    private final Set<StandardLocation> carried = EnumSet.noneOf(StandardLocation.class);

    /** Every path entry a compile on the held manager named, as it was on disk then. */
    private final Map<Path, EntryStamp> entries = new HashMap<>();

    private ReusedJavacFileManager() {}

    /**
     * The calling thread's manager, reporting file-manager diagnostics to {@code diags} until the
     * next call. A manager built for a different charset is discarded rather than reused, because
     * the charset a manager is constructed with is what decodes sources and cannot be changed after.
     *
     * <p>{@code declared} names the path locations the compile about to run sets through its own
     * options ({@link ProvenanceJavac#declaredLocations}). If the held manager still carries one the
     * compile never asked for, that manager is thrown away rather than handed over — see
     * {@link #staleLocation}.
     */
    static StandardJavaFileManager acquire(
            JavaCompiler javac,
            Charset encoding,
            DiagnosticListener<JavaFileObject> diags,
            Set<StandardLocation> declared) {
        return acquire(javac, encoding, diags, declared, List.of());
    }

    /**
     * As above, for a compile whose path options name {@code entries}: a held manager that saw one
     * of them as it was before a rewrite, or before it existed, is thrown away rather than handed
     * over.
     */
    static StandardJavaFileManager acquire(
            JavaCompiler javac,
            Charset encoding,
            DiagnosticListener<JavaFileObject> diags,
            Set<StandardLocation> declared,
            List<Path> entries) {
        return PER_THREAD.get().get(javac, encoding, diags, declared, entries);
    }

    /**
     * The option-set locations a held manager may carry over from an earlier compile, each one
     * reported by {@link StandardJavaFileManager#hasLocation} only once set. {@code SYSTEM_MODULES}
     * is not among them: a fresh manager already reports it set, defaulting to the running JDK, so
     * a carried {@code --system} is known only from the compile that declared it.
     */
    static final Set<StandardLocation> OPTION_LOCATIONS = EnumSet.of(
            StandardLocation.SOURCE_PATH,
            StandardLocation.MODULE_SOURCE_PATH,
            StandardLocation.ANNOTATION_PROCESSOR_PATH,
            StandardLocation.ANNOTATION_PROCESSOR_MODULE_PATH,
            StandardLocation.MODULE_PATH,
            StandardLocation.UPGRADE_MODULE_PATH,
            StandardLocation.PATCH_MODULE_PATH,
            StandardLocation.NATIVE_HEADER_OUTPUT);

    private StandardJavaFileManager get(
            JavaCompiler javac,
            Charset wanted,
            DiagnosticListener<JavaFileObject> diags,
            Set<StandardLocation> declared,
            List<Path> named) {
        relay.to = diags;
        Map<Path, EntryStamp> now = new HashMap<>();
        for (Path entry : named) now.put(entry, EntryStamp.of(entry));
        StandardJavaFileManager current = fm;
        if (current != null && wanted.equals(encoding) && !staleLocation(current, declared) && !rewritten(now)) {
            carried.addAll(declared);
            entries.putAll(now);
            return current;
        }
        close(current);
        StandardJavaFileManager fresh = javac.getStandardFileManager(relay, Locale.ROOT, wanted);
        fm = fresh;
        encoding = wanted;
        carried.clear();
        carried.addAll(declared);
        entries.clear();
        entries.putAll(now);
        return fresh;
    }

    /** Whether an entry the held manager may have seen is not what it was when last named. */
    private boolean rewritten(Map<Path, EntryStamp> now) {
        for (Map.Entry<Path, EntryStamp> e : now.entrySet()) {
            EntryStamp then = entries.get(e.getKey());
            if (then != null && !then.equals(e.getValue())) return true;
        }
        return false;
    }

    /**
     * An entry as it is on disk: a rewrite moves an archive's size, its mtime or, for a rename over
     * it, its file key. A directory is stamped by its existence alone, because javac lists it afresh
     * and a class written into it must not cost the manager.
     */
    record EntryStamp(long size, long mtimeMillis, @Nullable Object key) {
        static final EntryStamp MISSING = new EntryStamp(-1L, -1L, null);
        static final EntryStamp DIRECTORY = new EntryStamp(-2L, -2L, null);

        static EntryStamp of(Path entry) {
            try {
                BasicFileAttributes a = Files.readAttributes(entry, BasicFileAttributes.class);
                if (a.isDirectory()) return DIRECTORY;
                return new EntryStamp(a.size(), a.lastModifiedTime().toMillis(), a.fileKey());
            } catch (IOException e) {
                return MISSING;
            }
        }
    }

    /**
     * Whether the held manager carries a path location the compile about to run never asked for:
     * one an earlier compile declared, or one the manager reports set.
     *
     * <p>Such a manager has to be discarded rather than corrected, because a location cannot be put
     * back to "never set": clearing the processor path with a null leaves javac treating it as
     * declared and empty, which stops it falling back to the compile classpath for {@code -proc:full}
     * discovery. Leaving it alone instead is worse — the compile would silently run the previous
     * module's processors, resolve a type from the previous module's source path, or read its module
     * path, patches or system image, in a build that never asked for them. Discarding costs one
     * manager on a transition between a module with the location and one without, and nothing on a
     * run of either kind.
     */
    private boolean staleLocation(StandardJavaFileManager held, Set<StandardLocation> declared) {
        if (carried.contains(StandardLocation.PATCH_MODULE_PATH)) {
            return true;
        }
        for (StandardLocation location : carried) {
            if (!declared.contains(location)) return true;
        }
        for (StandardLocation location : OPTION_LOCATIONS) {
            if (!declared.contains(location) && held.hasLocation(location)) return true;
        }
        return false;
    }

    /**
     * Closing releases the open jars, which is the whole point of holding it, so this runs only when
     * a manager is being replaced. A manager still open when the worker exits is left to the process
     * teardown, which is force-kill — the handles must be safe to leak, and they are.
     */
    private void close(@Nullable StandardJavaFileManager stale) {
        if (stale == null) return;
        try {
            stale.close();
        } catch (IOException e) {
            // A manager being thrown away cannot fail the compile that is replacing it.
        }
    }

    private static final class Relay implements DiagnosticListener<JavaFileObject> {

        private volatile @Nullable DiagnosticListener<JavaFileObject> to;

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            DiagnosticListener<JavaFileObject> target = to;
            if (target != null) target.report(diagnostic);
        }
    }
}
