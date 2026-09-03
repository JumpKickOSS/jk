// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Project summary. {@code modules} / {@code affectedSince} ({@code -m} / {@code --affected-since})
 * ride only when set; {@code counts} opts into the source/test walk ({@code jk status}), never the
 * default for identity-only callers on hot paths.
 */
public record ProjectInfoRequest(
        @Nullable String dir,
        @Nullable String modules,
        @Nullable String affectedSince,
        boolean affected,
        boolean counts) {

    public String encode() {
        return RequestJson.request(EngineProtocol.PROJECT_INFO_REQUEST)
                .string("dir", dir)
                .optionalNonBlankString("modules", modules)
                .optionalNonBlankString("affectedSince", affectedSince)
                .optionalTrue("affected", affected)
                .optionalTrue("counts", counts)
                .finish();
    }

    public static ProjectInfoRequest decode(String json) {
        return new ProjectInfoRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "modules"),
                Jsonl.str(json, "affectedSince"),
                Jsonl.bool(json, "affected", false),
                Jsonl.bool(json, "counts", false));
    }
}
