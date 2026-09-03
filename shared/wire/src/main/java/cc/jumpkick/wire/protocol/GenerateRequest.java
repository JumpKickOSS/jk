// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Run a generator engine-side; {@code params} are the scaffold inputs as a flat map. */
public record GenerateRequest(
        @Nullable String dir,
        @Nullable String kind,
        @Nullable Map<String, String> params) {

    public GenerateRequest {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GENERATE_REQUEST)
                .string("dir", dir)
                .string("kind", kind)
                .map("params", params)
                .finish();
    }

    public static GenerateRequest decode(String json) {
        return new GenerateRequest(Jsonl.str(json, "dir"), Jsonl.str(json, "kind"), Jsonl.strMap(json, "params"));
    }
}
