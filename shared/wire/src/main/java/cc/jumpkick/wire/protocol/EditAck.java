// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Whether a {@code jk.toml} edit changed the file, the error when it did not, a detail line when
 * there is one, and a warning when the edit was written but something after it (its relock) failed
 * (see {@link EngineProtocol#EDIT_ACK}).
 */
public record EditAck(
        boolean changed,
        @Nullable String error,
        @Nullable String detail,
        @Nullable String warning) {
    public String encode() {
        return RequestJson.request(EngineProtocol.EDIT_ACK)
                .bool("changed", changed)
                .string("error", error)
                .optionalNonBlankString("detail", detail)
                .optionalNonBlankString("warning", warning)
                .finish();
    }

    public static EditAck decode(String json) {
        return new EditAck(
                Jsonl.bool(json, "changed", false),
                Jsonl.str(json, "error"),
                Jsonl.str(json, "detail"),
                Jsonl.str(json, "warning"));
    }
}
