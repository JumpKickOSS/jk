// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Module dependency DAG export ({@code jk explain --graph dot|mermaid}); {@code modules} and {@code
 * affectedSince} filter the workspace graph the same way build selectors do, and ride only when
 * set.
 */
public record ModuleGraphRequest(
        @Nullable String dir,
        @Nullable String format,
        @Nullable String modules,
        @Nullable String affectedSince) {

    public String encode() {
        return RequestJson.request(EngineProtocol.MODULE_GRAPH_REQUEST)
                .string("dir", dir)
                .string("format", format)
                .optionalNonBlankString("modules", modules)
                .optionalNonBlankString("affectedSince", affectedSince)
                .finish();
    }

    public static ModuleGraphRequest decode(String json) {
        return new ModuleGraphRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "format"),
                Jsonl.str(json, "modules"),
                Jsonl.str(json, "affectedSince"));
    }
}
