// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * A cache maintenance operation (see {@link EngineProtocol#CACHE_PRUNE_REQUEST}). {@code op} is
 * {@code prune} / {@code purge} / {@code sweep} / {@code clear}; {@code dir} is the project root a
 * {@code clear} scopes to and rides only then; {@code includeJkTmp} asks a prune to also sweep
 * {@code state/tmp} (only when the default cache dir is in use); {@code dryRun} reports what would
 * be removed without deleting.
 */
public record CachePruneRequest(
        @Nullable String op,
        @Nullable String cache,
        @Nullable String dir,
        boolean dryRun,
        boolean includeJkTmp) {

    public String encode() {
        return RequestJson.request(EngineProtocol.CACHE_PRUNE_REQUEST)
                .string("op", op)
                .string("cache", cache)
                .optionalNonBlankString("dir", dir)
                .bool("dryRun", dryRun)
                .bool("includeJkTmp", includeJkTmp)
                .finish();
    }

    public static CachePruneRequest decode(String json) {
        return new CachePruneRequest(
                Jsonl.str(json, "op"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "dir"),
                Jsonl.bool(json, "dryRun", false),
                Jsonl.bool(json, "includeJkTmp", false));
    }
}
