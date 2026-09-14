// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import sbt.internal.inc.ExternalLookup;
import sbt.internal.inc.Locate;
import scala.Option;
import scala.collection.immutable.Set;
import xsbti.FileConverter;
import xsbti.Logger;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.VirtualFileRef;
import xsbti.api.AnalyzedClass;
import xsbti.compile.Changes;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.DefaultExternalHooks;
import xsbti.compile.DefinesClass;
import xsbti.compile.ExternalHooks;
import xsbti.compile.FileHash;
import xsbti.compile.IncOptions;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.analysis.ReadStamps;
import xsbti.compile.analysis.Stamp;

/**
 * The parts of Zinc's {@code Setup}/{@code Inputs} jk supplies: the per-entry analysis lookup, a
 * silent logger, no extra key/value pairs, and the path-to-{@link VirtualFile} lift. Each is a Zinc
 * SPI obligation with little jk decision in it — grouped because the alternative is four files
 * whose whole content is "we have nothing to say here".
 */
final class ZincSetup {

    private ZincSetup() {}

    /**
     * Per-entry analysis: the producer's, for an entry another jk compile wrote ({@link
     * ClasspathAnalyses}); none for a Maven jar or the JDK. jk compiles one module per Zinc
     * session, so this is how a session sees what the sessions before it learned.
     */
    static final class ClasspathLookup implements PerClasspathEntryLookup {
        private final ClasspathAnalyses producers;
        private final FileConverter converter;

        ClasspathLookup(ClasspathAnalyses producers, FileConverter converter) {
            this.producers = producers;
            this.converter = converter;
        }

        @Override
        public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
            if (producers.isEmpty()) return Optional.empty();
            return producers.analysis(converter.toPath(classpathEntry)).map(a -> a);
        }

