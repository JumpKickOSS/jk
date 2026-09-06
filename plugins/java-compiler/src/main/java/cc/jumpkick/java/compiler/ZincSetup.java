// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.ExternalLookup;
import sbt.internal.inc.Locate;
import scala.Option;
import scala.collection.immutable.Set;
import scala.jdk.javaapi.CollectionConverters;
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
 * The parts of Zinc's {@code Setup}/{@code Inputs} jk supplies as constants: no per-entry analysis,
 * a silent logger, no extra key/value pairs, and the path-to-{@link VirtualFile} lift. Each is a
 * Zinc SPI obligation with no jk decision in it — grouped because the alternative is four files
 * whose whole content is "we have nothing to say here".
 */
final class ZincSetup {

    private ZincSetup() {}

    /** No cross-entry analysis: jk compiles one module per Zinc session. */
    static final class ClasspathLookup implements PerClasspathEntryLookup {
        @Override
        public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
            return Optional.empty();
        }

        @Override
        public DefinesClass definesClass(VirtualFile classpathEntry) {
            if ("rt.jar".equals(classpathEntry.name())) return name -> false;
            return Locate.definesClass(classpathEntry);
        }
    }

    /**
     * Incremental options whose library-change detection compares every recorded classpath class
     * file against its current stamp.
     *
     * <p>Zinc's default detection is built for sbt, where a dependency that lives in a directory
     * is another sub-project with its own analysis: a directory classpath entry hashes to a
     * constant, so an "unchanged classpath" makes Zinc consult {@link ClasspathLookup#analysis}
     * for every library class instead of the stamps, and jk answers "no analysis" — so a class
     * rewritten in place inside {@code classes/main} (a test compile's own main output, or an
     * upstream module wired as a directory) is invisible to the dependent compile. Answering
     * {@code getChangedBinaries} ourselves keeps the stamp comparison in force, the same comparison
     * {@link ZincJavaCompiler#plan} makes for its forecast, so the forecast and the compile agree.
     */
    static IncOptions incOptions(ReadStamps current) {
        ExternalHooks hooks = new DefaultExternalHooks(Optional.of(new StampedLibraries(current)), Optional.empty());
        return IncOptions.create().withExternalHooks(hooks);
    }

    /**
     * Library change = a recorded stamp that the current stamper no longer reproduces.
     *
     * <p>Implements Zinc's Scala {@link ExternalLookup} rather than the Java {@code
     * ExternalHooks.Lookup} it extends: {@code LookupImpl} only honours hooks of the Scala type and
     * silently ignores the rest.
     */
    static final class StampedLibraries implements ExternalLookup {
        private final ReadStamps current;

        StampedLibraries(ReadStamps current) {
            this.current = current;
        }

        @Override
        public Option<Set<VirtualFileRef>> changedBinaries(CompileAnalysis previous) {
            if (!(previous instanceof Analysis analysis)) return Option.empty();
            HashSet<VirtualFileRef> changed = new HashSet<>();
            for (Map.Entry<VirtualFileRef, Stamp> e :
                    analysis.readStamps().getAllLibraryStamps().entrySet()) {
                if (stampChanged(e.getValue(), current.library(e.getKey()))) changed.add(e.getKey());
            }
            return Option.apply(CollectionConverters.asScala(changed).toSet());
        }

        /** No cross-project analysis, so no class is ever "analyzed" elsewhere. */
        @Override
        public Option<AnalyzedClass> lookupAnalyzedClass(String binaryClassName, Option<VirtualFileRef> file) {
            return Option.empty();
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

        @Override
        public Optional<FileHash[]> hashClasspath(VirtualFile[] classpath) {
            return Optional.empty();
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
