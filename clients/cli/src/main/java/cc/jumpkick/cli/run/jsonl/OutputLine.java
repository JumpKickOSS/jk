// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** One line of a task's output. */
public record OutputLine(long ts, String task, String line) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.OUTPUT)
                .string("task", task)
                .string("line", line)
                .finish();
    }

    public static OutputLine decode(String json) {
        return new OutputLine(Jsonl.longValue(json, "ts", 0), Jsonl.str(json, "task"), Jsonl.str(json, "line"));
    }
}
