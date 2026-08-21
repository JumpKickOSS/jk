// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import sbt.internal.inc.CompileFailed;
import sbt.internal.inc.FileAnalysisStore;
import sbt.internal.inc.FreshCompilerCache;
import sbt.internal.inc.Locate;
import sbt.internal.inc.PlainVirtualFileConverter;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.Stamps;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.javac.JavaTools;
import sbt.internal.inc.javac.Javadoc;
import xsbti.FileConverter;
import xsbti.Logger;
import xsbti.Position;
import xsbti.Problem;
import xsbti.Reporter;
import xsbti.Severity;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;
import xsbti.compile.ClasspathOptions;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileOrder;
import xsbti.compile.CompileResult;
import xsbti.compile.Compilers;
import xsbti.compile.DefinesClass;
import xsbti.compile.IncOptions;
import xsbti.compile.IncToolOptions;
import xsbti.compile.IncrementalCompiler;
import xsbti.compile.Inputs;
import xsbti.compile.JavaCompiler;
import xsbti.compile.Output;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.Setup;

/**
 * Zinc Java-only incremental compile (Mill's dummy-scalac recipe). {@link #compileMixed} is a
 * reserved seam for a later Scala mode of this same worker — not a sibling plugin.
 */
public final class ZincJavaCompiler {

    private ZincJavaCompiler() {}

