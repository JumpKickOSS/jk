// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.CompileFailed;
import sbt.internal.inc.FileAnalysisStore;
import sbt.internal.inc.FreshCompilerCache;
import sbt.internal.inc.Locate;
import sbt.internal.inc.PlainVirtualFileConverter;
import sbt.internal.inc.Relations;
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
import xsbti.VirtualFileRef;
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
import xsbti.compile.MiniOptions;
import xsbti.compile.MiniSetup;
import xsbti.compile.Output;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.Setup;
import xsbti.compile.analysis.ReadStamps;
import xsbti.compile.analysis.Stamp;

/**
 * Zinc incremental compile. Java-only uses a dummy scalac; {@link #compileMixed} is the same
 * worker with a real Scala 3 compiler + published sbt bridge (one session for circular
 * Java↔Scala).
 */
public final class ZincJavaCompiler {

    /**
     * The charset every source is read as. Both javac front ends here already reach UTF-8 without
     * being told — Zinc through sbt.io's hardcoded default, {@link ProvenanceJavac} through the
     * charset its file manager is built with — so naming it changes no bytes today. What it
     * changes is who owns the decision: javac's own fallback is the host's
     * {@link Charset#defaultCharset()}, not a build input jk can reproduce, and a charset that is
     * two libraries' defaults cannot be moved on purpose. This one can. {@link #javacOptions}
     * declares it, {@link ProvenanceJavac} pins its file manager to it, and the engine hashes the
     * same charset into the javac action key — as its own constant, since the engine and this
     * worker are separate processes — so moving the pin recompiles rather than restoring
     * artifacts decoded under the old one.
     */
    private static final Charset SOURCE_ENCODING = StandardCharsets.UTF_8;

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

    /** One source Zinc would compile, with the analysis reason. */
    public record Invalidation(Path source, String why) {}

    /**
     * Read-only invalidation forecast from the previous Zinc analysis + current stamps. Does not
     * write class files or analysis.
     */
    public record Plan(boolean full, String reason, List<Invalidation> invalidations) {
        public Plan {
            reason = reason == null ? "" : reason;
            invalidations = invalidations == null ? List.of() : List.copyOf(invalidations);
        }

