// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Apply one structured edit ({@code op} with {@code args}) to {@code file}. {@code offline} keeps any
 * lookup the edit makes (the newest version, whether a pinned one exists) off the network.
 */
public record EditRequest(@Nullable String file, @Nullable String op, List<String> args, boolean offline) {

    public EditRequest {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.EDIT_REQUEST)
                .string("file", file)
                .string("op", op)
                .array("args", args)
                .bool("offline", offline)
                .finish();
    }

    public static EditRequest decode(String json) {
        return new EditRequest(
                Jsonl.str(json, "file"),
                Jsonl.str(json, "op"),
                Jsonl.strArray(json, "args"),
                Jsonl.bool(json, "offline", false));
    }
}
