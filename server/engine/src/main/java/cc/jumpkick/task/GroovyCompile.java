// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.WorkerCompileDriver;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Action-cache front for Groovy compile: a whole-input action-key hit/miss around the worker fork
 * (the worker is always a full compile — Groovy has no incremental state to guard).
 */
public final class GroovyCompile {

    private GroovyCompile() {}

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
     * write the action cache after a successful compile so the next explain/build can CACHE_HIT.
     */
    public static Result run(
            String taskId, GroovycRequest request, String jkVersion, boolean useCache, Cas cas, ActionCache actionCache)
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
            GroovycRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache)
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
                    return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of());
                }
            }
        }
        wipe(request);

        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(request.outputDir());
        CasPrewriter prewriter = CasPrewriter.watching(cas, request.outputDir());
        CompileResult gr;
        Map<String, String> outputs;
        try {
            gr = WorkerCompileDriver.compile(request);
        } finally {
            outputs = prewriter.finish();
        }
        if (!gr.success()) {
            return new Result(false, "errors", key, gr.diagnostics());
        }
        // Never cache a zero-output "success" for a non-empty source set: caching that
        // poisons every later run under the same key.
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(true, "compiled-no-outputs", key, gr.diagnostics());
        }
        // Store on rebuild/force too so the next explain sees CACHE_HIT; only ephemeral
        // (verify-scratch) runs skip the write — their keys never recur.
        if (persist) actionCache.storeWithOutputs(taskId, key, Map.of(), outputs);
        return new Result(true, "compiled", key, gr.diagnostics());
    }

    /**
     * Full-recompile lane: the output and stub dirs hold exactly one compile's results. Stale
     * stubs are the worse half — javac resolves deleted Groovy types from {@code --source-path}
     * stubs and ships stub-bodied phantom classes instead of erroring.
     */
    private static void wipe(GroovycRequest request) throws IOException {
        cc.jumpkick.host.PathUtil.deleteRecursively(request.outputDir());
        Files.createDirectories(request.outputDir());
        if (request.stubsOut() != null) {
            cc.jumpkick.host.PathUtil.deleteRecursively(request.stubsOut());
            Files.createDirectories(request.stubsOut());
        }
    }
}
