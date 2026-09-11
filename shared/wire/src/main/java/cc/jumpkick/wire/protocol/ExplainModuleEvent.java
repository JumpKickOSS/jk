// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * One module of a {@code jk explain} burst: its counts, what it produces, and the preflight's own
 * reason for scheduling it when it had one (see {@link EngineProtocol#EXPLAIN_MODULE}).
 */
public record ExplainModuleEvent(
        String dir,
        String coord,
        int sourceCount,
        int testCount,
        boolean producesJar,
        boolean producesImage,
        @Nullable String reason) {
    public String encode() {
        return RequestJson.request(EngineProtocol.EXPLAIN_MODULE)
                .string("dir", dir)
                .string("coord", coord)
                .number("sourceCount", sourceCount)
                .number("testCount", testCount)
                .bool("producesJar", producesJar)
                .bool("producesImage", producesImage)
                .string("reason", reason)
                .finish();
    }

    public static ExplainModuleEvent decode(String json) {
        return new ExplainModuleEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coord"),
                Jsonl.intValue(json, "sourceCount", 0),
                Jsonl.intValue(json, "testCount", 0),
                Jsonl.bool(json, "producesJar", false),
                Jsonl.bool(json, "producesImage", false),
                Jsonl.str(json, "reason"));
    }
}
