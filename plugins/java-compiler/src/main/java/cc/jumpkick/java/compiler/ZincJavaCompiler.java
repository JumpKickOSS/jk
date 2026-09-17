// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.JavacLevel;
import cc.jumpkick.java.compiler.ScalaBridge.MixedScala;
import cc.jumpkick.java.compiler.ZincSetup.ClasspathLookup;
import cc.jumpkick.java.compiler.ZincSetup.QuietLogger;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.processing.Processor;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import sbt.internal.inc.APIs;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.CompileFailed;
import sbt.internal.inc.FreshCompilerCache;
import sbt.internal.inc.PlainVirtualFileConverter;
import sbt.internal.inc.Relations;
import sbt.internal.inc.Stamps;
import sbt.internal.inc.ZincUtil;
import scala.Option;
import xsbti.FileConverter;
import xsbti.Problem;
import xsbti.VirtualFile;
import xsbti.VirtualFileRef;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileOrder;
import xsbti.compile.CompileResult;
import xsbti.compile.Compilers;
import xsbti.compile.IncrementalCompiler;
import xsbti.compile.Inputs;
import xsbti.compile.JavaCompiler;
import xsbti.compile.MiniOptions;
import xsbti.compile.MiniSetup;
import xsbti.compile.PreviousResult;
import xsbti.compile.Setup;
import xsbti.compile.analysis.ReadStamps;
import xsbti.compile.analysis.Stamp;

/**
 * Zinc incremental compile. Java-only uses a dummy scalac; {@link #compileMixed} is the same
 * worker with a real Scala 3 compiler + published sbt bridge (one session for circular
 * Java↔Scala).
 *
 * <p>What stays here is the one invariant: the sources Zinc is asked to compile, the analysis it
 * diffs them against, and the javac options recorded in that analysis have to be decided together.
 * {@link #compile} writes the analysis and {@link #plan} reads it, and both derive the same
 * {@link #javacOptions} — a forecast built from a different option list is a forecast of a
 * different compile. Everything with no stake in that invariant has its own owner:
 * {@link ZincWorkdir} (the analysis file and its store, paired so they cannot name different
 * files), {@link GeneratedProvenance} (which generated file came from which source),
 * {@link ScalaBridge} (which jars scalac loads), {@link ProvenanceJavac} /
 * {@link RecordingJavaCompiler} / {@link CollectingReporter} / {@link ZincSetup} (Zinc's SPI).
 */
public final class ZincJavaCompiler {

    /**
     * The charset every source is read as. {@link ProvenanceJavac}, the in-process front end, already
     * reaches UTF-8 without being told, through the charset its file manager is built with, so naming
     * it changes no bytes today. What it changes is who owns the decision: javac's own fallback is
     * the host's
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

    /**
     * One compiler diagnostic. {@code key} is javac's own name for it ({@code
     * compiler.err.cant.resolve.location}), {@code ""} when the diagnostic came without one — a
     * thrown {@code CompileFailed}, an I/O failure, a compiler that reports text only.
     */
    public record Diag(String kind, @Nullable String file, long line, long col, String message, String key) {
        public Diag {
            key = key == null ? "" : key;
        }

        /** A diagnostic that carries no compiler key. */
        public Diag(String kind, @Nullable String file, long line, long col, String message) {
            this(kind, file, line, col, message, "");
        }
    }

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
    public static Result compileJava(JavaCompileJob job) {
        return compile(job, null);
    }

    /**
     * Forecast which sources Zinc would compile without writing outputs. Uses the previous analysis
     * under {@code workdir} plus current source/library stamps.
     */
    public static Plan planJava(JavaCompileJob job) {
        return plan(job);
    }

    /**
     * Joint Java+Scala incremental compile with a real Scala 3 compiler + published sbt bridge.
     * {@code compilerClasspath} is the worker compiler closure (not the project compile CP).
     */
    public static Result compileMixed(
            JavaCompileJob job,
            String scalaVersion,
            List<Path> compilerClasspath,
            @Nullable Path bridgeJar,
            @Nullable Path libraryJar,
            @Nullable Path compilerJar) {
        if (scalaVersion == null || scalaVersion.isBlank()) {
            throw new IllegalArgumentException("compileMixed requires scalaVersion");
        }
        if (compilerClasspath == null || compilerClasspath.isEmpty()) {
            throw new IllegalArgumentException("compileMixed requires a Scala compiler classpath");
        }
        return compile(job, new MixedScala(scalaVersion, compilerClasspath, bridgeJar, libraryJar, compilerJar));
    }

