// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestFailureInfo;
import java.util.List;

/** A warning attached to a task: the plain diagnostic shape (see {@link EngineProtocol#WARN}). */
public record WarnEvent(
        String dir,
        String task,
        String code,
        String message,
        String test,
        String module,
        String engine,
        String testClass,
        String method,
        String exceptionClass,
        String file,
        int line,
        int snippetStart,
        int worker,
        List<String> snippet,
        String stack) {
    public String encode() {
        return DiagnosticFields.encode(
                EngineProtocol.WARN,
                dir,
                task,
                code,
                message,
                test,
                module,
                engine,
                testClass,
                method,
                exceptionClass,
                file,
                line,
                snippetStart,
                worker,
                snippet,
                stack);
    }

    /** The record for one failure's fields, with the caller's own dir/task/code/message/test. */
    static WarnEvent of(String dir, String task, String code, String message, String test, TestFailureInfo f) {
        return new WarnEvent(
                dir,
                task,
                code,
                message,
                test,
                f.module(),
                f.engine(),
                f.className(),
                f.method(),
                f.exceptionClass(),
                f.file(),
                f.line(),
                f.snippetStart(),
                f.worker(),
                f.snippet(),
                f.stack());
    }

    public static WarnEvent decode(String json) {
        return new WarnEvent(
                DiagnosticFields.str(json, "dir"),
                DiagnosticFields.str(json, "task"),
                DiagnosticFields.str(json, "code"),
                DiagnosticFields.str(json, "message"),
                DiagnosticFields.str(json, "test"),
                DiagnosticFields.str(json, "module"),
                DiagnosticFields.str(json, "engine"),
                DiagnosticFields.str(json, EngineProtocol.TEST_CLASS_FIELD),
                DiagnosticFields.str(json, "method"),
                DiagnosticFields.str(json, "exceptionClass"),
                DiagnosticFields.str(json, "file"),
                Jsonl.intValue(json, "line", 0),
                Jsonl.intValue(json, "snippetStart", 0),
                Jsonl.intValue(json, "worker", 0),
                Jsonl.strArray(json, "snippet"),
                DiagnosticFields.str(json, "stack"));
    }
}
