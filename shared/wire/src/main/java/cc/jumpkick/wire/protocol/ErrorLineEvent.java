// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestFailureInfo;
import java.util.List;

/**
 * An error line, plain or enriched with the test-failure fields (see {@link EngineProtocol#ERROR_LINE}).
 * {@code conflict} is an unsatisfiable resolve's conflict, the text of one JSON object, {@code ""}
 * otherwise.
 */
public record ErrorLineEvent(
        String dir,
        String task,
        String code,
        String message,
        String test,
        String module,
        String suite,
        String engine,
        String testClass,
        String method,
        String exceptionClass,
        String file,
        int line,
        int snippetStart,
        int worker,
        List<String> snippet,
        String stack,
        int textId,
        int sameText,
        String conflict) {

    /** An error line with no conflict behind it. */
    public ErrorLineEvent(
            String dir,
            String task,
            String code,
            String message,
            String test,
            String module,
            String suite,
            String engine,
            String testClass,
            String method,
            String exceptionClass,
            String file,
            int line,
            int snippetStart,
            int worker,
            List<String> snippet,
            String stack,
            int textId,
            int sameText) {
        this(
                dir,
                task,
                code,
                message,
                test,
                module,
                suite,
                engine,
                testClass,
                method,
                exceptionClass,
                file,
                line,
                snippetStart,
                worker,
                snippet,
                stack,
                textId,
                sameText,
                "");
    }

    /** A step's error that is a resolve with no solution: its message and its {@code conflict}. */
    public static ErrorLineEvent conflict(String dir, String task, String code, String message, String conflict) {
        return new ErrorLineEvent(
                dir, task, code, message, "", "", "", "", "", "", "", "", 0, 0, 0, List.of(), "", 0, 0, conflict);
    }

    public String encode() {
        return DiagnosticFields.encode(
                EngineProtocol.ERROR_LINE,
                dir,
                task,
                code,
                message,
                test,
                module,
                suite,
                engine,
                testClass,
                method,
                exceptionClass,
                file,
                line,
                snippetStart,
                worker,
                snippet,
                stack,
                "",
                conflict,
                textId,
                sameText);
    }

    /** The record for one failure's fields, with the caller's own dir/task/code/message/test. */
    static ErrorLineEvent of(
            String dir,
            String task,
            String code,
            String message,
            String test,
            TestFailureInfo f,
            FailureTextRefs.Ref ref) {
        return new ErrorLineEvent(
                dir,
                task,
                code,
                message,
                test,
                f.module(),
                f.suite(),
                f.engine(),
                f.className(),
                f.method(),
                f.exceptionClass(),
                f.file(),
                f.line(),
                f.snippetStart(),
                f.worker(),
                f.snippet(),
                f.stack(),
                ref.textId(),
                ref.sameText());
    }

    public static ErrorLineEvent decode(String json) {
        return new ErrorLineEvent(
                DiagnosticFields.str(json, "dir"),
                DiagnosticFields.str(json, "task"),
                DiagnosticFields.str(json, "code"),
                DiagnosticFields.str(json, "message"),
                DiagnosticFields.str(json, "test"),
                DiagnosticFields.str(json, "module"),
                DiagnosticFields.str(json, "suite"),
                DiagnosticFields.str(json, "engine"),
                DiagnosticFields.str(json, EngineProtocol.TEST_CLASS_FIELD),
                DiagnosticFields.str(json, "method"),
                DiagnosticFields.str(json, "exceptionClass"),
                DiagnosticFields.str(json, "file"),
                Jsonl.intValue(json, "line", 0),
                Jsonl.intValue(json, "snippetStart", 0),
                Jsonl.intValue(json, "worker", 0),
                Jsonl.strArray(json, "snippet"),
                DiagnosticFields.str(json, "stack"),
                Jsonl.intValue(json, "textId", 0),
                Jsonl.intValue(json, "sameText", 0),
                DiagnosticFields.str(json, "conflict"));
    }
}
