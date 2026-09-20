// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * One beat of a {@code jk outdated} read ({@link EngineProtocol#OUTDATED_PROGRESS}): {@code checked}
 * of {@code total} rows are done and {@code coordinate} is the one being fetched now. The first
 * beat carries {@code checked = 0} before any repository is read, so the client's bar is
 * determinate from the start.
 */
public record OutdatedProgressEvent(int checked, int total, String coordinate) {

    public String encode() {
        return RequestJson.request(EngineProtocol.OUTDATED_PROGRESS)
                .number("checked", checked)
                .number("total", total)
                .string("coordinate", coordinate)
                .finish();
    }

    public static OutdatedProgressEvent decode(String json) {
        String coordinate = Jsonl.str(json, "coordinate");
        return new OutdatedProgressEvent(
                Jsonl.intValue(json, "checked", 0),
                Jsonl.intValue(json, "total", 0),
                coordinate == null ? "" : coordinate);
    }
}
