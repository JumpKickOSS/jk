// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Apply one structured edit ({@code op} with {@code args}) to {@code file}. */
public record EditRequest(@Nullable String file, @Nullable String op, List<String> args) {

    public EditRequest {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.EDIT_REQUEST)
                .string("file", file)
                .string("op", op)
                .array("args", args)
                .finish();
    }

    public static EditRequest decode(String json) {
        return new EditRequest(Jsonl.str(json, "file"), Jsonl.str(json, "op"), Jsonl.strArray(json, "args"));
    }
}
