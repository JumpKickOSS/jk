// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Read-only outdated-dependency report for {@code jk outdated} ({@link EngineProtocol#OUTDATED_REQUEST}).
 * Non-null {@code error} is printable and {@code rows} is empty; {@code workspace} true spans modules.
 * Wire rows are {@code |}-joined 8-tuples (coords/versions/scopes never contain {@code |}).
 */
public record OutdatedReport(@Nullable String error, boolean workspace, List<Row> rows) {

    /** One direct declared dependency's version picture. */
    public record Row(
            String moduleLabel,
            String coordinate,
            String display,
            String scope,
            @Nullable String current,
            @Nullable String compatible,
            String latest,
            @Nullable String tip) {

        /**
         * True when an update would change something: Compatible or Latest is strictly ahead of
         * Current, or Current is not a version (unlocked, unknown) and so cannot be called current.
         */
        public boolean canMove() {
            if (versionOf(current) == null) return true;
            return compatibleAhead() || latestAhead();
        }

        /** Compatible is a strictly higher version than Current. */
        public boolean compatibleAhead() {
            return ahead(compatible, current);
        }

        /** Latest is a strictly higher version than Current. */
        public boolean latestAhead() {
            return ahead(latest, current);
        }
    }

    /** True when {@code a} is a strictly higher version than {@code b} and both are versions. */
    public static boolean ahead(@Nullable String a, @Nullable String b) {
        String na = versionOf(a);
        String nb = versionOf(b);
        return na != null && nb != null && Versions.compare(na, nb) > 0;
    }

    /** A cell as a comparable Maven version, or null when it is not one ("", "tip", tag text). */
    private static @Nullable String versionOf(@Nullable String v) {
        if (v == null || v.isEmpty() || v.equals("tip")) return null;
        String n = GitVersion.fromTag(v);
        return (n.isEmpty() || !Character.isDigit(n.charAt(0))) ? null : n;
    }

    /** The rows an update would change; see {@link Row#canMove}. */
    public List<Row> movable() {
        return rows.stream().filter(Row::canMove).toList();
    }

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
        return RequestJson.request(EngineProtocol.OUTDATED_ACK)
                .string("error", error)
                .bool("workspace", workspace)
                .array("rows", encoded)
                .finish();
    }

    /**
     * Structured form for map-shaped surfaces (MCP {@code structuredContent}) — the same rows
     * {@link #encode} pipe-joins for the wire. {@code checked} counts every row examined;
     * {@code rows} is the movable subset unless {@code all}.
     */
    public Map<String, Object> toStructured(boolean all) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (error != null) {
            m.put("error", error);
            return m;
        }
        m.put("workspace", workspace);
        m.put("checked", rows.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : all ? rows : movable()) {
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
