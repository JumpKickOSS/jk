// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincInputs;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.WorkerCompileDriver;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Action-cache front for the secondary-language compiles (Kotlin, Groovy): a whole-input
 * action-key hit/miss around the worker fork. Both languages share one {@link Result} and one
 * post-fork fold; they differ only in the key, the pre-fork hygiene arm, and the worker dispatch
 * ({@link WorkerCompileDriver} switches on the request type). For Kotlin, the worker owns
 * incremental recompile and the cheap {@code .kstamp} check sits in front of this; the Groovy
 * worker is always a full compile.
 */
public final class LangCompile {

    private LangCompile() {}

    /** Outcome of a {@link #run}. {@code diagnostics} are the worker's, one entry each. */
    public record Result(
            boolean success, String outcome, String actionKey, List<CompileResult.Diagnostic> diagnostics) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }

        /** Joined diagnostics for logs and exception messages. */
        public String output() {
            return diagnostics.stream().map(CompileResult.Diagnostic::describe).collect(Collectors.joining("\n"));
        }
    }

    /**
     * @param useCache when false ({@code --redo}/{@code --force}), skip restore/skip — still
     * <em>write</em> the action cache after a successful compile so the next explain/build can
     * CACHE_HIT.
     */
    public static Result run(
            String taskId,
            KotlincRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env,
            KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, true, cas, actionCache, env, snapshotter);
    }

    /**
     * As above with {@code persist}: false for {@code jk verify}'s scratch rebuild, whose
     * scratch-salted keys can never recur — a successful compile must not leave an orphan action
     * record behind.
     */
    public static Result run(
            String taskId,
            KotlincRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env,
            KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        String key = ActionKey.forKotlinc(taskId, request, jkVersion, snapshotter);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            // A failed restore (missing/corrupt blob) falls through to a real compile.
            if (hit.isPresent() && actionCache.restore(hit.get(), request.outputDir())) {
                return cacheHit(key);
            }
        }

        Files.createDirectories(request.outputDir());
        // Incremental state is only valid alongside the outputs it produced: if the output
        // dir is (now) empty of classes while IC state survives (a cleaned target/, a fresh
        // checkout with a warm cache), BTA would compile "only what changed" into the void
        // and report success with a near-empty dir. Start the IC state over instead.
        Path workingDir = request.workingDir();
        if (request.incremental()
                && workingDir != null
                && Files.isDirectory(workingDir)
                && (!hasClasses(request.outputDir()) || javaDeclarationsMoved(actionCache, taskId, request))) {
            PathUtil.deleteRecursively(workingDir);
        }
        return forkAndStore(
                taskId,
                key,
                request.outputDir(),
                request.sources().isEmpty(),
                persist,
                cas,
                actionCache,
                // Recorded for why-rebuilt: the tokens are memoized by the key above, so a lookup each.
                () -> ActionKey.kotlincInputs(request, snapshotter),
                () -> WorkerCompileDriver.compile(request, env));
    }

    /**
     * @param useCache when false ({@code --redo}/{@code --force}), skip restore/skip — still
     * <em>write</em> the action cache after a successful compile so the next explain/build can
     * CACHE_HIT.
     */
    public static Result run(
            String taskId,
            GroovycRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, true, cas, actionCache, env);
    }

    /**
     * As above with {@code persist}: false for {@code jk verify}'s scratch rebuild, whose
     * scratch-salted keys can never recur — a successful compile must not leave an orphan action
     * record behind.
     */
    public static Result run(
            String taskId,
            GroovycRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env)
            throws IOException {
        String key = ActionKey.forGroovyc(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                // Restore into a CLEAN dir: the worker is a full compile, so anything already
                // here is a previous source set — a deleted.groovy's class would resurrect
                // through the assemble merge and poison later records. A failed restore
                // (missing/corrupt blob) falls through to the real compile below.
                wipe(request);
                if (actionCache.restore(hit.get(), request.outputDir())) {
                    return cacheHit(key);
                }
            }
        }
        wipe(request);
        return forkAndStore(
                taskId,
                key,
                request.outputDir(),
                request.sources().isEmpty(),
                persist,
                cas,
                actionCache,
                () -> ActionKey.snapshotInputs(request),
                () -> WorkerCompileDriver.compile(request, env));
    }

    /**
     * The shared post-hygiene fold: prewrite the CAS while the worker runs, then judge and store.
     * {@code inputs} is what the record remembers of the request, for {@code jk why-rebuilt}.
     */
    private static Result forkAndStore(
            String taskId,
            String key,
            Path outputDir,
            boolean noSources,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Inputs inputs,
            Supplier<CompileResult> fork)
            throws IOException {
        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(outputDir);
        CasPrewriter prewriter = CasPrewriter.watching(cas, outputDir);
        CompileResult cr;
        Map<String, String> outputs;
        try {
            cr = fork.get();
        } finally {
            outputs = prewriter.finish();
        }
        if (!cr.success()) {
            return new Result(false, "errors", key, cr.diagnostics());
        }
        // Never cache a zero-output "success" for a non-empty source set: a compiler convinced
        // nothing changed (stale incremental state) can report success over an empty output
        // dir, and caching that poisons every later run under the same key.
        if (outputs.isEmpty() && !noSources) {
            return new Result(true, "compiled-no-outputs", key, cr.diagnostics());
        }
        // Store on rebuild/force too: the work re-ran and must refresh the action pointer so
        // the next non-rebuild explain sees CACHE_HIT (same as JavaCompile). Only
        // ephemeral (verify-scratch) runs skip the write — their keys never recur.
        if (persist) actionCache.storeWithOutputs(taskId, key, inputs.snapshot(), outputs);
        return new Result(true, "compiled", key, cr.diagnostics());
    }

    /** The inputs a stored record carries for {@code jk why-rebuilt}; computed only when a record is written. */
    @FunctionalInterface
    private interface Inputs {
        Map<String, String> snapshot() throws IOException;
    }

    private static Result cacheHit(String key) {
        return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of());
    }

    /**
     * True when the Java declarations kotlinc reads through {@code -Xjava-source-roots} differ from
     * the ones the task's last compile recorded. The incremental state tracks Kotlin sources and
     * classpath snapshots; a Java signature it read from source is invisible to it, so an
     * incremental compile after such an edit finds nothing to do and leaves Kotlin classes linked
     * against a declaration that no longer exists. The state is started over instead, and the
     * full compile reads the new declarations. A mixed module with no recorded compile is treated
     * as moved: nothing vouches for the state.
     */
    static boolean javaDeclarationsMoved(ActionCache actionCache, String taskId, KotlincRequest request)
            throws IOException {
        if (request.javaSourceRoots().isEmpty()) return false;
        Optional<ActionCache.ActionRecord> prior = actionCache.lastFor(taskId);
        if (prior.isEmpty()) return true;
        return javaDeclarationsMoved(prior.get().inputs(), request);
    }

    /** {@link #javaDeclarationsMoved(ActionCache, String, KotlincRequest)} against a record's inputs. */
    static boolean javaDeclarationsMoved(Map<String, String> priorInputs, KotlincRequest request) throws IOException {
        List<Path> javaSources = KotlincInputs.javaSources(request);
        Map<Path, String> digests = JavaSourceApi.digests(javaSources);
        Map<String, String> now = new HashMap<>();
        for (Path src : javaSources) {
            now.put("java-api:" + src, Objects.requireNonNull(digests.get(src), "digest"));
        }
        Map<String, String> recorded = new HashMap<>();
        for (Map.Entry<String, String> e : priorInputs.entrySet()) {
            if (e.getKey().startsWith("java-api:")) recorded.put(e.getKey(), e.getValue());
        }
        return !now.equals(recorded);
    }

    /** Any {@code .class} anywhere under {@code dir}? */
    private static boolean hasClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(f -> f.toString().endsWith(".class"));
        }
    }

    /**
     * Full-recompile lane: the output and stub dirs hold exactly one compile's results. Stale
     * stubs are the worse half — javac resolves deleted Groovy types from {@code --source-path}
     * stubs and ships stub-bodied phantom classes instead of erroring.
     */
    private static void wipe(GroovycRequest request) throws IOException {
        PathUtil.deleteRecursively(request.outputDir());
        Files.createDirectories(request.outputDir());
        if (request.stubsOut() != null) {
            PathUtil.deleteRecursively(request.stubsOut());
            Files.createDirectories(request.stubsOut());
        }
    }
}
