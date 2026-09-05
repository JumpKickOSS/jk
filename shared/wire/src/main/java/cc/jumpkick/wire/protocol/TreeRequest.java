// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Render the dependency tree ({@code jk tree}). */
public record TreeRequest(@Nullable String dir, int maxDepth, boolean flatten, boolean stack, List<String> scopes) {

    public TreeRequest {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TREE_REQUEST)
                .string("dir", dir)
                .number("maxDepth", maxDepth)
                .bool("flatten", flatten)
                .bool("stack", stack)
                .array("scopes", scopes)
                .finish();
    }

    public static TreeRequest decode(String json) {
        return new TreeRequest(
                Jsonl.str(json, "dir"),
                Jsonl.intValue(json, "maxDepth", Integer.MAX_VALUE),
                Jsonl.bool(json, "flatten", false),
                Jsonl.bool(json, "stack", false),
                Jsonl.strArray(json, "scopes"));
    }
}
