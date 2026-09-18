// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Explain why a dependency is on the graph ({@code jk why}). {@code features} and {@code
 * noDefaultFeatures} are the feature selection the declared roots are read under.
 */
public record WhyRequest(
        @Nullable String dir, @Nullable String query, List<String> features, boolean noDefaultFeatures) {

    public WhyRequest {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.WHY_REQUEST)
                .string("dir", dir)
                .string("query", query)
                .array("features", features)
                .bool("noDefaultFeatures", noDefaultFeatures)
                .finish();
    }

    public static WhyRequest decode(String json) {
        return new WhyRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "query"),
                Jsonl.strArray(json, "features"),
                Jsonl.bool(json, "noDefaultFeatures", false));
    }
}
