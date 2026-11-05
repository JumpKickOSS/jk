// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * Predecessor → successor: in-flight job count after yielding its listeners, and the runs that
 * finished since the last report (see {@link EngineProtocol#DRAIN_STATUS}). Each run is the text of
 * one JSON object the successor's session log folds; the wire carries it as a string and reads
 * nothing inside it. {@code runs} is omitted when empty.
 */
public record DrainStatusFrame(long pid, int plans, String version, List<String> runs) {

    public DrainStatusFrame {
        runs = List.copyOf(runs);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DRAIN_STATUS)
                .number("pid", pid)
                .number("plans", plans)
                .string("version", version, "")
                .optionalArray("runs", runs.isEmpty() ? null : runs)
                .finish();
    }

    public static DrainStatusFrame decode(String json) {
        return new DrainStatusFrame(
                Jsonl.longValue(json, "pid", 0),
                Jsonl.intValue(json, "plans", 0),
                Jsonl.requiredStr(json, "version"),
                Jsonl.strArray(json, "runs"));
    }
}
