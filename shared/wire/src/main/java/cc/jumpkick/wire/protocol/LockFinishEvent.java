// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * Terminal for a lock/update request: the engine-computed exit code, pre-plan failure text, and
 * {@code jk update --git}'s refreshed count ({@code -1} for every other request) (see {@link EngineProtocol#LOCK_FINISH}).
 */
public record LockFinishEvent(boolean success, int exitCode, List<String> errors, int refreshed) {
    public String encode() {
        return RequestJson.request(EngineProtocol.LOCK_FINISH)
                .bool("success", success)
                .number("exitCode", exitCode)
                .array("errors", errors)
                .number("refreshed", refreshed)
                .finish();
    }

    public static LockFinishEvent decode(String json) {
        return new LockFinishEvent(
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.strArray(json, "errors"),
                Jsonl.intValue(json, "refreshed", -1));
    }
}
