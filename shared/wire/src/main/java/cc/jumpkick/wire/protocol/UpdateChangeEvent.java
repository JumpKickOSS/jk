// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One package {@code jk update}'s relock changed in the lockfile under {@code dir}: {@code from} is
 * null for an added package, {@code to} for a removed one; {@code members} name the workspace
 * members a member-override row serves (see {@link EngineProtocol#UPDATE_CHANGE}).
 */
public record UpdateChangeEvent(
        String dir,
        String coordinate,
        @Nullable String from,
        @Nullable String to,
        List<String> members) {

    public String encode() {
        return RequestJson.request(EngineProtocol.UPDATE_CHANGE)
                .string("dir", dir)
                .string("coordinate", coordinate)
                .string("from", from)
                .string("to", to)
                .array("members", members)
                .finish();
    }

    public static UpdateChangeEvent decode(String json) {
        return new UpdateChangeEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coordinate"),
                Jsonl.str(json, "from"),
                Jsonl.str(json, "to"),
                Jsonl.strArray(json, "members"));
    }
}