    private static Result compile(JavaCompileJob job, @Nullable MixedScala mixed) {
        List<Path> sources = job.sources();
        List<Path> classpath = job.classpath();
        Path classOutput = job.classOutput();
        Path workdir = Objects.requireNonNull(job.workdir(), "a compile needs a workdir; only a forecast may omit it");
        Path sourceOutput = job.sourceOutput();
        int release = job.release();
        List<String> extraOptions = job.extraOptions();
        List<Path> processorPath = job.processorPath();
        RecordingJavaCompiler javac = null;
        ProcessorLoad processors = ProcessorLoad.none();
        CompilePhases phases = CompilePhases.open(job.phasesLog());
        CollectingReporter reporter = new CollectingReporter();
        try {
            Files.createDirectories(classOutput);
            Files.createDirectories(workdir);
            if (sourceOutput != null) Files.createDirectories(sourceOutput);
            ZincWorkdir zinced = ZincWorkdir.of(workdir);
            zinced.discardAnalysisIfAggregating();

            IncrementalCompiler zinc = ZincUtil.defaultIncrementalCompiler();
            FileConverter converter = PlainVirtualFileConverter.converter();
            ApProvenance provenance = new ApProvenance();
            processors = processorsFor(processorPath);
            phases.mark("processors");
            ConstantDeps constants = new ConstantDeps();
            javac = recordingJavac(converter, processors, provenance, constants, reporter);
            Compilers compilers = ScalaBridge.compilersFor(javac, mixed);

            VirtualFile[] sourceFiles = ZincSetup.virtual(sources, converter);
            List<Path> cp = new ArrayList<>(classpath);
            // Scala 3.8+ ships the stdlib as scala-library (same version as the compiler);
            // scala3-library_3 is an empty stub. Zinc's ClasspathOptions only move a library
            // already on this list onto scalac's bootclasspath — they do not invent it.
            for (Path lib : ScalaBridge.extraClasspath(mixed)) {
                if (!cp.contains(lib)) cp.add(lib);
            }
            cp.add(classOutput);
            VirtualFile[] cpFiles = ZincSetup.virtual(cp, converter);
            phases.mark("virtualise");

            AnalysisStore store = zinced.store();
            // One stamper for the compile and for the classpath hash: both must see the same
            // (mtime-cached) hash of a jar or the two could disagree mid-compile.
            ReadStamps stamper = Stamps.timeWrapBinaryStamps(converter);
            // The same stamper judges a producer's analysis against its classes on disk.
            ClasspathAnalyses producers = ClasspathAnalyses.of(job.classpathAnalyses(), stamper);
            Setup setup = Setup.of(
                    new ClasspathLookup(producers, converter),
                    false,
                    zinced.analysisFile(),
                    new FreshCompilerCache(),
                    ZincSetup.incOptions(stamper, converter, classOutput, producers),
                    reporter,
                    ZincSetup.noExtra());

            CompileOptions options = CompileOptions.of()
                    .withClasspath(cpFiles)
                    .withSources(sourceFiles)
                    .withClassesDirectory(classOutput)
                    .withScalacOptions(ScalaBridge.scalacOptions(mixed, release))
                    .withJavacOptions(
                            javacOptions(release, extraOptions, sourceOutput, processorPath, sources, classpath))
                    .withOrder(CompileOrder.Mixed)
                    .withConverter(converter)
                    .withStamper(stamper);

            Optional<AnalysisContents> prev = zinced.readAnalysis(store);
            phases.mark("read-analysis");
            if (prev.isEmpty()) {
                // No usable previous analysis ⇒ a full compile. Zinc only deletes removed-source
                // products when it has a prior analysis to diff against, so a full compile must start
                // from a clean class output or renamed/removed/no-longer-generated classes linger and
                // ship in the jar.
                deleteClassFiles(classOutput);
            }
            phases.mark("clear-output");
            PreviousResult previous = prev.isPresent()
                    ? PreviousResult.of(prev.get().getAnalysis(), prev.get().getMiniSetup())
                    : PreviousResult.of(Optional.empty(), Optional.empty());

            Inputs inputs = Inputs.of(compilers, options, setup, previous);
            CompileResult compiled = zinc.compile(inputs, QuietLogger.INSTANCE);
            phases.mark("zinc-compile");
            if (reporter.hasErrors()) {
                return new Result(false, reporter.diagnostics(), javac.compiledSources(), provenance.generated);
            }
            // Delete generated sources/classes whose (recompiled) origin no longer generates them —
            // e.g. an @Gen annotation was removed. Zinc can't do this: generated files aren't in its
            // source set, so their class files are unattributed products it never prunes.
            GeneratedProvenance.of(workdir)
                    .reconcile(sourceOutput, classOutput, javac.compiledSources(), provenance.generated);
            zinced.markAggregating(provenance.aggregating());
            phases.mark("reconcile");
            // The edges javac erased (inlined constants) go into the analysis before it is written:
            // Zinc's own Java analysis never saw them, and without them the next compile cannot
            // know which classes to recompile when a constant's value changes.
            CompileAnalysis analysis = compiled.analysis();
            if (analysis instanceof Analysis full) {
                analysis = ConstantDeps.addTo(
                        full, constants.edges(), producers, List.of(cpFiles), classOutput, stamper, converter);
            }
            zinced.persistAnalysis(store, AnalysisContents.create(analysis, compiled.setup()));
            phases.mark("persist-analysis");
            phases.write(classOutput, sources.size());
            return new Result(true, reporter.diagnostics(), javac.compiledSources(), provenance.generated);
        } catch (IOException e) {
            // Errors.text, not getMessage(): a message-less IOException would otherwise put null on
            // the wire as the diagnostic text.
            return new Result(false, List.of(new Diag("ERROR", null, 0, 0, Errors.text(e))), List.of());
        } catch (CompileFailed failed) {
            // The reporter's own rows carry javac's keys; the problems on the throw are the same
            // rows without them, so they stand in only when the reporter logged nothing.
            List<Diag> diags = new ArrayList<>(reporter.diagnostics());
            if (diags.isEmpty()) {
                for (Problem p : failed.problems()) diags.add(CollectingReporter.toDiag(p));
            }
            if (diags.isEmpty()) {
                diags.add(new Diag(
                        "ERROR", null, 0, 0, failed.getMessage() == null ? "compile failed" : failed.getMessage()));
            }
            return new Result(false, diags, javac == null ? List.of() : javac.compiledSources());
        } catch (RuntimeException e) {
            return new Result(false, List.of(new Diag("ERROR", null, 0, 0, crashed(e))), List.of());
        }
    }

