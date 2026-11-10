// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestFailureInfo;
import java.util.List;

/**
 * A warning attached to a task: the plain diagnostic shape (see {@link EngineProtocol#WARN}), plus
 * {@code "replayed":true} when a step that did not run said it again as its last run said it.
 */
public record WarnEvent(
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
        boolean replayed) {

    /** The field a replayed warning carries; absent on every other. */
    static final String REPLAYED = "replayed";

    public String encode() {
        String encoded = DiagnosticFields.encode(
                EngineProtocol.WARN,
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
                textId,
                sameText);
        return replayed ? markReplayed(encoded) : encoded;
    }

    /** {@code line}, an encoded warning, marked replayed. */
    static String markReplayed(String line) {
        return Jsonl.append(line, JsonFields.fields().bool(REPLAYED, true).body());
    }

    /** The record for one failure's fields, with the caller's own dir/task/code/message/test. */
    static WarnEvent of(
            String dir,
            String task,
            String code,
            String message,
            String test,
            TestFailureInfo f,
            FailureTextRefs.Ref ref) {
        return new WarnEvent(
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
                ref.sameText(),
                false);
    }

    public static WarnEvent decode(String json) {
        return new WarnEvent(
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
                Jsonl.bool(json, REPLAYED, false));
    }
}
