// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only outdated-dependency report for {@code jk outdated} ({@link EngineProtocol#OUTDATED_REQUEST}).
 * Non-null {@code error} is printable and {@code rows} is empty; {@code workspace} true spans modules.
 * Wire rows are {@code |}-joined 8-tuples (coords/versions/scopes never contain {@code |}).
 */
public record OutdatedReport(String error, boolean workspace, List<Row> rows) {

    /** One direct declared dependency's version picture. */
    public record Row(
            String moduleLabel,
            String coordinate,
            String display,
            String scope,
            String current,
            String compatible,
            String latest,
            String tip) {}

    public static OutdatedReport error(String message) {
        return new OutdatedReport(message, false, List.of());
    }

    public static OutdatedReport of(boolean workspace, List<Row> rows) {
        return new OutdatedReport(null, workspace, List.copyOf(rows));
    }

    public String encode() {
        List<String> encoded = new ArrayList<>(rows.size());
        for (Row r : rows) {
            encoded.add(String.join(
                    "|",
                    r.moduleLabel(),
                    r.coordinate(),
                    r.display(),
                    r.scope(),
                    r.current(),
                    r.compatible(),
                    r.latest(),
                    r.tip()));
        }
        return "{\"type\":\"" + EngineProtocol.OUTDATED_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"workspace\":" + workspace
                + ",\"rows\":" + EngineProtocol.quoteArray(encoded)
                + "}";
    }

    /**
     * Structured form for map-shaped surfaces (MCP {@code structuredContent}) — the same rows
     * {@link #encode} pipe-joins for the wire.
     */
    public Map<String, Object> toStructured() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (error != null) {
            m.put("error", error);
            return m;
        }
        m.put("workspace", workspace);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("module", r.moduleLabel());
            o.put("coordinate", r.coordinate());
            o.put("current", r.current());
            o.put("compatible", r.compatible());
            o.put("latest", r.latest());
            if (r.tip() != null && !r.tip().isBlank()) o.put("tip", r.tip());
            out.add(o);
        }
        m.put("rows", out);
        return m;
    }

    public static OutdatedReport decode(String line) {
        String error = Jsonl.str(line, "error");
        boolean workspace = Jsonl.bool(line, "workspace", false);
        List<Row> rows = new ArrayList<>();
        for (String enc : Jsonl.strArray(line, "rows")) {
            String[] f = enc.split("\\|", -1);
            rows.add(new Row(at(f, 0), at(f, 1), at(f, 2), at(f, 3), at(f, 4), at(f, 5), at(f, 6), at(f, 7)));
        }
        return new OutdatedReport(error, workspace, rows);
    }

    private static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }
}