    public record Result(boolean success, List<Diag> diagnostics, List<Path> compiledSources) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
            compiledSources = List.copyOf(compiledSources);
        }
    }

    public record Diag(String kind, String file, long line, long col, String message) {}

    /**
     * Compile {@code sources} into {@code classOutput}, persisting Zinc analysis under {@code
     * workdir}.
     */
    public static Result compileJava(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath) {
        try {
            Files.createDirectories(classOutput);
            Files.createDirectories(workdir);
            if (sourceOutput != null) Files.createDirectories(sourceOutput);

            IncrementalCompiler zinc = ZincUtil.defaultIncrementalCompiler();
            FileConverter converter = PlainVirtualFileConverter.converter();
            RecordingJavaCompiler javac = recordingJavac(converter);
            Compilers compilers = javaOnlyCompilers(javac);

            VirtualFile[] sourceFiles = virtual(sources, converter);
            List<Path> cp = new ArrayList<>(classpath);
            cp.add(classOutput);
            VirtualFile[] cpFiles = virtual(cp, converter);

            CollectingReporter reporter = new CollectingReporter();
            Path analysisFile = workdir.resolve("zinc");
            AnalysisStore store = FileAnalysisStore.binary(analysisFile.toFile());
            Setup setup = Setup.of(
                    new ClasspathLookup(),
                    false,
                    analysisFile,
                    new FreshCompilerCache(),
                    IncOptions.create(),
                    reporter,
                    extra());

            CompileOptions options = CompileOptions.of()
                    .withClasspath(cpFiles)
                    .withSources(sourceFiles)
                    .withClassesDirectory(classOutput)
                    .withScalacOptions(new String[0])
                    .withJavacOptions(javacOptions(release, extraOptions, sourceOutput, processorPath))
                    .withOrder(CompileOrder.Mixed)
                    .withConverter(converter)
                    .withStamper(Stamps.timeWrapBinaryStamps(converter));

            Optional<AnalysisContents> prev = store.get();
            PreviousResult previous = prev.isPresent()
                    ? PreviousResult.of(prev.get().getAnalysis(), prev.get().getMiniSetup())
                    : PreviousResult.of(Optional.empty(), Optional.empty());

            Inputs inputs = Inputs.of(compilers, options, setup, previous);
            CompileResult compiled = zinc.compile(inputs, QuietLogger.INSTANCE);
            if (reporter.hasErrors()) {
                return new Result(false, reporter.diagnostics(), javac.compiledSources());
            }
            store.set(AnalysisContents.create(compiled.analysis(), compiled.setup()));
            return new Result(true, reporter.diagnostics(), javac.compiledSources());
        } catch (IOException e) {
            return new Result(false, List.of(new Diag("ERROR", null, 0, 0, e.getMessage())), List.of());
        } catch (CompileFailed failed) {
            List<Diag> diags = new ArrayList<>();
            for (Problem p : failed.problems()) diags.add(toDiag(p));
            if (diags.isEmpty()) {
                diags.add(new Diag(
                        "ERROR", null, 0, 0, failed.getMessage() == null ? "compile failed" : failed.getMessage()));
            }
            return new Result(false, diags, List.of());
        } catch (RuntimeException e) {
            return new Result(
                    false,
                    List.of(new Diag("ERROR", null, 0, 0, e.getClass().getName() + ": " + e.getMessage())),
                    List.of());
        }
    }

    /**
     * Joint Java+Scala compile. Requires a real Scala compiler classpath; Java-only uses {@link
     * #compileJava}.
     */
    public static Result compileMixed(List<Path> sources, List<Path> classpath, Path classOutput, Path workdir) {
        throw new UnsupportedOperationException("compileMixed requires a Scala compiler classpath");
    }

    private static Compilers javaOnlyCompilers(JavaCompiler javac) {
        File dummy = new File("");
        ScalaInstance dummyScala = new ScalaInstance(
                "", null, null, null, new File[] {dummy}, new File[] {dummy}, new File[0], scala.Option.apply(""));
        ClasspathOptions cpOpts = ClasspathOptions.of(false, false, false, false, false);
        xsbti.compile.ScalaCompiler scalac = ZincUtil.scalaCompiler(dummyScala, dummy, cpOpts);
        xsbti.compile.Javadoc javadoc =
                Javadoc.local().isDefined() ? Javadoc.local().get() : Javadoc.fork(scala.Option.empty());
        return ZincUtil.compilers(JavaTools.apply(javac, javadoc), scalac);
    }

    private static RecordingJavaCompiler recordingJavac(FileConverter converter) {
        scala.Option<JavaCompiler> local = sbt.internal.inc.javac.JavaCompiler.local();
        JavaCompiler javac =
                local.isDefined() ? local.get() : sbt.internal.inc.javac.JavaCompiler.fork(scala.Option.empty());
        return new RecordingJavaCompiler(javac, converter);
    }

    private static String[] javacOptions(int release, List<String> extra, Path sourceOutput, List<Path> processorPath) {
        List<String> opts = new ArrayList<>();
        if (release > 0 && !containsFlag(extra, "--release")) {
            opts.add("--release");
            opts.add(Integer.toString(release));
        }
        if (sourceOutput != null && !containsFlag(extra, "-s")) {
            opts.add("-s");
            opts.add(sourceOutput.toAbsolutePath().toString());
        }
        if (processorPath != null && !processorPath.isEmpty() && !containsFlag(extra, "-processorpath")) {
            opts.add("-processorpath");
            String sep = File.pathSeparator;
            StringBuilder pp = new StringBuilder();
            for (int i = 0; i < processorPath.size(); i++) {
                if (i > 0) pp.append(sep);
                pp.append(processorPath.get(i).toAbsolutePath());
            }
            opts.add(pp.toString());
        }
        if (extra != null) opts.addAll(extra);
        return opts.toArray(String[]::new);
    }

    private static boolean containsFlag(List<String> extra, String flag) {
        if (extra == null) return false;
        for (String o : extra) if (flag.equals(o)) return true;
        return false;
    }

    private static VirtualFile[] virtual(List<Path> paths, FileConverter converter) {
        VirtualFile[] out = new VirtualFile[paths.size()];
        for (int i = 0; i < paths.size(); i++) out[i] = converter.toVirtualFile(paths.get(i));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static T2<String, String>[] extra() {
        return new T2[0];
    }

    private static Diag toDiag(Problem p) {
        Position pos = p.position();
        String file = pos.sourcePath().orElse(null);
        long line = pos.line().map(Integer::longValue).orElse(0L);
        String kind =
                switch (p.severity()) {
                    case Error -> "ERROR";
                    case Warn -> "WARNING";
                    case Info -> "NOTE";
                };
        return new Diag(kind, file, line, 0, p.message());
    }

    private static final class RecordingJavaCompiler implements JavaCompiler {
        private final JavaCompiler delegate;
        private final FileConverter converter;
        private final List<Path> compiledSources = new ArrayList<>();

        RecordingJavaCompiler(JavaCompiler delegate, FileConverter converter) {
            this.delegate = delegate;
            this.converter = converter;
        }

        List<Path> compiledSources() {
            return compiledSources;
        }

        @Override
        public boolean run(
                VirtualFile[] sources,
                String[] options,
                Output output,
                IncToolOptions incToolOptions,
                Reporter reporter,
                Logger log) {
            for (VirtualFile vf : sources) {
                Path path = converter.toPath(vf);
                if (!compiledSources.contains(path)) compiledSources.add(path);
            }
            return delegate.run(sources, options, output, incToolOptions, reporter, log);
        }
    }

    private static final class ClasspathLookup implements PerClasspathEntryLookup {
        @Override
        public Optional<xsbti.compile.CompileAnalysis> analysis(VirtualFile classpathEntry) {
            return Optional.empty();
        }

        @Override
        public DefinesClass definesClass(VirtualFile classpathEntry) {
            if ("rt.jar".equals(classpathEntry.name())) return name -> false;
            return Locate.definesClass(classpathEntry);
        }
    }

    private static final class CollectingReporter implements Reporter {
        private final List<Problem> problems = new ArrayList<>();

        List<Diag> diagnostics() {
            List<Diag> out = new ArrayList<>();
            for (Problem p : problems) out.add(toDiag(p));
            return out;
        }

        @Override
        public void reset() {
            problems.clear();
        }

        @Override
        public boolean hasErrors() {
            for (Problem p : problems) if (p.severity() == Severity.Error) return true;
            return false;
        }

        @Override
        public boolean hasWarnings() {
            for (Problem p : problems) if (p.severity() == Severity.Warn) return true;
            return false;
        }

        @Override
        public void printSummary() {}

        @Override
        public Problem[] problems() {
            return problems.toArray(Problem[]::new);
        }

        @Override
        public void log(Problem problem) {
            problems.add(problem);
        }

        @Override
        public void comment(Position pos, String msg) {}
    }

    private enum QuietLogger implements Logger {
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
}
