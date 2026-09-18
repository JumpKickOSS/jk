// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Render the dependency tree ({@code jk tree}). {@code features} and {@code noDefaultFeatures} are
 * the feature selection the tree is read under, as {@code jk lock} takes the same flags.
 */
public record TreeRequest(
        @Nullable String dir,
        int maxDepth,
        boolean flatten,
        boolean stack,
        List<String> scopes,
        List<String> features,
        boolean noDefaultFeatures) {

    public TreeRequest {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        features = features == null ? List.of() : List.copyOf(features);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TREE_REQUEST)
                .string("dir", dir)
                .number("maxDepth", maxDepth)
                .bool("flatten", flatten)
                .bool("stack", stack)
                .array("scopes", scopes)
                .array("features", features)
                .bool("noDefaultFeatures", noDefaultFeatures)
                .finish();
    }

    public static TreeRequest decode(String json) {
        return new TreeRequest(
                Jsonl.str(json, "dir"),
                Jsonl.intValue(json, "maxDepth", Integer.MAX_VALUE),
                Jsonl.bool(json, "flatten", false),
                Jsonl.bool(json, "stack", false),
                Jsonl.strArray(json, "scopes"),
                Jsonl.strArray(json, "features"),
                Jsonl.bool(json, "noDefaultFeatures", false));
    }
}
