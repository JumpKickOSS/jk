// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk template lint}: lint each template directory in {@code paths}, or every template under
 * it, each resolved against {@code dir}. Empty {@code paths} means {@code dir} itself.
 */
public record TemplateLintRequest(@Nullable String dir, List<String> paths) {

    public TemplateLintRequest {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TEMPLATE_LINT_REQUEST)
                .string("dir", dir)
                .array("paths", paths)
                .finish();
    }

    public static TemplateLintRequest decode(String json) {
        return new TemplateLintRequest(Jsonl.str(json, "dir"), Jsonl.strArray(json, "paths"));
    }
}