    /** Frames of the compiler's own crash a diagnostic carries: enough to name the code that threw. */
    static final int CRASH_FRAMES = 12;

    /**
     * An unexpected exception as a compile diagnostic: its class and message, each cause's, and the
     * innermost cause's top frames. The type is the most useful thing about such a failure and the
     * frames say whose code threw — javac's, a plugin's, a processor's or this worker's — which a
     * bare class name cannot.
     */
    static String crashed(Throwable e) {
        StringBuilder sb = new StringBuilder("the compiler worker failed: ");
        Throwable t = e;
        while (true) {
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            if (t.getCause() == null || t.getCause() == t) break;
            sb.append("\n  caused by ");
            t = t.getCause();
        }
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, CRASH_FRAMES); i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        if (frames.length > CRASH_FRAMES)
            sb.append("\n\t... ").append(frames.length - CRASH_FRAMES).append(" more");
        return sb.toString();
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

    private static Plan plan(JavaCompileJob job) {
        List<Path> sources = job.sources();
        List<Path> classpath = job.classpath();
        Path classOutput = job.classOutput();
        Path workdir = job.workdir();
        Path sourceOutput = job.sourceOutput();
        int release = job.release();
        List<String> extraOptions = job.extraOptions();
        List<Path> processorPath = job.processorPath();
        if (workdir == null) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        ZincWorkdir zinced = ZincWorkdir.of(workdir);
        if (!zinced.hasAnalysis()) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        if (zinced.aggregating()) {
            return new Plan(
                    true,
                    "aggregating annotation processors",
                    allSources(sources, "aggregating annotation processors"));
        }
        FileConverter converter = PlainVirtualFileConverter.converter();
        Optional<AnalysisContents> prev = zinced.readAnalysis(zinced.store());
        if (prev.isEmpty()) {
            return new Plan(true, "no zinc analysis", allSources(sources, "no zinc analysis"));
        }
        MiniSetup setup = prev.get().getMiniSetup();
        String[] wantOpts = javacOptions(release, extraOptions, sourceOutput, processorPath, sources, classpath);
        if (setup != null && optionsChanged(setup, wantOpts)) {
            return new Plan(true, "javac options changed", allSources(sources, "javac options changed"));
        }
        CompileAnalysis raw = prev.get().getAnalysis();
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

        forecastExternals(analysis, ClasspathAnalyses.of(job.classpathAnalyses(), current), converter, invalid);

        if (invalid.isEmpty()) {
            return new Plan(false, "zinc analysis current", List.of());
        }
        List<Invalidation> items = new ArrayList<>();
        for (Map.Entry<Path, String> e : invalid.entrySet()) {
            items.add(new Invalidation(e.getKey(), e.getValue()));
        }
        return new Plan(false, summarize(items), items);
    }

