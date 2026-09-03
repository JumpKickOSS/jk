// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Explain why a dependency is on the graph ({@code jk why}). */
public record WhyRequest(@Nullable String dir, @Nullable String query) {

    public String encode() {
        return RequestJson.request(EngineProtocol.WHY_REQUEST)
                .string("dir", dir)
                .string("query", query)
                .finish();
    }

    public static WhyRequest decode(String json) {
        return new WhyRequest(Jsonl.str(json, "dir"), Jsonl.str(json, "query"));
    }
}
