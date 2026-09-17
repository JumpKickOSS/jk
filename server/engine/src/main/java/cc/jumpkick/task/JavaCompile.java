// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.ForkedJavac;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Action-cache front for Java compile. The {@code jk-java-compiler} worker owns Zinc incremental
 * recompile; this only does a whole-input action-key hit/miss around the fork.
 *
 * <p>The key hashes the sources before the worker reads them. A source whose bytes move while the
 * worker runs is re-read afterwards and named in {@link Result#movedSources}: the classes on disk
 * may come from either version, so no record is stored under the key and the Zinc analysis is
 * dropped, and the next build compiles the module from what is then on disk.
 */
public final class JavaCompile {

    private JavaCompile() {}

    /** Test seam: runs once the key is taken and before the worker is forked. */
    static volatile Runnable beforeFork = () -> {};

    /**
     * @param movedSources sources whose bytes changed between the key and the worker's return;
     *     the compile is not recorded and the next build recompiles them
     * @param waitMillis time spent queued behind the shared compiler worker; see TaskContext#waited
     */
    public record Result(
            boolean success,
            String outcome,
            String actionKey,
            List<CompileResult.Diagnostic> diagnostics,
            List<Path> compiledSources,
            List<Path> movedSources,
            long waitMillis) {
        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            compiledSources = compiledSources == null ? List.of() : List.copyOf(compiledSources);
            movedSources = movedSources == null ? List.of() : List.copyOf(movedSources);
        }

        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    public enum Outcome {
        CACHE_HIT,
        INCREMENTAL,
        FULL
    }

    public record Prediction(Outcome outcome, String actionKey, int sourceCount, String reason, List<Path> sources) {
        public Prediction {
            reason = reason == null ? "" : reason;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public Prediction(Outcome outcome, String actionKey, int sourceCount, String reason) {
            this(outcome, actionKey, sourceCount, reason, List.of());
        }

        public Prediction(Outcome outcome, String actionKey, int sourceCount) {
            this(outcome, actionKey, sourceCount, "");
        }
    }

    /** {@code label} names the module and step in a worker failure; see {@link ForkedJavac.Request#label()}. */
    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        return run(
                taskId,
                label,
                request,
                jkVersion,
                useCache,
                true,
                cas,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env);
    }

    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        Path out = Objects.requireNonNull(request.outputDir(), "outputDir");
        Files.createDirectories(out);
        if (request.sources().isEmpty()) {
            return new Result(true, "no-sources", "", List.of(), List.of(), List.of(), 0L);
        }

        String key = ActionKey.forJavac(taskId, request, jkVersion);
        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent() && actionCache.restore(hit.get(), out)) {
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of(), List.of(), List.of(), 0L);
            }
        }

        Path gen = generatedSourceDir != null ? generatedSourceDir : stateDir.resolve("gen");
        Files.createDirectories(gen);
        if (!useCache) {
            deleteClasses(out);
            if (Files.isDirectory(stateDir)) PathUtil.deleteRecursively(stateDir);
            Files.createDirectories(stateDir);
        } else if (Files.isDirectory(stateDir) && !hasClasses(out)) {
            PathUtil.deleteRecursively(stateDir);
            Files.createDirectories(stateDir);
        }

        // The record's inputs describe the bytes the key hashed, so they are taken here, before
        // the worker reads a source; the same snapshot is what the post-fork re-read is held to.
        Map<String, String> inputs = ActionKey.snapshotInputs(request);
        beforeFork.run();
        CasPrewriter prewriter = CasPrewriter.watching(cas, out);
        ForkedJavac.Result wr;
        try {
            wr = ForkedJavac.compile(new ForkedJavac.Request(
                            request.javaHome(),
                            Objects.requireNonNull(workerJar, "workerJar"),
                            request.sources(),
                            request.classpath(),
                            request.processorPath(),
                            out,
                            gen,
                            request.release(),
                            request.extraOptions(),
                            stateDir,
                            request.scalaVersion(),
                            request.compilerClasspath(),
                            request.scalaLibraryJar(),
                            request.scalaCompilerJar(),
                            request.scalaBridgeJar(),
                            env)
                    .withClasspathAnalyses(producerAnalyses(request, stateDir))
                    .withLabel(label));
        } catch (RuntimeException | Error compileFailure) {
            // The failure is the result. finish() walks and hashes the output tree, and a walk
            // over what a dying compiler left behind can throw too — from a finally block that
            // throw would replace the compiler's own exception with a filesystem one.
            prewriter.close();
            throw compileFailure;
        }
        Map<String, String> outputs = prewriter.finish();
        if (!wr.success()) {
            return new Result(false, "errors", key, wr.diagnostics(), wr.compiledSources(), List.of(), wr.waitMillis());
        }
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(
                    true,
                    "compiled-no-outputs",
                    key,
                    wr.diagnostics(),
                    wr.compiledSources(),
                    List.of(),
                    wr.waitMillis());
        }
        List<Path> moved = ActionKey.changedSources(request.sources(), inputs);
        if (!moved.isEmpty()) {
            // Neither version of the moved source is what the key names, and the analysis may
            // pair its earlier hash with classes from its later bytes: a revert to those earlier
            // bytes would then compile nothing. Nothing is recorded and the analysis goes, so the
            // next build compiles the module whole from what is on disk.
            if (Files.isDirectory(stateDir)) PathUtil.deleteRecursively(stateDir);
            return new Result(true, "compiled", key, wr.diagnostics(), wr.compiledSources(), moved, wr.waitMillis());
        }
        if (persist) {
            actionCache.storeWithOutputs(taskId, key, inputs, outputs);
        } else if (Files.isDirectory(stateDir)) {
            PathUtil.deleteRecursively(stateDir);
        }
        return new Result(true, "compiled", key, wr.diagnostics(), wr.compiledSources(), List.of(), wr.waitMillis());
    }

    public static Prediction predict(
            String taskId, CompileRequest request, String jkVersion, ActionCache actionCache, Path stateDir)
            throws IOException {
        // No worker jar, so no plan worker is forked; the env is never consulted.
        return predict(taskId, request, jkVersion, actionCache, stateDir, null, null, WorkerEnv.strict());
    }

    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            WorkerEnv env)
            throws IOException {
        return predict(taskId, request, jkVersion, actionCache, stateDir, workerJar, null, env);
    }

    /** {@code env} mirrors the build's so the plan worker shares its pool rather than starting a second one. */
    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        return predict(
                taskId,
                request,
                jkVersion,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env,
                ClasspathAbi::token);
    }

    /**
     * As above, with the compile classpath keyed through {@code cp}: the forecast's view of a
     * sibling tree the build restores before this compile runs.
     */
    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env,
            ActionKey.EntryToken cp)
            throws IOException {
        String key = ActionKey.forJavac(taskId, request, jkVersion, cp);
        if (request.sources().isEmpty() || actionCache.lookup(key).isPresent()) {
            return new Prediction(Outcome.CACHE_HIT, key, request.sources().size(), "");
        }
        if (!Files.isRegularFile(stateDir.resolve(ProducerAnalyses.ANALYSIS_FILE))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "no zinc analysis");
        }
        if (workerJar != null && Files.isRegularFile(workerJar)) {
            Path gen = generatedSourceDir != null ? generatedSourceDir : stateDir.resolve("gen");
            Files.createDirectories(gen);
            ForkedJavac.Plan plan = ForkedJavac.plan(new ForkedJavac.Request(
                            request.javaHome(),
                            workerJar,
                            request.sources(),
                            request.classpath(),
                            request.processorPath(),
                            Objects.requireNonNull(request.outputDir(), "outputDir"),
                            gen,
                            request.release(),
                            request.extraOptions(),
                            stateDir,
                            request.scalaVersion(),
                            request.compilerClasspath(),
                            request.scalaLibraryJar(),
                            request.scalaCompilerJar(),
                            request.scalaBridgeJar(),
                            env)
                    .withClasspathAnalyses(producerAnalyses(request, stateDir)));
            List<Path> files = plan.sources();
            if (plan.full()) {
                return new Prediction(
                        Outcome.FULL,
                        key,
                        files.isEmpty() ? request.sources().size() : files.size(),
                        plan.reason(),
                        files);
            }
            String reason = plan.reason();
            if (reason.isBlank()) {
                int n = files.size();
                reason = n == 1 ? "1 source" : n + " sources";
            }
            return new Prediction(Outcome.INCREMENTAL, key, files.size(), reason, files);
        }
        Optional<ActionCache.ActionRecord> prior = actionCache.lastFor(taskId);
        if (prior.isEmpty()) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "no prior compile record");
        }
        Map<String, String> in = prior.get().inputs();
        if (!String.valueOf(request.release()).equals(in.getOrDefault("release", null))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "release changed");
        }
        if (!String.join(",", request.extraOptions()).equals(in.getOrDefault("options", ""))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "javac options changed");
        }
        List<Path> changed = changedSources(request, in);
        if (changed.isEmpty()) {
            // No source moved, yet the key missed: a classpath entry's API or a processor did.
            // Naming it is what keeps why-rebuilt from reading "nothing changed, rebuilt anyway".
            String classpathReason = changedClasspath(request, in);
            if (classpathReason != null) {
                return new Prediction(Outcome.INCREMENTAL, key, 0, classpathReason, List.of());
            }
        }
        int n = changed.size();
        String reason = n == 1 ? "1 source changed" : n + " sources changed";
        return new Prediction(Outcome.INCREMENTAL, key, n, reason, changed);
    }

    /**
     * The producer analyses the worker is handed for {@code request}'s classpath. Every Java
     * compile's state dir sits beside this one under the same incremental root, so the root is
     * {@code stateDir}'s parent; a state dir with no parent (a test's bare directory) has no
     * producers to find.
     */
    private static Map<Path, Path> producerAnalyses(CompileRequest request, Path stateDir) {
        Path incrementalRoot = stateDir.toAbsolutePath().normalize().getParent();
        if (incrementalRoot == null) return Map.of();
        return ProducerAnalyses.forClasspath(request.classpath(), incrementalRoot);
    }

    /**
     * Which classpath entries' tokens differ from the prior record's, spelled as the record spells
     * them ({@link ActionKey#snapshotInputs}): {@code cp:} by ABI, {@code pp:} by content. Null when
     * none differ.
     */
    private static @Nullable String changedClasspath(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        Map<String, String> now = ActionKey.snapshotInputs(request);
        List<String> api = new ArrayList<>();
        List<String> processors = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String k = e.getKey();
            boolean cp = k.startsWith("cp:");
            if (!cp && !k.startsWith("pp:")) continue;
            if (e.getValue().equals(priorInputs.get(k))) continue;
            Path entry = Path.of(k.substring(3));
            (cp ? api : processors).add(String.valueOf(entry.getFileName()));
        }
        if (api.isEmpty() && processors.isEmpty()) return null;
        if (!api.isEmpty()) return "dependency API changed (" + String.join(", ", api) + ")";
        return "processor changed (" + String.join(", ", processors) + ")";
    }

    private static List<Path> changedSources(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        return ActionKey.changedSources(request.sources(), priorInputs);
    }

    private static boolean hasClasses(Path dir) throws IOException {
        return PathUtil.anyRegularFile(dir, d -> false, f -> f.toString().endsWith(".class"));
    }

    private static void deleteClasses(Path out) throws IOException {
        if (!Files.isDirectory(out)) return;
        try (var walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".class")) Files.deleteIfExists(p);
            }
        }
    }
}