    /**
     * Classes a producer's analysis answered for when {@code analysis} was written are external
     * dependencies, tracked by the producer's per-class hashes rather than by a library stamp.
     * They are forecast the way Zinc invalidates them: changed hashes in the producer's current
     * analysis, or no producer analysis answering for the class any more, invalidate every source
     * whose classes use it.
     */
    private static void forecastExternals(
            Analysis analysis, ClasspathAnalyses producers, FileConverter converter, Map<Path, String> invalid) {
        APIs apis = analysis.apis();
        Relations rel = analysis.relations();
        var externals = apis.allExternals().iterator();
        while (externals.hasNext()) {
            String external = externals.next();
            if (producers.sameApi(external, apis.externalAPI(external))) continue;
            String why = "dependency " + external + " changed";
            var users = rel.usesExternal(external).iterator();
            while (users.hasNext()) {
                var defs = rel.definesClass(users.next()).iterator();
                while (defs.hasNext()) {
                    Path userSrc = pathOf(defs.next(), converter);
                    if (userSrc != null) invalid.putIfAbsent(userSrc, why);
                }
            }
        }
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
        return ZincSetup.stampChanged(old, now);
    }

    private static @Nullable Path pathOf(@Nullable VirtualFileRef ref, FileConverter converter) {
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

    /**
     * Every in-process compile goes through {@link ProvenanceJavac}, with or without processors,
     * because it compiles through a file manager held across compiles while Zinc's own
     * {@code JavaCompiler.local} opens one per compile and re-indexes the whole classpath with it.
     *
     * <p>The processor loader is handed over only when there is actually something to install:
     * {@code setProcessors} with an empty list would disable a processor-free module's ability to
     * discover a processor from its own compile classpath.
     *
     * <p>Forking stays the fallback for a runtime with no in-process compiler — a JRE rather than a
     * JDK — which is the one case {@link ProvenanceJavac} cannot serve.
     */
    private static RecordingJavaCompiler recordingJavac(
            FileConverter converter,
            ProcessorLoad processors,
            ApProvenance provenance,
            ConstantDeps constants,
            CollectingReporter reporter) {
        // A forked javac cannot be listened to, so its compiles record no inlined-constant edges
        // and its diagnostics carry no keys — the same limit Zinc's own forked mode has.
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler() != null
                ? new ProvenanceJavac(
                        processors.any() ? processors.loader() : null, provenance, SOURCE_ENCODING, constants, reporter)
                : sbt.internal.inc.javac.JavaCompiler.fork(Option.empty());
        return new RecordingJavaCompiler(javac, converter);
    }

    /**
     * The processor path's classloader, held for the life of the worker. Holds no {@link Processor}
     * instances: each javac round loads its own (see {@link #freshProcessors}).
     */
    record ProcessorLoad(boolean any, @Nullable URLClassLoader loader) {
        static ProcessorLoad none() {
            return new ProcessorLoad(false, null);
        }

        void discard() {
            if (loader == null) return;
            try {
                loader.close();
            } catch (IOException ignored) {
                // nothing is compiling against it any more; unload is best-effort
            }
        }
    }

    /** How many distinct processor paths a worker keeps loaders for before evicting the oldest. */
    private static final int PROCESSOR_LOADER_CAP = 4;

    /**
     * One loader per processor path, reused by every compile in the job that asks for that path.
     *
     * <p>Building it per compile means each module loads the processor's classes from its jar from
     * scratch. That is close to free on Linux and brutal on NTFS: a build was measured opening one
     * Lombok jar 14,797 times in fifteen seconds, and every open is followed by a walk of all nine
     * path components to canonicalise it, at roughly 92 µs an open against 4 µs on ext4.
     *
     * <p>Bounded and evicting, because a workspace may use several processor paths and a loader that
     * nothing will ask for again should not be held open. Access is synchronized: a worker compiles
     * one module at a time today, but the protocol allows concurrent items and a classloader shared
     * by accident is worse than a lock nobody contends.
     */
    private static final LinkedHashMap<List<Path>, ProcessorLoad> PROCESSOR_LOADERS =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<Path>, ProcessorLoad> eldest) {
                    if (size() <= PROCESSOR_LOADER_CAP) return false;
                    eldest.getValue().discard();
                    return true;
                }
            };

    /**
     * The loader for {@code processorPath}, built once per worker and reused after that.
     *
     * <p>Reuse is bounded by the job: the worker's pull loop stays up across the modules of one job
     * and exits on {@code DONE}, so no loader outlives the build that created it, and a processor jar
     * is read from the immutable dependency store rather than rewritten under a running build.
     */
    static synchronized ProcessorLoad processorsFor(List<Path> processorPath) {
        if (processorPath == null || processorPath.isEmpty()) return ProcessorLoad.none();
        List<Path> key = List.copyOf(processorPath);
        ProcessorLoad cached = PROCESSOR_LOADERS.get(key);
        if (cached != null) return cached;
        ProcessorLoad load = loadProcessors(key);
        PROCESSOR_LOADERS.put(key, load);
        return load;
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

    /** javac's spelling of a module descriptor source. */
    static final String MODULE_INFO = "module-info.java";

    /**
     * Whether this compile runs inside a named module: it compiles a {@code module-info.java}, or
     * its options patch one ({@code --patch-module}, a test compile against a modular main). Such a
     * compile reads its dependencies from the module path, so the classpath rides
     * {@code --module-path} as well — a plain jar there is an automatic module, a classes directory
     * with a descriptor an explicit one, and a plain directory is ignored by the module system while
     * still serving the classpath.
     */
    static boolean modular(List<Path> sources, List<String> extra) {
        for (Path source : sources) {
            Path name = source.getFileName();
            if (name != null && MODULE_INFO.equals(name.toString())) return true;
        }
        return containsFlag(extra, "--patch-module");
    }

    private static String[] javacOptions(
            int release,
            List<String> extra,
            @Nullable Path sourceOutput,
            List<Path> processorPath,
            List<Path> sources,
            List<Path> classpath) {
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
        if (!containsFlag(extra, "--release"))
            opts.addAll(JavacLevel.options(release, extra == null ? List.of() : extra));
        if (sourceOutput != null && !containsFlag(extra, "-s")) {
            opts.add("-s");
            opts.add(sourceOutput.toAbsolutePath().toString());
        }
        if (processorPath != null && !processorPath.isEmpty() && !containsFlag(extra, "-processorpath")) {
            opts.add("-processorpath");
            opts.add(Classpaths.join(processorPath));
        }
        if (!classpath.isEmpty() && modular(sources, extra) && !containsFlag(extra, "--module-path")) {
            opts.add("--module-path");
            opts.add(Classpaths.join(classpath));
        }
        if (extra != null) opts.addAll(extra);
        return opts.toArray(String[]::new);
    }

    private static boolean containsFlag(List<String> extra, String flag) {
        if (extra == null) return false;
        for (String o : extra) if (flag.equals(o)) return true;
        return false;
    }
}
