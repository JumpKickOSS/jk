// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Command session finished: exit code, wall duration, and the optional wedge and module list. */
public record SessionFinishLine(
        long ts, int exit, long durationMs, @Nullable String wedge, List<String> modules) {
    public String encode() {
        return JsonlEnvelope.open(ts, "session-finish")
                .number("exit", exit)
                .number("duration_ms", durationMs)
                .optionalNonBlankString("wedge", wedge)
                .optionalArray("modules", modules)
                .finish();
    }

    public static SessionFinishLine decode(String json) {
        return new SessionFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.intValue(json, "exit", 0),
                Jsonl.longValue(json, "duration_ms", 0),
                Jsonl.str(json, "wedge"),
                Jsonl.strArray(json, "modules"));
    }
}
