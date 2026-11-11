// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
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
 * A bounded pool of {@link StandardJavaFileManager}s, each checked out for one compile and held
 * across compiles rather than opened and closed around each one.
 *
 * <p>A manager opened per compile re-opens and re-indexes every classpath entry, and that cost is
 * fixed per invocation — it does not shrink with the module. Measured on a 20-cpu host, a
 * <em>one-source</em> compile against 200 real jars takes 364 ms on Windows and 138 ms on Linux,
 * against 23 ms and 21 ms with an empty classpath: roughly 1.7 ms per jar on Windows against 0.6 ms
 * on Linux. Holding the manager keeps those jars open, so the next compile pays only for entries it
 * has not seen. A compile whose classpath shares 190 of its 200 entries with its predecessor drops
 * from 276 ms to 56 ms.
 *
 * <p>The worker compiles on one thread per core, and {@link StandardJavaFileManager} is not
 * thread-safe, so a manager belongs to one compile at a time: {@link #acquire} checks one out and
 * {@link Lease#close} returns it. A compile gets the idle manager that has already named the most of
 * its path entries; at most {@link #CAP} idle managers are kept, and the one returned longest ago is
 * closed past that, so the jars held open stay bounded however many threads compile.
 *
 * <p>Reuse is bounded by what is on disk, not by the job: the engine keeps a worker for a short
 * linger after its job, so the next build's compile may get a manager the last one left. javac
 * lists a directory entry afresh on every task, so a class added to an upstream output directory
 * resolves, and one removed stops resolving. Everything else about an entry the manager remembers
 * as it first found it: an archive stays open on the file it opened, and an entry that was missing
 * stays missing (javac caches the absence until the manager closes). So an entry rewritten since (a
 * snapshot re-resolved, a local jar rebuilt) or one that has appeared since (a sibling's output
 * directory created after a compile named it) discards the manager. {@code
 * ReusedJavacFileManagerTest} pins these.
 */
final class ReusedJavacFileManager {

    /** Idle managers kept between compiles; each holds every archive it has opened. */
    static final int CAP = 4;

    private static final ReusedJavacFileManager SHARED = new ReusedJavacFileManager(CAP);

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

    private final int cap;

    /** Managers no compile holds, the most recently returned first. */
    private final Deque<Held> idle = new ArrayDeque<>();

    ReusedJavacFileManager(int cap) {
        this.cap = cap;
    }

    /**
     * Checks a manager out of the worker's pool for one compile, reporting file-manager diagnostics
     * to {@code diags} until the lease is closed. A manager built for another charset is never
     * handed over, because the charset a manager is constructed with is what decodes sources and
     * cannot be changed after.
     *
     * <p>{@code declared} names the path locations the compile sets through its own options
     * ({@link ProvenanceJavac#declaredLocations}); a manager that carries one the compile never
     * asked for is passed over — see {@link Held#staleLocation}. {@code entries} are the path
     * entries the options name and {@code release} the release options they spell: a manager that
     * saw one of the entries before a rewrite, or before it existed, is closed, and one that compiled
     * at another release is passed over.
     */
    static Lease acquire(
            JavaCompiler javac,
            Charset encoding,
            DiagnosticListener<JavaFileObject> diags,
            Set<StandardLocation> declared,
            List<Path> entries,
            String release) {
        return SHARED.checkout(javac, encoding, diags, declared, entries, release);
    }

    Lease checkout(
            JavaCompiler javac,
            Charset encoding,
            DiagnosticListener<JavaFileObject> diags,
            Set<StandardLocation> declared,
            List<Path> named,
            String release) {
        Map<Path, EntryStamp> now = new HashMap<>();
        for (Path entry : named) now.put(entry, EntryStamp.of(entry));
        Held chosen = null;
        synchronized (this) {
            int best = -1;
            for (Iterator<Held> it = idle.iterator(); it.hasNext(); ) {
                Held held = it.next();
                if (held.rewritten(now)) {
                    it.remove();
                    held.close();
                    continue;
                }
                if (!held.encoding.equals(encoding) || !held.release.equals(release) || held.staleLocation(declared)) {
                    continue;
                }
                int overlap = held.overlap(now);
                if (overlap > best) {
                    best = overlap;
                    chosen = held;
                }
            }
            if (chosen != null) idle.remove(chosen);
        }
        if (chosen == null) chosen = new Held(javac, encoding, release);
        chosen.carried.addAll(declared);
        chosen.entries.putAll(now);
        chosen.relay.to = diags;
        return new Lease(chosen);
    }

    /**
     * Puts {@code held} back as the most recently returned, closing the oldest past the cap. A
     * manager that carried {@code --patch-module} is never handed over again, so it is closed here.
     */
    private void giveBack(Held held) {
        held.relay.to = null;
        if (held.carried.contains(StandardLocation.PATCH_MODULE_PATH)) {
            held.close();
            return;
        }
        Held evicted = null;
        synchronized (this) {
            idle.addFirst(held);
            if (idle.size() > cap) evicted = idle.removeLast();
        }
        if (evicted != null) evicted.close();
    }

    /** Test seam: how many managers sit idle in this pool. */
    synchronized int idleCount() {
        return idle.size();
    }

    /** One compile's hold on a manager; closing it returns the manager to the pool, not to javac. */
    final class Lease implements AutoCloseable {
        private @Nullable Held held;

        private Lease(Held held) {
            this.held = held;
        }

        StandardJavaFileManager manager() {
            Held h = held;
            if (h == null) throw new IllegalStateException("file manager lease already returned");
            return h.fm;
        }

        @Override
        public void close() {
            Held h = held;
            held = null;
            if (h != null) giveBack(h);
        }
    }

    /** A manager and what the compiles that ran on it declared and named. */
    private static final class Held {
        /**
         * The listener the manager is constructed with. A manager outlives any one compile, so it
         * cannot be handed that compile's collector directly — it forwards to whichever compile holds it.
         */
        final Relay relay = new Relay();

        final StandardJavaFileManager fm;
        final Charset encoding;

        /**
         * The release options the manager's compiles ran under. javac opens each archive with the
         * multi-release view of the target at first open and keeps it until the manager closes, so a
         * compile at another release must not inherit that view.
         */
        final String release;

        /** Every option-set location a compile has declared on the manager since it was built. */
        final Set<StandardLocation> carried = EnumSet.noneOf(StandardLocation.class);

        /** Every path entry a compile on the manager named, as it was on disk then. */
        final Map<Path, EntryStamp> entries = new HashMap<>();

        Held(JavaCompiler javac, Charset encoding, String release) {
            this.fm = javac.getStandardFileManager(relay, Locale.ROOT, encoding);
            this.encoding = encoding;
            this.release = release;
        }

        /** Whether an entry the manager may have seen is not what it was when last named. */
        boolean rewritten(Map<Path, EntryStamp> now) {
            for (Map.Entry<Path, EntryStamp> e : now.entrySet()) {
                EntryStamp then = entries.get(e.getKey());
                if (then != null && !then.equals(e.getValue())) return true;
            }
            return false;
        }

        /** How many of the compile's entries the manager has already named. */
        int overlap(Map<Path, EntryStamp> now) {
            int n = 0;
            for (Path entry : now.keySet()) {
                if (entries.containsKey(entry)) n++;
            }
            return n;
        }

        /**
         * Whether the manager carries a path location the compile about to run never asked for: one
         * an earlier compile declared, or one the manager reports set.
         *
         * <p>Such a manager is passed over rather than corrected, because a location cannot be put
         * back to "never set": clearing the processor path with a null leaves javac treating it as
         * declared and empty, which stops it falling back to the compile classpath for {@code
         * -proc:full} discovery. Handing it over instead is worse — the compile would silently run the
         * previous module's processors, resolve a type from the previous module's source path, or
         * read its module path, patches or system image, in a build that never asked for them.
         */
        boolean staleLocation(Set<StandardLocation> declared) {
            for (StandardLocation location : carried) {
                if (!declared.contains(location)) return true;
            }
            for (StandardLocation location : OPTION_LOCATIONS) {
                if (!declared.contains(location) && fm.hasLocation(location)) return true;
            }
            return false;
        }

        /**
         * Closing releases the open jars, which is the whole point of holding it, so this runs only
         * when a manager is evicted or stale. A manager still open when the worker exits is left to
         * the process teardown, which is force-kill — the handles must be safe to leak, and they are.
         */
        void close() {
            try {
                fm.close();
            } catch (IOException e) {
                // A manager being thrown away cannot fail the compile that is replacing it.
            }
        }
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

    private static final class Relay implements DiagnosticListener<JavaFileObject> {

        private volatile @Nullable DiagnosticListener<JavaFileObject> to;

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            DiagnosticListener<JavaFileObject> target = to;
            if (target != null) target.report(diagnostic);
        }
    }
}
