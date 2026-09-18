// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import org.jspecify.annotations.Nullable;

/** An error against a task; {@code test} and {@code exceptionClass} ride only when non-empty. */
public record ErrorLine(
        long ts,
        String task,
        String code,
        String message,
        @Nullable String test,
        @Nullable String exceptionClass) {
    public String encode() {
        return JsonlEnvelope.open(ts, "error")
                .string("task", task)
                .string("code", code)
                .string("message", message)
                .optionalNonEmptyString("test", test)
                .optionalNonEmptyString("exceptionClass", exceptionClass)
                .finish();
    }

    public static ErrorLine decode(String json) {
        return new ErrorLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "code"),
                Jsonl.requiredStr(json, "message"),
                Jsonl.str(json, "test"),
                Jsonl.str(json, "exceptionClass"));
    }
}
