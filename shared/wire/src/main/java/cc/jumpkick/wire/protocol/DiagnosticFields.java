// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * The one field order and omit-when-empty rule the three diagnostic-shaped events share: the five
 * always-present fields — the tool's own {@code key} for the diagnostic between {@code code} and
 * {@code message} when the tool gave one — then the additive test fields only when they say
 * something, so a plain compiler diagnostic stays small. The stack is serialized once, top-level,
 * last. {@code textId} / {@code sameText} are the stream's {@link FailureTextRefs}.
 */
final class DiagnosticFields {
    private DiagnosticFields() {}

    static String encode(
            String type,
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
            String key,
            int textId,
            int sameText) {
        return RequestJson.request(type)
                .string("dir", dir)
                .string("task", task)
                .string("code", code)
                .optionalNonEmptyString("key", key)
                .string("message", message)
                .optionalNonEmptyString("test", test)
                .optionalNonEmptyString("module", module)
                .optionalNonEmptyString("suite", suite)
                .optionalNonEmptyString("engine", engine)
                .optionalNonEmptyString(EngineProtocol.TEST_CLASS_FIELD, testClass)
                .optionalNonEmptyString("method", method)
                .optionalNonEmptyString("exceptionClass", exceptionClass)
                .optionalNonEmptyString("file", file)
                .optionalNumber("line", line, 0)
                .optionalNumber("snippetStart", snippetStart, 0)
                .optionalNumber("worker", worker, 0)
                .optionalArray("snippet", snippet)
                .optionalNumber("textId", textId, 0)
                .optionalNumber("sameText", sameText, 0)
                .optionalNonEmptyString("stack", stack)
                .finish();
    }

    /** An absent string field reads as {@code ""}: the shape every producer passes for "nothing to say". */
    static String str(String json, String key) {
        String v = Jsonl.str(json, key);
        return v == null ? "" : v;
    }
}
