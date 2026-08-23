// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KotlincDriver;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.KotlincResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Action-cache front for Kotlin compile. The worker owns incremental recompile; this only does a
 * whole-input action-key hit/miss around the fork (behind the cheap {@code.kstamp} check).
 */
public final class KotlinCompile {

    private KotlinCompile() {}

    /** Outcome of a {@link #run}. {@code diagnostics} are the worker's, one entry each. */
    public record Result(
            boolean success,
            String outcome,
            String actionKey,
            List<cc.jumpkick.compile.CompileResult.Diagnostic> diagnostics) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }

        /** Joined diagnostics for logs and exception messages. */
        public String output() {
            return diagnostics.stream()
                    .map(cc.jumpkick.compile.CompileResult.Diagnostic::describe)
                    .collect(Collectors.joining("\n"));
        }
    }

    /**
     * @param useCache when false ({@code --redo}/{@code --force}), skip restore/skip — still
     * <em>write</em> the action cache after a successful compile so the next explain/build can
     * CACHE_HIT.
     */
    public static Result run(
            String taskId, KotlincRequest request, String jkVersion, boolean useCache, Cas cas, ActionCache actionCache)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, true, cas, actionCache);
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
            ActionCache actionCache)
            throws IOException {
        String key = ActionKey.forKotlinc(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            // A failed restore (missing/corrupt blob) falls through to a real compile.
            if (hit.isPresent() && actionCache.restore(hit.get(), request.outputDir())) {
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of());
            }
        }

        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(request.outputDir());
        // Incremental state is only valid alongside the outputs it produced: if the output
        // dir is (now) empty of classes while IC state survives (a cleaned target/, a fresh
        // checkout with a warm cache), BTA would compile "only what changed" into the void
        // and report success with a near-empty dir. Start the IC state over instead.
        if (request.incremental() && Files.isDirectory(request.workingDir()) && !hasClasses(request.outputDir())) {
            cc.jumpkick.util.PathUtil.deleteRecursively(request.workingDir());
        }
        CasPrewriter prewriter = CasPrewriter.watching(cas, request.outputDir());
        KotlincResult kr;
        Map<String, String> outputs;
        try {
            kr = new KotlincDriver().compile(request);
        } finally {
            outputs = prewriter.finish();
        }
        if (!kr.success()) {
            return new Result(false, "errors", key, kr.diagnostics());
        }
        // Never cache a zero-output "success" for a non-empty source set: stale incremental
        // state can convince the compiler nothing changed while the output dir is empty, and
        // caching that poisons every later run under the same key.
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(true, "compiled-no-outputs", key, kr.diagnostics());
        }
        // Store on rebuild/force too: the work re-ran and must refresh the action pointer so
        // the next non-rebuild explain sees CACHE_HIT (same as JavaCompile). Only
        // ephemeral (verify-scratch) runs skip the write — their keys never recur.
        if (persist) actionCache.storeWithOutputs(taskId, key, Map.of(), outputs);
        return new Result(true, "compiled", key, kr.diagnostics());
    }

    /** Any {@code .class} anywhere under {@code dir}? */
    private static boolean hasClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(f -> f.toString().endsWith(".class"));
        }
    }
}
