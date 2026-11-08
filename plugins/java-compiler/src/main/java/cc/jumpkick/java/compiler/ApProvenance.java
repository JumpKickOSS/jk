// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;

/**
 * Generated-file → originating-source mappings, recorded by {@link ProvenanceFileManager} as javac's
 * Filer asks the file manager for each file a processor writes. Isolating processors have arity 1;
 * aggregating processors have arity &gt;1.
 *
 * <p>Recording at the file manager rather than wrapping the {@link
 * javax.annotation.processing.ProcessingEnvironment} hands every processor javac's own environment,
 * which some need: {@code Trees.instance} and {@code JavacProcessingEnvironment.instance} refuse
 * anything else, so a processor that reaches javac's trees fails outright under a wrapped one.
 */
final class ApProvenance {
    final Map<Path, Set<Path>> generated = new LinkedHashMap<>();

    /** Files a processor read back from an output location. */
    private final Set<Path> readBack = new HashSet<>();

    /** Output files that hold a copied resource's bytes this compile ({@link GeneratedProvenance#pristine}). */
    private Predicate<Path> pristine = p -> false;

    /** The manager javac compiles through, recording into this. */
    JavaFileManager manager(JavaFileManager fm, @Nullable Path sourceRoot, Function<Path, FileObject> files) {
        return new ProvenanceFileManager(fm, this, sourceRoot, files);
    }

    /**
     * True when any generated file's provenance is not exactly one origin: {@code >1} is a genuine
     * aggregator, and {@code 0} is unknown provenance (a {@code createResource}/{@code createSourceFile}
     * call with no originating elements — e.g. a {@code META-INF/services} writer). Both must force a
     * full recompile, since an incremental subset rebuild could silently drop such a file's inputs.
     * Only arity exactly 1 is safe to treat as isolating.
     */
    boolean aggregating() {
        for (Set<Path> origins : generated.values()) {
            if (origins.size() != 1) return true;
        }
        return merging();
    }

    /**
     * True when a processor read a file back from an output location and wrote that same file: it
     * merges this run's elements into what the last run left, as Helidon's {@code
     * service-registry.json} does. Its file is then a product of every source, whatever origins it
     * names — an incremental run would merge into rows whose sources are gone — so the module is
     * compiled in full from then on, with last run's generated files deleted first. A read of a file
     * the processor does not write, a copied resource such as {@code persistence.xml}, is not one;
     * nor is a read of a copied resource it then rewrites, as Helidon merges its static manifest,
     * while the copy still holds the resource's bytes: its merge base is the module's resource, not
     * last run's output.
     */
    boolean merging() {
        for (Path read : readBack) {
            if (generated.containsKey(read) && !pristine.test(read)) return true;
        }
        return false;
    }

    /** The output files that hold a copied resource's bytes as this compile starts. */
    void pristine(Predicate<Path> pristine) {
        this.pristine = pristine;
    }

    void recordRead(FileObject read) {
        Path path = pathOf(read);
        if (path != null) readBack.add(path);
    }

    /**
     * Records {@code created} as generated from the sources among {@code originating}. javac names an
     * element's class file when it came from the classpath; only sources are origins an incremental
     * compile can track, so those are left out, as they always were.
     */
    void record(FileObject created, FileObject[] originating) {
        Path gen = pathOf(created);
        if (gen == null) return;
        Set<Path> origins = generated.computeIfAbsent(gen, k -> new TreeSet<>());
        for (FileObject origin : originating) {
            if (!(origin instanceof JavaFileObject source) || source.getKind() != JavaFileObject.Kind.SOURCE) continue;
            Path path = pathOf(source);
            if (path != null) origins.add(path);
        }
    }

    private static @Nullable Path pathOf(FileObject file) {
        try {
            return Path.of(file.toUri());
        } catch (RuntimeException notAPath) {
            // not a file on disk; nothing a later run could have left there or track back to
            return null;
        }
    }
}
