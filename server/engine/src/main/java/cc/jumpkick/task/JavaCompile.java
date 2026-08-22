// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.ForkedJavac;
import cc.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Action-cache front for Java compile. The {@code jk-java-compiler} worker owns Zinc incremental
 * recompile; this only does a whole-input action-key hit/miss around the fork.
 */
public final class JavaCompile {

    private JavaCompile() {}

    public record Result(
            boolean success,
            String outcome,
            String actionKey,
            List<CompileResult.Diagnostic> diagnostics,
            List<Path> compiledSources) {
        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            compiledSources = compiledSources == null ? List.of() : List.copyOf(compiledSources);
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

    public static Result run(
            String taskId,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            Path workerJar,
            Path generatedSourceDir)
            throws IOException {
        return run(
                taskId, request, jkVersion, useCache, true, cas, actionCache, stateDir, workerJar, generatedSourceDir);
    }

    public static Result run(
            String taskId,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            Path workerJar,
            Path generatedSourceDir)
            throws IOException {
        Path out = request.outputDir();
        Files.createDirectories(out);
        if (request.sources().isEmpty()) {
            return new Result(true, "no-sources", "", List.of(), List.of());
        }

        String key = ActionKey.forJavac(taskId, request, jkVersion);
        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent() && actionCache.restore(hit.get(), out)) {
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of(), List.of());
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

        CasPrewriter prewriter = CasPrewriter.watching(cas, out);
        ForkedJavac.Result wr;
        Map<String, String> outputs;
        try {
            wr = ForkedJavac.compile(new ForkedJavac.Request(
                    request.javaHome(),
                    workerJar,
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
                    request.scalaBridgeJar()));
        } finally {
            outputs = prewriter.finish();
        }
        if (!wr.success()) {
            return new Result(false, "errors", key, wr.diagnostics(), wr.compiledSources());
        }
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(true, "compiled-no-outputs", key, wr.diagnostics(), wr.compiledSources());
        }
        if (persist) {
            actionCache.storeWithOutputs(taskId, key, ActionKey.snapshotInputs(request), outputs);
        } else if (Files.isDirectory(stateDir)) {
            PathUtil.deleteRecursively(stateDir);
        }
        return new Result(true, "compiled", key, wr.diagnostics(), wr.compiledSources());
    }

    public static Prediction predict(
            String taskId, CompileRequest request, String jkVersion, ActionCache actionCache, Path stateDir)
            throws IOException {
        return predict(taskId, request, jkVersion, actionCache, stateDir, null, null);
    }

    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            Path workerJar)
            throws IOException {
        return predict(taskId, request, jkVersion, actionCache, stateDir, workerJar, null);
    }

    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            Path workerJar,
            Path generatedSourceDir)
            throws IOException {
        String key = ActionKey.forJavac(taskId, request, jkVersion);
        if (request.sources().isEmpty() || actionCache.lookup(key).isPresent()) {
            return new Prediction(Outcome.CACHE_HIT, key, request.sources().size(), "");
        }
        if (!Files.isRegularFile(stateDir.resolve("zinc"))) {
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
                    request.outputDir(),
                    gen,
                    request.release(),
                    request.extraOptions(),
                    stateDir,
                    request.scalaVersion(),
                    request.compilerClasspath(),
                    request.scalaLibraryJar(),
                    request.scalaCompilerJar(),
                    request.scalaBridgeJar()));
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
        int n = changed.size();
        String reason = n == 1 ? "1 source changed" : n + " sources changed";
        return new Prediction(Outcome.INCREMENTAL, key, n, reason, changed);
    }

    private static List<Path> changedSources(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        List<Path> changed = new ArrayList<>();
        for (Path s : request.sources()) {
            String key = s.toAbsolutePath().normalize().toString();
            String prior = priorInputs.get(key);
            String now = FileHashMemo.contentHash(s);
            if (prior == null || !prior.equals(now)) changed.add(s);
        }
        return changed;
    }

    private static boolean hasClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(f -> f.toString().endsWith(".class"));
        }
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
