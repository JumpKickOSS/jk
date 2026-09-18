// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** An enriched test-failure error: module, engine, class, method, exception, source location and one top-level stack, each riding only when it says something. */
public record TestFailureErrorLine(
        long ts,
        String task,
        String code,
        String message,
        String module,
        String engine,
        String className,
        String method,
        String exceptionClass,
        int worker,
        String file,
        int line,
        int snippetStart,
        List<String> snippet,
        String stack) {
    public String encode() {
        return JsonlEnvelope.open(ts, "error")
                .string("task", task)
                .string("code", code)
                .string("message", message)
                .optionalNonEmptyString("module", module)
                .optionalNonEmptyString("engine", engine)
                .optionalNonEmptyString("class", className)
                .optionalNonEmptyString("method", method)
                .optionalNonEmptyString("exceptionClass", exceptionClass)
                .optionalNumber("worker", worker, 0)
                .optionalNonEmptyString("file", file)
                .optionalNumber("line", line, 0)
                .optionalNumber("snippetStart", snippetStart, 0)
                .optionalArray("snippet", snippet)
                .optionalNonEmptyString("stack", stack)
                .finish();
    }

    public static TestFailureErrorLine decode(String json) {
        return new TestFailureErrorLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "code"),
                Jsonl.requiredStr(json, "message"),
                orEmpty(Jsonl.str(json, "module")),
                orEmpty(Jsonl.str(json, "engine")),
                orEmpty(Jsonl.str(json, "class")),
                orEmpty(Jsonl.str(json, "method")),
                orEmpty(Jsonl.str(json, "exceptionClass")),
                Jsonl.intValue(json, "worker", 0),
                orEmpty(Jsonl.str(json, "file")),
                Jsonl.intValue(json, "line", 0),
                Jsonl.intValue(json, "snippetStart", 0),
                Jsonl.strArray(json, "snippet"),
                orEmpty(Jsonl.str(json, "stack")));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
