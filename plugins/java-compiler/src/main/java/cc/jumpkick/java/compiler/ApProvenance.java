// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;

/**
 * Captures generated-file → originating-source mappings by wrapping annotation processors' {@link
 * Filer}. Isolating processors have arity 1; aggregating processors have arity &gt;1.
 */
final class ApProvenance {
    final Map<Path, Set<Path>> generated = new LinkedHashMap<>();
    volatile @Nullable Trees trees;

    List<Processor> wrap(List<Processor> processors) {
        List<Processor> wrapped = new ArrayList<>(processors.size());
        for (Processor p : processors) wrapped.add(new RecordingProcessor(p, this));
        return wrapped;
    }

    /**
     * True when any generated file's provenance is not exactly one origin: {@code >1} is a genuine
     * aggregator, and {@code 0} is unknown provenance (a {@code createResource}/{@code createSourceFile}
     * call with no originating elements — e.g. a {@code META-INF/services} writer). Both must force a
     * full recompile, since an incremental subset rebuild could silently drop such a file's inputs
     *. Only arity exactly 1 is safe to treat as isolating.
     */
    boolean aggregating() {
        for (Set<Path> origins : generated.values()) {
            if (origins.size() != 1) return true;
        }
        return false;
    }

    void record(FileObject created, Element[] originating) {
        Trees trees = this.trees;
        if (trees == null) return;
        Path gen;
        try {
            gen = Path.of(created.toUri());
        } catch (RuntimeException notAPath) {
            return;
        }
        Set<Path> origins = generated.computeIfAbsent(gen, k -> new TreeSet<>());
        for (Element e : originating) {
            TreePath tp = trees.getPath(e);
            if (tp != null)
                origins.add(Path.of(tp.getCompilationUnit().getSourceFile().toUri()));
        }
    }

    private static final class RecordingProcessor implements Processor {
        private final Processor delegate;
        private final ApProvenance provenance;

        RecordingProcessor(Processor delegate, ApProvenance provenance) {
            this.delegate = delegate;
            this.provenance = provenance;
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment env) {
            provenance.trees = Trees.instance(env);
            delegate.init(new RecordingEnv(env, new RecordingFiler(env.getFiler(), provenance)));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends Completion> getCompletions(
                Element element, AnnotationMirror annotation, ExecutableElement module, String userText) {
            return delegate.getCompletions(element, annotation, module, userText);
        }
    }

    private record RecordingEnv(ProcessingEnvironment delegate, Filer filer) implements ProcessingEnvironment {
        @Override
        public Map<String, String> getOptions() {
            return delegate.getOptions();
        }

        @Override
        public Messager getMessager() {
            return delegate.getMessager();
        }

        @Override
        public Filer getFiler() {
            return filer;
        }

        @Override
        public Elements getElementUtils() {
            return delegate.getElementUtils();
        }

        @Override
        public Types getTypeUtils() {
            return delegate.getTypeUtils();
        }

        @Override
        public SourceVersion getSourceVersion() {
            return delegate.getSourceVersion();
        }

        @Override
        public Locale getLocale() {
            return delegate.getLocale();
        }
    }

    private record RecordingFiler(Filer delegate, ApProvenance provenance) implements Filer {
        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originating) throws IOException {
            JavaFileObject jfo = delegate.createSourceFile(name, originating);
            provenance.record(jfo, originating);
            return jfo;
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originating) throws IOException {
            JavaFileObject jfo = delegate.createClassFile(name, originating);
            provenance.record(jfo, originating);
            return jfo;
        }

        @Override
        public FileObject createResource(
                JavaFileManager.Location location,
                CharSequence moduleAndPkg,
                CharSequence relativeName,
                Element... originating)
                throws IOException {
            FileObject fo = delegate.createResource(location, moduleAndPkg, relativeName, originating);
            provenance.record(fo, originating);
            return fo;
        }

        @Override
        public FileObject getResource(
                JavaFileManager.Location location, CharSequence moduleAndPkg, CharSequence relativeName)
                throws IOException {
            return delegate.getResource(location, moduleAndPkg, relativeName);
        }
    }
}
