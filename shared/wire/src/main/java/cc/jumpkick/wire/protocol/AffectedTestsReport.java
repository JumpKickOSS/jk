// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Read-only ranked test list for {@code jk test --affected} ({@link
 * EngineProtocol#AFFECTED_TESTS_REQUEST}). A refuse is discriminated by the {@code refused}
 * boolean — never by the message being non-empty, which turned an empty-message refuse into a
 * silent success. {@code rows} is the execute-set ranking; wire rows are
 * {@code |}-joined {@code score|class|reason}.
 */
public record AffectedTestsReport(
        boolean refused,
        @Nullable String error,
        @Nullable String refuseCode,
        int cap,
        int candidateCount,
        List<Row> rows) {

    public AffectedTestsReport {
        // A refuse always names its code — an anonymous refuse is undebuggable.
        refuseCode = !refused ? "" : (refuseCode == null || refuseCode.isBlank() ? "internal" : refuseCode);
        error = refused ? (error == null ? "" : error) : null;
    }

    public record Row(int score, String className, String reason) {}

    public static AffectedTestsReport error(String code, @Nullable String message) {
        return new AffectedTestsReport(true, message, code, 20, 0, List.of());
    }

    public static AffectedTestsReport of(int cap, int candidateCount, List<Row> rows) {
        return new AffectedTestsReport(false, null, "", cap, candidateCount, List.copyOf(rows));
    }

    public String encode() {
        List<String> encoded = new ArrayList<>(rows.size());
        for (Row r : rows) {
            encoded.add(r.score() + "|" + r.className() + "|" + r.reason());
        }
        return RequestJson.request(EngineProtocol.AFFECTED_TESTS_ACK)
                .bool("refused", refused)
                .string("error", error)
                .string("refuseCode", refuseCode)
                .number("cap", cap)
                .number("candidateCount", candidateCount)
                .array("rows", encoded)
                .finish();
    }

    public Map<String, Object> toStructured() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cap", cap);
        m.put("candidateCount", candidateCount);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("class", r.className());
            o.put("score", r.score());
            o.put("reason", r.reason());
            out.add(o);
        }
        m.put("ranked", out);
        if (refused) {
            m.put("error", error);
            m.put("refuse", Map.of("code", refuseCode, "message", error));
        }
        return m;
    }

    public static AffectedTestsReport decode(String line) {
        boolean refused = Jsonl.bool(line, "refused", false);
        String error = Jsonl.str(line, "error");
        String code = Jsonl.str(line, "refuseCode");
        int cap = Jsonl.intValue(line, "cap", 20);
        int candidates = Jsonl.intValue(line, "candidateCount", 0);
        List<Row> rows = new ArrayList<>();
        for (String enc : Jsonl.strArray(line, "rows")) {
            String[] f = enc.split("\\|", -1);
            int score = 0;
            try {
                score = Integer.parseInt(f.length > 0 ? f[0] : "0");
            } catch (NumberFormatException ignored) {
                // keep 0
            }
            rows.add(new Row(score, f.length > 1 ? f[1] : "", f.length > 2 ? f[2] : ""));
        }
        return new AffectedTestsReport(refused, error, code, cap, candidates, rows);
    }
}
