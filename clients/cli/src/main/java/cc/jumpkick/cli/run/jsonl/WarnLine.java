// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** A warning against a task. */
public record WarnLine(long ts, String task, String code, String message) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.WARN)
                .string("task", task)
                .string("code", code)
                .string("message", message)
                .finish();
    }

    public static WarnLine decode(String json) {
        return new WarnLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.str(json, "task"),
                Jsonl.str(json, "code"),
                Jsonl.str(json, "message"));
    }
}
