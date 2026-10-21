// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import java.util.ArrayList;
import java.util.List;

/**
 * A test run's slowest classes and methods, longest first: {@code classes} rows carry {@code
 * module}, {@code testClass}, {@code tier} and {@code millis}; {@code methods} rows add {@code
 * method}.
 */
public record SlowestTestsLine(long ts, List<SlowestTests.Row> classes, List<SlowestTests.Row> methods) {

    public SlowestTestsLine {
        classes = List.copyOf(classes);
        methods = List.copyOf(methods);
    }

    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.SLOWEST_TESTS)
                .token("classes", rows(classes))
                .token("methods", rows(methods))
                .finish();
    }

    public static SlowestTestsLine decode(String json) {
        return new SlowestTestsLine(Jsonl.longValue(json, "ts", 0), rows(json, "classes"), rows(json, "methods"));
    }

    private static String rows(List<SlowestTests.Row> rows) {
        StringBuilder array = new StringBuilder("[");
        for (SlowestTests.Row r : rows) {
            if (array.length() > 1) array.append(',');
            JsonFields row = JsonFields.object()
                    .string("module", r.module())
                    .string(EngineProtocol.TEST_CLASS_FIELD, r.className());
            if (!r.method().isEmpty()) row.string("method", r.method());
            array.append(
                    row.string("tier", r.tier()).number("millis", r.millis()).finish());
        }
        return array.append(']').toString();
    }

    private static List<SlowestTests.Row> rows(String json, String key) {
        List<SlowestTests.Row> out = new ArrayList<>();
        for (String r : Jsonl.objectArray(json, key)) {
            String method = Jsonl.str(r, "method");
            out.add(new SlowestTests.Row(
                    Jsonl.requiredStr(r, "module"),
                    Jsonl.requiredStr(r, EngineProtocol.TEST_CLASS_FIELD),
                    method == null ? "" : method,
                    Jsonl.requiredStr(r, "tier"),
                    Jsonl.longValue(r, "millis", 0)));
        }
        return out;
    }
}