        public List<Path> sources() {
            return invalidations.stream().map(Invalidation::source).toList();
        }
    }

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
     * Forecast which sources Zinc would compile without writing outputs. Uses the previous analysis
     * under {@code workdir} plus current source/library stamps.
     */
    public static Plan planJava(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath) {
        return plan(sources, classpath, classOutput, workdir, sourceOutput, release, extraOptions, processorPath);
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
        ProcessorLoad processors = ProcessorLoad.none();
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
            processors = loadProcessors(processorPath);
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

            Optional<AnalysisContents> prev = readAnalysis(store, analysisFile);
            if (prev.isEmpty()) {
                // No usable previous analysis ⇒ a full compile. Zinc only deletes removed-source
                // products when it has a prior analysis to diff against, so a full compile must start
                // from a clean class output or renamed/removed/no-longer-generated classes linger and
                // ship in the jar.
                deleteClassFiles(classOutput);
            }
            PreviousResult previous = prev.isPresent()
                    ? PreviousResult.of(prev.get().getAnalysis(), prev.get().getMiniSetup())
                    : PreviousResult.of(Optional.empty(), Optional.empty());

            Inputs inputs = Inputs.of(compilers, options, setup, previous);
            CompileResult compiled = zinc.compile(inputs, QuietLogger.INSTANCE);
            if (reporter.hasErrors()) {
                return new Result(false, reporter.diagnostics(), javac.compiledSources(), provenance.generated);
            }
            // Delete generated sources/classes whose (recompiled) origin no longer generates them —
            // e.g. an @Gen annotation was removed. Zinc can't do this: generated files aren't in its
            // source set, so their class files are unattributed products it never prunes.
            reconcileGeneratedOutputs(
                    workdir, sourceOutput, classOutput, javac.compiledSources(), provenance.generated);
            if (provenance.aggregating()) {
                Files.writeString(workdir.resolve("aggregating"), "1\n");
            } else {
                Files.deleteIfExists(workdir.resolve("aggregating"));
            }
            persistAnalysis(store, analysisFile, AnalysisContents.create(compiled.analysis(), compiled.setup()));
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
            String msg = e.getMessage() == null
                    ? e.getClass().getName()
                    : e.getClass().getName() + ": " + e.getMessage();
            return new Result(false, List.of(new Diag("ERROR", null, 0, 0, msg)), List.of());
        } finally {
            processors.close();
        }
    }

    /** Windows may deny a delete or a replace while another handle lingers; POSIX EACCES is permanent. */
    private static final int LOCK_ATTEMPTS = 8;

    /**
     * Read the persisted Zinc analysis, tolerating corruption. A truncated file or a schema bump
     * (e.g. a Zinc dependency upgrade) must not fail every build until a manual {@code --rebuild}:
     * delete the unreadable file and report "no analysis" so the caller falls through to a clean
     * full compile.
     *
     * <p>Unreadability surfaces either way — a thrown parse error, or an empty {@link Optional} over
     * a file that plainly exists — and both mean the same thing, so both delete it. Left in place it
     * is a file every later {@code store.set} must replace and no read can ever use.
     *
     * <p>Do not call {@code store.get()} on a non-gzip file. Zinc opens a {@code FileInputStream}
     * then wraps it in {@link GZIPInputStream}; a bad magic throws in that constructor and never
     * closes the stream. Windows then refuses to delete or replace the analysis file.
     */
    private static Optional<AnalysisContents> readAnalysis(AnalysisStore store, Path analysisFile) {
        if (!Files.isRegularFile(analysisFile)) {
            return Optional.empty();
        }
        if (!gzipHeaderReadable(analysisFile)) {
            tryDeleteAnalysis(analysisFile);
            return Optional.empty();
        }
        try {
            Optional<AnalysisContents> got = store.get();
            if (got.isEmpty()) {
                tryDeleteAnalysis(analysisFile);
            }
            return got;
        } catch (RuntimeException e) {
            tryDeleteAnalysis(analysisFile);
            return Optional.empty();
        }
    }

    /**
     * Zinc's binary store is gzip. Opens and closes the file ourselves so a bad header cannot leak
     * a handle the way {@code store.get()} does.
     */
    private static boolean gzipHeaderReadable(Path analysisFile) {
        try (InputStream raw = Files.newInputStream(analysisFile)) {
            new GZIPInputStream(raw).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code store.set}, retried while Windows refuses to replace the analysis file because another
     * handle still holds it. Zinc's Scala {@code set} declares no checked exceptions yet lets {@code
     * IO.move}'s {@link IOException} escape at runtime, so the catch has to be {@link Exception} for
     * the retry to see it at all.
     */
    private static void persistAnalysis(AnalysisStore store, Path analysisFile, AnalysisContents contents)
            throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                store.set(contents);
                return;
            } catch (Exception e) {
                if (!isWindows() || !isSharingViolation(e) || attempt == LOCK_ATTEMPTS) {
                    if (e instanceof RuntimeException re) throw re;
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                tryDeleteAnalysis(analysisFile);
                sleepBriefly(attempt);
            }
        }
    }

    /**
     * A Windows sharing denial, recognised by exception type: the system message is localized, so
     * matching its English text would silently never fire on a German or Japanese host. Only asked
     * on Windows — a POSIX {@link AccessDeniedException} is EACCES and permanent.
     */
    private static boolean isSharingViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof FileSystemException) return true;
        }
        return false;
    }

    /**
     * Remove the analysis file. On POSIX one {@code deleteIfExists} is the whole story. Windows may
     * deny the delete while another handle lingers ({@link FileSystemException}, not
     * {@link AccessDeniedException} — sharing violation is ERROR_SHARING_VIOLATION), so rename it
     * out of the way — allowed where deleting is not — and retry when even that is refused.
     */
    private static void tryDeleteAnalysis(Path analysisFile) {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(analysisFile);
                return;
            } catch (IOException e) {
                if (!isWindows() || !isSharingViolation(e) || attempt == LOCK_ATTEMPTS) return;
                if (renameAside(analysisFile)) return;
                sleepBriefly(attempt);
            }
        }
    }

    /** Move {@code file} aside so a fresh one can take its name; false when even that is denied. */
    private static boolean renameAside(Path file) {
        Path junk = file.resolveSibling(file.getFileName() + ".stale-" + System.nanoTime());
        try {
            Files.move(file, junk, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return false;
        }
        try {
            Files.deleteIfExists(junk);
        } catch (IOException ignored) {
            junk.toFile().deleteOnExit();
        }
        return true;
    }

    /** {@code os.name} read live so a test can spoof it; this module cannot see {@code HostPlatform}. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reconcile annotation-processor outputs against the previous build's provenance. For every
     * generated file recorded last time whose origins were <em>all</em> recompiled this run but which
     * was <em>not</em> regenerated, delete the generated source and its class files (an isolating
     * processor that stopped generating it — e.g. its annotation was removed). Then persist the
     * merged provenance for the next build.
     */
    private static void reconcileGeneratedOutputs(
            Path workdir, Path sourceOutput, Path classOutput, List<Path> compiledSources, Map<Path, Set<Path>> newProv)
            throws IOException {
        if (workdir == null) return;
        Path provFile = workdir.resolve("provenance.tsv");
        Map<Path, Set<Path>> prev = readProvenance(provFile);
        Set<Path> recompiled = new HashSet<>();
        for (Path p : compiledSources) recompiled.add(p.toAbsolutePath().normalize());
        Set<Path> regenerated = normalizeAll(newProv.keySet()); // this run's generated files, normalized

        Map<Path, Set<Path>> merged = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> e : prev.entrySet()) {
            Path gen = e.getKey(); // normalized on write
            Set<Path> origins = e.getValue();
            if (regenerated.contains(gen)) continue; // regenerated this run — newProv is authoritative
            boolean allRecompiled = !origins.isEmpty() && recompiled.containsAll(origins);
            if (allRecompiled) {
                deleteGeneratedOutputs(gen, sourceOutput, classOutput); // no longer generated → prune
            } else {
                merged.put(gen, origins); // owned by a source that was not recompiled — keep
            }
        }
        for (Map.Entry<Path, Set<Path>> e : newProv.entrySet()) {
            merged.put(e.getKey().toAbsolutePath().normalize(), normalizeAll(e.getValue()));
        }
        writeProvenance(provFile, merged);
    }

    private static void deleteGeneratedOutputs(Path gen, Path sourceOutput, Path classOutput) throws IOException {
        Files.deleteIfExists(gen); // the generated source/resource itself
        String name = gen.getFileName().toString();
        if (sourceOutput == null || classOutput == null || !name.endsWith(".java")) return;
        Path srcRoot = sourceOutput.toAbsolutePath().normalize();
        Path genAbs = gen.toAbsolutePath().normalize();
        if (!genAbs.startsWith(srcRoot)) return;
        Path rel = srcRoot.relativize(genAbs);
        Path pkgDir = classOutput.resolve(rel).getParent();
        if (pkgDir == null || !Files.isDirectory(pkgDir)) return;
        String stem = name.substring(0, name.length() - ".java".length());
        try (var s = Files.list(pkgDir)) {
            for (Path c : (Iterable<Path>) s::iterator) {
                String cn = c.getFileName().toString();
                // <stem>.class plus nested/anonymous <stem>$Inner.class
                if (cn.equals(stem + ".class") || cn.startsWith(stem + "$")) Files.deleteIfExists(c);
            }
        }
    }

    private static Set<Path> normalizeAll(Set<Path> paths) {
        Set<Path> out = new HashSet<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }

    private static Map<Path, Set<Path>> readProvenance(Path provFile) throws IOException {
        Map<Path, Set<Path>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(provFile)) return out;
        for (String line : Files.readAllLines(provFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            Set<Path> origins = new HashSet<>();
            for (int i = 1; i < parts.length; i++) origins.add(Path.of(parts[i]));
            out.put(Path.of(parts[0]), origins);
        }
        return out;
    }

    private static void writeProvenance(Path provFile, Map<Path, Set<Path>> prov) throws IOException {
        List<String> lines = new ArrayList<>(prov.size());
        for (Map.Entry<Path, Set<Path>> e : prov.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey().toString());
            for (Path origin : e.getValue()) sb.append('\t').append(origin);
            lines.add(sb.toString());
        }
        Files.write(provFile, lines, StandardCharsets.UTF_8);
    }

    /** Remove every {@code .class} file under {@code dir} (used before an analysis-less full compile). */
    private static void deleteClassFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".class")) Files.deleteIfExists(p);
            }
        }
    }

    private static Plan plan(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path workdir,
            Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath) {
        if (workdir == null || !Files.isRegularFile(workdir.resolve("zinc"))) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        if (Files.isRegularFile(workdir.resolve("aggregating"))) {
            return new Plan(
                    true,
                    "aggregating annotation processors",
                    allSources(sources, "aggregating annotation processors"));
        }
        FileConverter converter = PlainVirtualFileConverter.converter();
        Path analysisFile = workdir.resolve("zinc");
        AnalysisStore store = FileAnalysisStore.binary(analysisFile.toFile());
        Optional<AnalysisContents> prev = readAnalysis(store, analysisFile);
        if (prev.isEmpty()) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        MiniSetup setup = prev.get().getMiniSetup();
        String[] wantOpts = javacOptions(release, extraOptions, sourceOutput, processorPath);
        if (setup != null && optionsChanged(setup, wantOpts)) {
            return new Plan(true, "javac options changed", allSources(sources, "javac options changed"));
        }
        xsbti.compile.CompileAnalysis raw = prev.get().getAnalysis();
        if (!(raw instanceof Analysis analysis)) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        ReadStamps previous = analysis.readStamps();
        ReadStamps current = Stamps.timeWrapBinaryStamps(converter);
        LinkedHashMap<Path, String> invalid = new LinkedHashMap<>();

        for (Path src : sources) {
            if (src == null) continue;
            VirtualFile vf = converter.toVirtualFile(src);
            Stamp old = previous.source(vf);
            Stamp now = current.source(vf);
            if (stampChanged(old, now)) {
                invalid.putIfAbsent(src.toAbsolutePath().normalize(), "source changed");
            }
        }

        // Normalize the current source set once — the deleted-source scan below is otherwise
        // O(previous × current) with a normalize() per pair.
        HashSet<Path> currentNorm = new HashSet<>();
        for (Path s : sources) {
            if (s != null) currentNorm.add(s.toAbsolutePath().normalize());
        }
        Relations rel = analysis.relations();
        for (Map.Entry<VirtualFileRef, Stamp> e : previous.getAllSourceStamps().entrySet()) {
            Path src = pathOf(e.getKey(), converter);
            if (src == null) continue;
            if (currentNorm.contains(src)) continue;
            var names = rel.classNames(e.getKey());
            var nameIt = names.iterator();
            while (nameIt.hasNext()) {
                String className = nameIt.next();
                var users = rel.usesInternalClass(className);
                var userIt = users.iterator();
                while (userIt.hasNext()) {
                    String userClass = userIt.next();
                    var defs = rel.definesClass(userClass);
                    var defIt = defs.iterator();
                    while (defIt.hasNext()) {
                        Path userSrc = pathOf(defIt.next(), converter);
                        if (userSrc != null) {
                            invalid.putIfAbsent(userSrc, "used deleted " + className);
                        }
                    }
                }
            }
        }

        List<Path> libraries = new ArrayList<>(classpath == null ? List.of() : classpath);
        if (classOutput != null) libraries.add(classOutput);
        HashSet<Path> currentLibs = new HashSet<>();
        for (Path lib : libraries) {
            if (lib == null || !Files.exists(lib)) continue;
            Path abs = lib.toAbsolutePath().normalize();
            currentLibs.add(abs);
            VirtualFile vf = converter.toVirtualFile(lib);
            Stamp old = previous.library(vf);
            Stamp now = current.library(vf);
            if (!stampChanged(old, now)) continue;
            String why = "classpath " + lib.getFileName() + " changed";
            var users = rel.usesLibrary(vf);
            var it = users.iterator();
            while (it.hasNext()) {
                Path src = pathOf(it.next(), converter);
                if (src != null) invalid.putIfAbsent(src, why);
            }
        }
        for (Map.Entry<VirtualFileRef, Stamp> e : previous.getAllLibraryStamps().entrySet()) {
            Path lib = pathOf(e.getKey(), converter);
            if (lib != null && currentLibs.contains(lib)) continue;
            String name = lib == null ? "entry" : lib.getFileName().toString();
            String why = "classpath " + name + " changed";
            var users = rel.usesLibrary(e.getKey());
            var it = users.iterator();
            while (it.hasNext()) {
                Path src = pathOf(it.next(), converter);
                if (src != null) invalid.putIfAbsent(src, why);
            }
        }

        if (invalid.isEmpty()) {
            return new Plan(false, "zinc analysis current", List.of());
        }
        List<Invalidation> items = new ArrayList<>();
        for (Map.Entry<Path, String> e : invalid.entrySet()) {
            items.add(new Invalidation(e.getKey(), e.getValue()));
        }
        return new Plan(false, summarize(items), items);
    }

    private static List<Invalidation> allSources(List<Path> sources, String why) {
        List<Invalidation> out = new ArrayList<>();
        if (sources == null) return out;
        for (Path s : sources) {
            if (s != null) out.add(new Invalidation(s.toAbsolutePath().normalize(), why));
        }
        return out;
    }

    private static String summarize(List<Invalidation> items) {
        int n = items.size();
        String head = items.get(0).why();
        boolean same = true;
        for (Invalidation i : items) {
            if (!head.equals(i.why())) {
                same = false;
                break;
            }
        }
        if (same) {
            if ("source changed".equals(head)) {
                return n == 1 ? "1 source changed" : n + " sources changed";
            }
            return head;
        }
        return n == 1 ? "1 source" : n + " sources";
    }

    private static boolean optionsChanged(MiniSetup setup, String[] want) {
        MiniOptions opts = setup.options();
        if (opts == null) return false;
        String[] have = opts.javacOptions();
        if (have == null) return false;
        if (have.length != want.length) return true;
        for (int i = 0; i < have.length; i++) {
            if (!have[i].equals(want[i])) return true;
        }
        return false;
    }

    private static boolean stampChanged(Stamp old, Stamp now) {
        String a = old == null ? "" : old.writeStamp();
        String b = now == null ? "" : now.writeStamp();
        return !a.equals(b);
    }

    private static Path pathOf(VirtualFileRef ref, FileConverter converter) {
        if (ref == null) return null;
        try {
            if (ref instanceof VirtualFile vf) {
                return converter.toPath(vf).toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // fall through to id
        }
        String id = ref.id();
        if (id == null || id.isBlank()) return null;
        try {
            if (id.startsWith("file:"))
                return Path.of(URI.create(id)).toAbsolutePath().normalize();
            return Path.of(id).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Path.of(id);
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

    /**
     * Cache the Scala compiler (ScalaInstance + classloaders + bridge) per compiler-classpath so a
     * multi-module job pays scalac warm-up once and does not leak an unclosed URLClassLoader per
     * module. Keyed by version + classpath; scoped to the per-job worker process, which exits at
     * job end, reclaiming the loaders.
     */
    private static final Map<String, xsbti.compile.ScalaCompiler> SCALAC_CACHE = new ConcurrentHashMap<>();

    private static Compilers mixedCompilers(JavaCompiler javac, MixedScala mixed) {
        String key = mixed.version() + "\n"
                + mixed.compilerClasspath().stream().map(Path::toString).collect(Collectors.joining("\n"));
        xsbti.compile.ScalaCompiler scalac = SCALAC_CACHE.computeIfAbsent(key, k -> buildScalac(mixed));
        xsbti.compile.Javadoc javadoc =
                Javadoc.local().isDefined() ? Javadoc.local().get() : Javadoc.fork(scala.Option.empty());
        return ZincUtil.compilers(JavaTools.apply(javac, javadoc), scalac);
    }

    private static xsbti.compile.ScalaCompiler buildScalac(MixedScala mixed) {
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
        return ZincUtil.scalaCompiler(instance, bridge, cpOpts);
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
            FileConverter converter, ProcessorLoad processors, ApProvenance provenance) {
        JavaCompiler javac;
        if (processors.any()) {
            javac = new ProvenanceJavac(processors.loader(), provenance);
        } else {
            scala.Option<JavaCompiler> local = sbt.internal.inc.javac.JavaCompiler.local();
            javac = local.isDefined() ? local.get() : sbt.internal.inc.javac.JavaCompiler.fork(scala.Option.empty());
        }
        return new RecordingJavaCompiler(javac, converter);
    }

    /**
     * The processor path's classloader, kept open for the whole compile. Holds no {@link Processor}
     * instances: each javac round loads its own (see {@link #freshProcessors}).
     */
    private record ProcessorLoad(boolean any, URLClassLoader loader) implements AutoCloseable {
        static ProcessorLoad none() {
            return new ProcessorLoad(false, null);
        }

        @Override
        public void close() {
            if (loader == null) return;
            try {
                loader.close();
            } catch (IOException ignored) {
                // compile is finished; unload is best-effort
            }
        }
    }

    private static ProcessorLoad loadProcessors(List<Path> processorPath) {
        if (processorPath == null || processorPath.isEmpty()) return ProcessorLoad.none();
        URL[] urls = new URL[processorPath.size()];
        for (int i = 0; i < processorPath.size(); i++) {
            try {
                urls[i] = processorPath.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad processor path entry: " + processorPath.get(i), e);
            }
        }
        URLClassLoader loader = processorClassLoader(urls);
        // Full iteration, not a hasNext() probe: hasNext validates only the FIRST services entry
        // (it loads the provider class without instantiating), so a jar whose second entry is
        // broken would otherwise blow up mid-Zinc-compile as a raw ServiceConfigurationError.
        // Fail fast here instead, where compile()'s RuntimeException catch turns it into a
        // diagnosed Result. The instances are discarded — per-cycle sets come from
        // freshProcessors, because AbstractProcessor.init is single-shot.
        boolean any;
        try {
            any = false;
            for (Processor ignored : ServiceLoader.load(Processor.class, loader)) any = true;
        } catch (ServiceConfigurationError e) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // loader teardown is best-effort on the failure path
            }
            throw new IllegalStateException("broken annotation processor registration: " + e.getMessage(), e);
        }
        return new ProcessorLoad(any, loader);
    }

    /**
     * A new {@link Processor} instance set per Zinc cycle (each cycle is its own {@link JavacTask};
     * within one task javac's real annotation rounds correctly reuse these instances, per the
     * processor contract). {@link javax.annotation.processing.AbstractProcessor#init} is single-shot
     * — it throws {@code "Cannot call init more than once."} — so a set handed to one task is never
     * reusable by the next cycle. Do not move this inside the task. Package-private so a test can
     * pin that two loads share nothing.
     */
    static List<Processor> freshProcessors(URLClassLoader loader) {
        List<Processor> processors = new ArrayList<>();
        try {
            for (Processor p : ServiceLoader.load(Processor.class, loader)) processors.add(p);
        } catch (ServiceConfigurationError e) {
            // SCE extends Error and would sail past every catch in compile(), potentially after a
            // cycle already wrote class files without persistAnalysis. loadProcessors fails fast
            // for entries broken at load time; this guards ones that break mid-compile (a jar
            // rewritten under us).
            throw new IllegalStateException("broken annotation processor registration: " + e.getMessage(), e);
        }
        return processors;
    }

    /**
     * Processor-path loader. Parent is the platform loader so {@link Processor} resolves, but
     * {@link ServiceLoader} does not inherit {@code META-INF/services} registrations from the
     * worker classpath — those are not on the user's {@code -processorpath}.
     */
    static URLClassLoader processorClassLoader(URL[] urls) {
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static String[] javacOptions(int release, List<String> extra, Path sourceOutput, List<Path> processorPath) {
        List<String> opts = new ArrayList<>();
        // Unconditional: the charset a build decodes its sources with is not negotiable, because
        // nothing downstream can tell UTF-8 bytecode from Latin-1 bytecode. Neither reader here
        // actually consults the flag — Zinc hands javac its own VJavaFileObject, decoded by sbt.io's
        // hardcoded UTF-8, and ProvenanceJavac's file manager outranks it — so this is jk saying out
        // loud, to javac and to the MiniSetup Zinc persists and diffs the next compile against, what
        // those two already do silently. It does not reach the action key: that hashes the request's
        // extraOptions, not this list, and pins the charset with a constant of its own.
        opts.add("-encoding");
        opts.add(SOURCE_ENCODING.name());
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
        private final URLClassLoader loader;
        private final ApProvenance provenance;

        ProvenanceJavac(URLClassLoader loader, ApProvenance provenance) {
            this.loader = loader;
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
            // This path reads sources through the file manager, and the charset given here is what
            // decides: it outranks -encoding, which BaseFileManager.getDecoder only falls back to.
            // Same constant as the flag, so the two spellings of the charset cannot drift apart.
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, SOURCE_ENCODING)) {
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
                task.setProcessors(provenance.wrap(freshProcessors(loader)));
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
        private final HashSet<Path> seen = new HashSet<>(); // O(1) dedup instead of O(n) contains

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
                if (seen.add(path)) compiledSources.add(path);
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
