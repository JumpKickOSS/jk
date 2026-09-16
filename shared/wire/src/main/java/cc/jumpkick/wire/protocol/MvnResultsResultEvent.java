// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for an mvn-results request: {@code results} is the written {@code jk-results.md}
 * ({@code null} when the run left nothing to report), {@code error} why journaling failed (see
 * {@link EngineProtocol#MVN_RESULTS_RESULT}).
 */
public record MvnResultsResultEvent(
        @Nullable String results, @Nullable String error) {
    public String encode() {
        return RequestJson.request(EngineProtocol.MVN_RESULTS_RESULT)
                .string("results", results)
                .string("error", error)
                .finish();
    }

    public static MvnResultsResultEvent decode(String json) {
        return new MvnResultsResultEvent(Jsonl.str(json, "results"), Jsonl.str(json, "error"));
    }
}
