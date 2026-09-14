// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sbt.internal.inc.APIs;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.Locate;
import sbt.internal.inc.Relations;
import sbt.internal.inc.Stamps;
import sbt.internal.inc.javac.ConstantDepListener;
import sbt.internal.inc.javac.JavaConstantDeps;
import sbt.internal.util.Relation;
import scala.Option;
import scala.Tuple3;
import scala.jdk.javaapi.CollectionConverters;
import xsbti.FileConverter;
import xsbti.VirtualFile;
import xsbti.VirtualFileRef;
import xsbti.api.AnalyzedClass;
import xsbti.api.DependencyContext;
import xsbti.api.ExternalDependency;
import xsbti.api.InternalDependency;
import xsbti.compile.analysis.ReadStamps;
import xsbti.compile.analysis.Stamp;

/**
 * The dependencies javac erases. A {@code static final} constant is inlined at every use, so the
 * using class's bytecode names neither the field nor its declaring class; Zinc's Java analysis
 * reads dependencies from bytecode and so never learns of the edge, and a consumer that inlined a
 * constant is not recompiled when the value changes — it keeps the old value for as long as the
 * source stays untouched. Zinc recovers those edges from javac's attributed AST (sbt/zinc#145),
 * but only when its own {@code LocalJavaCompiler} runs javac; jk runs javac itself ({@link
 * ProvenanceJavac}), so the same listener is installed here and the edges are added to the
 * analysis after the compile, as Zinc's {@code JavaAnalyze.processDependency} would have filed
 * them: an internal class dependency when the constant's owner is a class of this compile, an
 * external dependency on the producer's analyzed class when another jk compile produced it (with
 * the producer's hashes recorded, so the next compile's comparison sees the change), and a library
 * dependency on the classpath entry that defines it otherwise. The edge is a member reference; the
 * value rides in the owner's bytecode hash, which Zinc compares.
 */
final class ConstantDeps {

    private final JavaConstantDeps sink = new JavaConstantDeps();

    /** Record every inlined-constant reference the task attributes, keyed by the using class. */
    void listen(JavacTask task) {
        task.addTaskListener(new ConstantDepListener(task, sink));
    }

    /** The edges collected so far: using class binary name → binary names of the constants' owners. */
    Map<String, Set<String>> edges() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        var scalaMap = CollectionConverters.asJava(sink.result());
        for (var e : scalaMap.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(CollectionConverters.asJava(e.getValue())));
        }
        return out;
    }

    /**
     * {@code analysis} with {@code edges} filed as dependencies, or {@code analysis} itself when
     * there are none. {@code classpath} is the compile classpath as Zinc saw it, {@code classOutput}
     * this compile's own output (never a library).
     */
    static Analysis addTo(
            Analysis analysis,
            Map<String, Set<String>> edges,
            ClasspathAnalyses producers,
            List<VirtualFile> classpath,
            Path classOutput,
            ReadStamps stamps,
            FileConverter converter) {
        if (edges.isEmpty()) return analysis;
        Relations rel = analysis.relations();
        APIs apis = analysis.apis();
        Stamps recorded = analysis.stamps();
        Relation<String, String> products = rel.productClassName();
        Map<String, Optional<VirtualFile>> libraries = new HashMap<>();
        for (Map.Entry<String, Set<String>> edge : edges.entrySet()) {
            String fromBinary = edge.getKey();
            for (String fromClass : CollectionConverters.asJava(products.reverse(fromBinary))) {
                for (VirtualFileRef source : CollectionConverters.asJava(rel.definesClass(fromClass))) {
                    for (String onBinary : edge.getValue()) {
                        if (onBinary.equals(fromBinary)) continue;
                        Set<String> onClasses = CollectionConverters.asJava(products.reverse(onBinary));
                        if (!onClasses.isEmpty()) {
                            List<InternalDependency> deps = new ArrayList<>();
                            for (String onClass : onClasses) {
                                if (onClass.equals(fromClass)) continue;
                                deps.add(InternalDependency.of(
                                        fromClass, onClass, DependencyContext.DependencyByMemberRef));
                            }
                            if (!deps.isEmpty())
                                rel = rel.addInternalSrcDeps(source, CollectionConverters.asScala(deps));
                            continue;
                        }
                        Option<AnalyzedClass> producer = producers.analyzedClass(onBinary);
                        if (producer.isDefined()) {
                            apis = apis.markExternalAPI(onBinary, producer.get());
                            rel = rel.addExternalDeps(
                                    source,
                                    CollectionConverters.asScala(List.of(ExternalDependency.of(
                                            fromClass,
                                            onBinary,
                                            producer.get(),
                                            DependencyContext.DependencyByMemberRef))));
                            continue;
                        }
                        VirtualFile library = libraries
                                .computeIfAbsent(onBinary, b -> defining(b, classpath, classOutput, converter))
                                .orElse(null);
                        if (library == null) continue; // a platform class, or nothing on the classpath
                        Stamp stamp = stamps.library(library);
                        recorded = recorded.markLibrary(library, onBinary, stamp);
                        rel = rel.addLibraryDeps(
                                source, CollectionConverters.asScala(List.of(new Tuple3<>(library, onBinary, stamp))));
                    }
                }
            }
        }
        return analysis.copy(recorded, apis, rel, analysis.infos(), analysis.compilations());
    }

    /** The first classpath entry, other than this compile's own output, that defines {@code binaryName}. */
    private static Optional<VirtualFile> defining(
            String binaryName, List<VirtualFile> classpath, Path classOutput, FileConverter converter) {
        Path own = classOutput.toAbsolutePath().normalize();
        for (VirtualFile entry : classpath) {
            if (converter.toPath(entry).toAbsolutePath().normalize().equals(own)) continue;
            try {
                if (Locate.definesClass(entry).apply(binaryName)) return Optional.of(entry);
            } catch (RuntimeException unreadable) {
                // an entry that cannot be indexed defines nothing this compile can depend on
            }
        }
        return Optional.empty();
    }
}
