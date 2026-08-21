// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
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
 * Zinc incremental compile. Java-only uses a dummy scalac; {@link #compileMixed} is the same
 * worker with a real Scala 3 compiler + published sbt bridge (one session for circular
 * Java↔Scala).
 */
public final class ZincJavaCompiler {

    private ZincJavaCompiler() {}

    public record Result(
            boolean success, List<Diag> diagnostics, List<Path> compiledSources, Map<Path, Set<Path>> generated) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
            compiledSources = List.copyOf(compiledSources);
            generated = generated == null ? Map.of() : Map.copyOf(generated);
        }

        public Result(boolean success, List<Diag> diagnostics, List<Path> compiledSources) {
            this(success, diagnostics, compiledSources, Map.of());
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
        return compile(
                sources, classpath, classOutput, workdir, sourceOutput, release, extraOptions, processorPath, null);
    }

    /**
     * Joint Java+Scala incremental compile with a real Scala 3 compiler + published sbt bridge.
     * {@code compilerClasspath} is the worker compiler closure (not the project compile CP).
     */
    public static Result compileMixed(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath,
            String scalaVersion,
            List<Path> compilerClasspath,
            Path bridgeJar) {
        return compileMixed(
                sources,
                classpath,
                classOutput,
                workdir,
                sourceOutput,
                release,
                extraOptions,
                processorPath,
                scalaVersion,
                compilerClasspath,
                bridgeJar,
                null,
                null);
    }

    public static Result compileMixed(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath,
            String scalaVersion,
            List<Path> compilerClasspath,
            Path bridgeJar,
            Path libraryJar,
            Path compilerJar) {
        if (scalaVersion == null || scalaVersion.isBlank()) {
            throw new IllegalArgumentException("compileMixed requires scalaVersion");
        }
        if (compilerClasspath == null || compilerClasspath.isEmpty()) {
            throw new IllegalArgumentException("compileMixed requires a Scala compiler classpath");
        }
        return compile(
                sources,
                classpath,
                classOutput,
                workdir,
                sourceOutput,
                release,
                extraOptions,
                processorPath,
                new MixedScala(scalaVersion, compilerClasspath, bridgeJar, libraryJar, compilerJar));
    }

    private record MixedScala(
            String version, List<Path> compilerClasspath, Path bridgeJar, Path libraryJar, Path compilerJar) {}

    private static Result compile(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath,
            MixedScala mixed) {
        RecordingJavaCompiler javac = null;
        try {
            Files.createDirectories(classOutput);
            Files.createDirectories(workdir);
            if (sourceOutput != null) Files.createDirectories(sourceOutput);
            if (Files.isRegularFile(workdir.resolve("aggregating"))) {
                Files.deleteIfExists(workdir.resolve("zinc"));
            }

            IncrementalCompiler zinc = ZincUtil.defaultIncrementalCompiler();
            FileConverter converter = PlainVirtualFileConverter.converter();
            ApProvenance provenance = new ApProvenance();
            List<Processor> processors = loadProcessors(processorPath);
            javac = recordingJavac(converter, processors, provenance);
            Compilers compilers = mixed != null ? mixedCompilers(javac, mixed) : javaOnlyCompilers(javac);

            VirtualFile[] sourceFiles = virtual(sources, converter);
            List<Path> cp = new ArrayList<>(classpath);
            // Scala 3.8+ ships the stdlib as scala-library (same version as the compiler);
            // scala3-library_3 is an empty stub. Zinc's ClasspathOptions only move a library
            // already on this list onto scalac's bootclasspath — they do not invent it.
            if (mixed != null) {
                for (File lib : stdlibJars(mixed)) {
                    Path p = lib.toPath();
                    if (!cp.contains(p)) cp.add(p);
                }
            }
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
                    .withScalacOptions(scalacOptions(mixed != null ? mixed.version() : null, release))
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
                return new Result(false, reporter.diagnostics(), javac.compiledSources(), provenance.generated);
            }
            if (provenance.aggregating()) {
                Files.writeString(workdir.resolve("aggregating"), "1\n");
            } else {
                Files.deleteIfExists(workdir.resolve("aggregating"));
            }
            store.set(AnalysisContents.create(compiled.analysis(), compiled.setup()));
            return new Result(true, reporter.diagnostics(), javac.compiledSources(), provenance.generated);
        } catch (IOException e) {
            return new Result(false, List.of(new Diag("ERROR", null, 0, 0, e.getMessage())), List.of());
        } catch (CompileFailed failed) {
            List<Diag> diags = new ArrayList<>();
            for (Problem p : failed.problems()) diags.add(toDiag(p));
            if (diags.isEmpty()) {
                diags.add(new Diag(
                        "ERROR", null, 0, 0, failed.getMessage() == null ? "compile failed" : failed.getMessage()));
            }
            return new Result(false, diags, javac == null ? List.of() : javac.compiledSources());
        } catch (RuntimeException e) {
            return new Result(
                    false,
                    List.of(new Diag("ERROR", null, 0, 0, e.getClass().getName() + ": " + e.getMessage())),
                    List.of());
        }
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

    private static Compilers mixedCompilers(JavaCompiler javac, MixedScala mixed) {
        File[] allJars = mixed.compilerClasspath().stream().map(Path::toFile).toArray(File[]::new);
        File[] libraryJars = stdlibJars(mixed);
        File compilerJar = firstJar(mixed.compilerJar(), allJars, "scala3-compiler_3");
        File bridge = firstJar(mixed.bridgeJar(), allJars, "scala3-sbt-bridge");
        if (libraryJars.length == 0 || compilerJar == null || bridge == null) {
            throw new IllegalArgumentException(
                    "Scala compiler classpath must include scala-library, scala3-compiler_3, and scala3-sbt-bridge");
        }
        if (findJar(allJars, "scala-library") == null && findJar(libraryJars, "scala-library") == null) {
            throw new IllegalArgumentException(
                    "Scala compiler classpath must include org.scala-lang:scala-library (the stdlib)");
        }
        String scalaVersion = mixed.version();
        URL[] allUrls = urls(allJars);
        URL[] libraryUrls = urls(libraryJars);
        ClassLoader parent = ZincJavaCompiler.class.getClassLoader();
        ClassLoader libraryLoader = new URLClassLoader(libraryUrls, parent);
        ClassLoader compilerLoader = new URLClassLoader(allUrls, parent);
        ScalaInstance instance = new ScalaInstance(
                scalaVersion,
                compilerLoader,
                compilerLoader,
                libraryLoader,
                libraryJars,
                allJars,
                allJars,
                scala.Option.apply(scalaVersion));
        // bootLibrary + autoBoot: Zinc appends libraryJars when the compile CP already has
        // the stdlib. filterLibrary stays off so JDK 9+ (no -bootclasspath) cannot drop it.
        ClasspathOptions cpOpts = ClasspathOptions.of(true, false, false, true, false);
        xsbti.compile.ScalaCompiler scalac = ZincUtil.scalaCompiler(instance, bridge, cpOpts);
        xsbti.compile.Javadoc javadoc =
                Javadoc.local().isDefined() ? Javadoc.local().get() : Javadoc.fork(scala.Option.empty());
        return ZincUtil.compilers(JavaTools.apply(javac, javadoc), scalac);
    }

    /**
     * Real stdlib jars for scalac: {@code scala-library} (2.13 or 3.8+) plus the
     * {@code scala3-library_3} stub when present. Artifact filenames matter.
     */
    private static File[] stdlibJars(MixedScala mixed) {
        File[] allJars = mixed.compilerClasspath().stream().map(Path::toFile).toArray(File[]::new);
        List<File> out = new ArrayList<>();
        File sl = findJar(allJars, "scala-library");
        File s3 = findJar(allJars, "scala3-library_3");
        if (sl != null) out.add(sl);
        if (s3 != null && !out.contains(s3)) out.add(s3);
        if (out.isEmpty() && mixed.libraryJar() != null)
            out.add(mixed.libraryJar().toFile());
        return out.toArray(File[]::new);
    }

    private static File firstJar(Path extra, File[] allJars, String artifactPrefix) {
        File named = findJar(allJars, artifactPrefix);
        if (named != null) return named;
        return extra != null ? extra.toFile() : null;
    }

    private static File findJar(File[] jars, String artifactPrefix) {
        for (File f : jars) {
            String n = f.getName();
            if (n.startsWith(artifactPrefix + "-") || n.startsWith(artifactPrefix + ".")) return f;
        }
        return null;
    }

    private static URL[] urls(File[] files) {
        URL[] out = new URL[files.length];
        for (int i = 0; i < files.length; i++) {
            try {
                out[i] = files[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad classpath entry: " + files[i], e);
            }
        }
        return out;
    }

    private static String[] scalacOptions(String scalaVersion, int release) {
        if (scalaVersion == null || release <= 0) return new String[0];
        return new String[] {"-java-output-version", Integer.toString(release)};
    }

    private static RecordingJavaCompiler recordingJavac(
            FileConverter converter, List<Processor> processors, ApProvenance provenance) {
        JavaCompiler javac;
        if (!processors.isEmpty()) {
            javac = new ProvenanceJavac(processors, provenance);
        } else {
            scala.Option<JavaCompiler> local = sbt.internal.inc.javac.JavaCompiler.local();
            javac = local.isDefined() ? local.get() : sbt.internal.inc.javac.JavaCompiler.fork(scala.Option.empty());
        }
        return new RecordingJavaCompiler(javac, converter);
    }

    private static List<Processor> loadProcessors(List<Path> processorPath) {
        if (processorPath == null || processorPath.isEmpty()) return List.of();
        URL[] urls = new URL[processorPath.size()];
        for (int i = 0; i < processorPath.size(); i++) {
            try {
                urls[i] = processorPath.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad processor path entry: " + processorPath.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, ZincJavaCompiler.class.getClassLoader());
        List<Processor> processors = new ArrayList<>();
        for (Processor p : ServiceLoader.load(Processor.class, loader)) processors.add(p);
        return processors;
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

    /**
     * ToolProvider javac that installs wrapped processors so generated-file provenance is recorded.
     * Used instead of Zinc's {@code JavaCompiler.local} when a processor path is present.
     */
    private static final class ProvenanceJavac implements JavaCompiler {
        private final List<Processor> processors;
        private final ApProvenance provenance;

        ProvenanceJavac(List<Processor> processors, ApProvenance provenance) {
            this.processors = processors;
            this.provenance = provenance;
        }

        @Override
        public boolean run(
                VirtualFile[] sources,
                String[] options,
                Output output,
                IncToolOptions incToolOptions,
                Reporter reporter,
                Logger log) {
            javax.tools.JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) throw new IllegalStateException("no system javac (run under a JDK)");
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            Path classOut = output.getSingleOutputAsPath().orElseThrow();
            try (StandardJavaFileManager fm =
                    javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
                Files.createDirectories(classOut);
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
                for (int i = 0; i < options.length - 1; i++) {
                    if ("-s".equals(options[i])) {
                        Path srcOut = Path.of(options[i + 1]);
                        Files.createDirectories(srcOut);
                        fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(srcOut));
                    }
                }
                List<Path> srcPaths = new ArrayList<>();
                for (VirtualFile vf : sources) {
                    if (vf instanceof xsbti.PathBasedFile pathFile) srcPaths.add(pathFile.toPath());
                }
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(srcPaths);
                JavacTask task = (JavacTask) javac.getTask(null, fm, diags, Arrays.asList(options), null, units);
                task.setProcessors(provenance.wrap(processors));
                boolean ok = task.call();
                sbt.internal.inc.javac.DiagnosticsReporter bridge =
                        new sbt.internal.inc.javac.DiagnosticsReporter(reporter);
                for (var d : diags.getDiagnostics()) bridge.report(d);
                return ok && !bridge.hasErrors();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
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