        @Override
        public DefinesClass definesClass(VirtualFile classpathEntry) {
            if ("rt.jar".equals(classpathEntry.name())) return name -> false;
            return Locate.definesClass(classpathEntry);
        }
    }

    /**
     * Incremental options whose classpath hash sees inside directory entries.
     *
     * <p>Zinc's library-change detection is built for sbt, where a dependency that lives in a
     * directory is another sub-project with its own analysis: {@code ClasspathCache} hashes a
     * directory entry to a constant, so an "unchanged classpath" makes Zinc consult {@link
     * ClasspathLookup#analysis} for every library class instead of comparing stamps, and jk answers
     * "no analysis" — a class rewritten in place inside {@code classes/main} (a test compile's own
     * main output, or an upstream module wired as a directory) is invisible to the dependent
     * compile. Hashing a directory by its listing (path, size, mtime of every file) flips the
     * classpath hash whenever its contents move, which sends Zinc down its origin-lookup path: find
     * the entry that now defines the class, compare that class file's stamp with the recorded one.
     * That path also covers a dependency that moved to a different entry (a version bump), which a
     * bare stamp comparison of the recorded files would miss. Jars hash exactly as Zinc hashes
     * them, so recorded jar hashes stay comparable across this change.
     */
    static IncOptions incOptions(
            ReadStamps current, FileConverter converter, Path classOutput, ClasspathAnalyses producers) {
        ExternalHooks hooks = new DefaultExternalHooks(
                Optional.of(new DirectoryAwareClasspathHash(current, converter, classOutput, producers)),
                Optional.empty());
        return IncOptions.create().withExternalHooks(hooks);
    }

    /**
     * Implements Zinc's Scala {@link ExternalLookup} rather than the Java {@code
     * ExternalHooks.Lookup} it extends: {@code LookupImpl} only honours hooks of the Scala type and
     * silently ignores the rest. Everything but {@link #hashClasspath} and {@link
     * #lookupAnalyzedClass} defers to Zinc.
     */
    static final class DirectoryAwareClasspathHash implements ExternalLookup {
        /** Zinc's own hash for an entry that does not exist ({@code ClasspathCache.emptyFileHash}). */
        private static final int ABSENT = 42;

        private final ReadStamps current;
        private final FileConverter converter;
        private final Path classOutput;
        private final ClasspathAnalyses producers;

        DirectoryAwareClasspathHash(
                ReadStamps current, FileConverter converter, Path classOutput, ClasspathAnalyses producers) {
            this.current = current;
            this.converter = converter;
            this.classOutput = classOutput.toAbsolutePath().normalize();
            this.producers = producers;
        }

        @Override
        public Optional<FileHash[]> hashClasspath(VirtualFile[] classpath) {
            FileHash[] out = new FileHash[classpath.length];
            for (int i = 0; i < classpath.length; i++) {
                Path path = converter.toPath(classpath[i]);
                out[i] = FileHash.of(path, hashEntry(classpath[i], path));
            }
            return Optional.of(out);
        }

        private int hashEntry(VirtualFile entry, Path path) {
            if (!Files.exists(path)) return ABSENT;
            if (!Files.isDirectory(path)) return current.library(entry).getValueId();
            // The module's own output is on the classpath too; its files are products Zinc tracks
            // itself, and they change on every compile — hashing them would flip the classpath
            // hash each time and make every compile take the slow origin-lookup path.
            if (path.toAbsolutePath().normalize().equals(classOutput)) return ABSENT;
            try {
                return listingHash(path);
            } catch (IOException e) {
                return ABSENT;
            }
        }

        /** Order-independent digest of every file's relative path, size and mtime under {@code dir}. */
        static int listingHash(Path dir) throws IOException {
            TreeMap<String, Long> entries = new TreeMap<>();
            PathUtil.forEachRegularFile(
                    dir,
                    (file, attrs) -> entries.put(
                            dir.relativize(file).toString().replace(File.separatorChar, '/'),
                            attrs.size() * 31 + attrs.lastModifiedTime().toMillis()));
            int h = 1;
            for (Map.Entry<String, Long> e : entries.entrySet()) {
                h = 31 * h + e.getKey().hashCode();
                h = 31 * h + Long.hashCode(e.getValue());
            }
            return h;
        }

        @Override
        public Option<Set<VirtualFileRef>> changedBinaries(CompileAnalysis previous) {
            return Option.empty();
        }

        /**
         * The producer's view of a class another jk compile produced. Asked here, not left to the
         * per-entry analyses alone: Zinc's {@code LookupImpl} takes an empty answer from the
         * external hook as final and never falls through to {@link ClasspathLookup}, so an
         * external hook that stays silent would hide every producer analysis.
         */
        @Override
        public Option<AnalyzedClass> lookupAnalyzedClass(String binaryClassName, Option<VirtualFileRef> file) {
            if (producers.isEmpty()) return Option.empty();
            return producers.analyzedClass(binaryClassName);
        }

        @Override
        public Option<Changes<VirtualFileRef>> changedSources(CompileAnalysis previous) {
            return Option.empty();
        }

        @Override
        public Option<Set<VirtualFileRef>> removedProducts(CompileAnalysis previous) {
            return Option.empty();
        }

        @Override
        public boolean shouldDoIncrementalCompilation(Set<String> changedClasses, CompileAnalysis analysis) {
            return true;
        }
    }

    /** Stamps compare by their persisted text; a missing stamp on either side is a change. */
    static boolean stampChanged(Stamp old, Stamp now) {
        String a = old == null ? "" : old.writeStamp();
        String b = now == null ? "" : now.writeStamp();
        return !a.equals(b);
    }

    /** Zinc's own chatter is not jk's output; diagnostics come through the reporter. */
    enum QuietLogger implements Logger {
        INSTANCE;

        @Override
        public void error(Supplier<String> msg) {}

        @Override
        public void warn(Supplier<String> msg) {}

        @Override
        public void info(Supplier<String> msg) {}

        @Override
        public void debug(Supplier<String> msg) {}

        @Override
        public void trace(Supplier<Throwable> exception) {}
    }

    @SuppressWarnings("unchecked")
    static T2<String, String>[] noExtra() {
        return new T2[0];
    }

    static VirtualFile[] virtual(List<Path> paths, FileConverter converter) {
        VirtualFile[] out = new VirtualFile[paths.size()];
        for (int i = 0; i < paths.size(); i++) out[i] = converter.toVirtualFile(paths.get(i));
        return out;
    }
}
