// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycDriver;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.GroovycResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * Action-cache front for Groovy compile: a whole-input action-key hit/miss around the worker fork
 * (the worker is always a full compile — Groovy has no incremental state to guard).
 */
public final class GroovyCompile {

    private GroovyCompile() {}

    /** Outcome of a {@link #run}. {@code output} carries the worker's diagnostics. */
    public record Result(boolean success, String outcome, String actionKey, String output) {
        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    /**
     * @param useCache when false ({@code --force} / {@code jk verify}), skip the lookup AND the
     *     final store — a bypassing run neither reads nor writes the action cache; the
     *     result is still recorded.
     */
    public static Result run(
            String taskId, GroovycRequest request, String jkVersion, boolean useCache, Cas cas, ActionCache actionCache)
            throws IOException {
        String key = ActionKey.forGroovyc(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                actionCache.restore(hit.get(), request.outputDir());
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, "");
            }
        }

        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(request.outputDir());
        CasPrewriter prewriter = CasPrewriter.watching(cas, request.outputDir());
        GroovycResult gr;
        Map<String, String> outputs;
        try {
            gr = new GroovycDriver().compile(request);
        } finally {
            outputs = prewriter.finish();
        }
        if (!gr.success()) {
            return new Result(false, "errors", key, gr.output());
        }
        // Never cache a zero-output "success" for a non-empty source set: caching that
        // poisons every later run under the same key.
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(true, "compiled-no-outputs", key, gr.output());
        }
        // Bypassing runs neither read NOR write: --force must not churn entries under keys
        // the normal path already owns, and jk verify's scratch build (path-salted keys that
        // can never recur) must not leave orphan records behind.
        if (useCache) actionCache.storeWithOutputs(taskId, key, Map.of(), outputs);
        return new Result(true, "compiled", key, gr.output());
    }
}
