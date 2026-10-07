// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestClassResult;

/** A test class finished under a task (see {@link EngineProtocol#CLASS_FINISH}). */
public record ClassFinishEvent(String dir, String task, TestClassResult result) {
    public String encode() {
        return RequestJson.event(EngineProtocol.CLASS_FINISH)
                .string("dir", dir)
                .string("task", task)
                .string("module", result.module())
                .string(EngineProtocol.TEST_CLASS_FIELD, result.className())
                .number("tests", result.tests())
                .number("failed", result.failed())
                .number("skipped", result.skipped())
                .number("millis", result.durationMs())
                .finish();
    }

    public static ClassFinishEvent decode(String json) {
        return new ClassFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                new TestClassResult(
                        Jsonl.requiredStr(json, "module"),
                        Jsonl.requiredStr(json, EngineProtocol.TEST_CLASS_FIELD),
                        Jsonl.intValue(json, "tests", 0),
                        Jsonl.intValue(json, "failed", 0),
                        Jsonl.intValue(json, "skipped", 0),
                        Jsonl.longValue(json, "millis", 0)));
    }
}
