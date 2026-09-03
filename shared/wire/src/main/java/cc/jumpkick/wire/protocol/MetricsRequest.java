// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * A metrics stream request; a null or blank {@code dir} asks for every row and is omitted from the
 * line.
 */
public record MetricsRequest(@Nullable String dir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.METRICS_REQUEST)
                .optionalNonBlankString("dir", dir)
                .finish();
    }

    public static MetricsRequest decode(String json) {
        return new MetricsRequest(Jsonl.str(json, "dir"));
    }
}
