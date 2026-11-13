// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * A step's wait for a worker lease (see {@link EngineProtocol#TASK_WAIT}): {@code reason} is {@code
 * slot} or {@code memory} while none of its forks runs and the next one waits, {@code ""} once a
 * fork runs again.
 */
public record TaskWaitEvent(String dir, String task, String reason) {
    public String encode() {
        return RequestJson.event(EngineProtocol.TASK_WAIT)
                .string("dir", dir)
                .string("task", task)
                .string("reason", reason)
                .finish();
    }

    public static TaskWaitEvent decode(String json) {
        return new TaskWaitEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                Objects.requireNonNullElse(Jsonl.str(json, "reason"), ""));
    }
}
