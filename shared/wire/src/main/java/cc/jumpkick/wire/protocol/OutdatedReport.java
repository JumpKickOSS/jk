// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    /**
     * One coordinate across every module that declares it. {@code current} and {@code compatible}
     * list each distinct value with its module count, lowest version first, so two modules on
     * different pins read as a spread rather than as two rows.
     */
    public record Rollup(
            String coordinate,
            String display,
            List<String> modules,
            List<Spread> current,
            List<Spread> compatible,
            String latest,
            @Nullable String tip,
            List<String> scopes,
            boolean canMove) {

        /** The catalog short name when known, else the coordinate. */
        public String label() {
            return display.isEmpty() ? coordinate : display;
        }

        /** True when the modules behind this coordinate sit on more than one Current. */
        public boolean currentDiffers() {
            return current.size() > 1;
        }
    }

    /** One distinct version and how many modules sit on it; {@code version} is empty when unlocked. */
    public record Spread(String version, int modules) {}

    /**
     * {@code spread} as one cell: the version alone when every module agrees, else
     * {@code 1.1.1 ×11 · 1.2.0 ×1}. Empty when there is nothing to show.
     */
    public static String spreadText(List<Spread> spread) {
        if (spread.size() == 1) return spread.getFirst().version();
        List<String> parts = new ArrayList<>(spread.size());
        for (Spread s : spread) {
            parts.add((s.version().isEmpty() ? "—" : s.version()) + " ×" + s.modules());
        }
        return String.join(" · ", parts);
    }

    /** Every coordinate once, sorted by {@link Rollup#label}; see {@link Rollup}. */
    public List<Rollup> rollup() {
        LinkedHashMap<String, List<Row>> byCoordinate = new LinkedHashMap<>();
        for (Row r : rows)
            byCoordinate.computeIfAbsent(r.coordinate(), k -> new ArrayList<>()).add(r);
        List<Rollup> out = new ArrayList<>(byCoordinate.size());
        for (Map.Entry<String, List<Row>> e : byCoordinate.entrySet()) out.add(rollup(e.getKey(), e.getValue()));
        out.sort(Comparator.comparing(Rollup::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The coordinates an update would change somewhere. */
    public List<Rollup> movableRollup() {
        return rollup().stream().filter(Rollup::canMove).toList();
    }

    private static Rollup rollup(String coordinate, List<Row> rows) {
        String display = "";
        String latest = "";
        String tip = null;
        Set<String> modules = new LinkedHashSet<>();
        Set<String> scopes = new LinkedHashSet<>();
        boolean canMove = false;
        for (Row r : rows) {
            if (display.isEmpty()) display = r.display();
            if (latest.isEmpty() || ahead(r.latest(), latest)) latest = r.latest();
            if (tip == null && r.tip() != null && !r.tip().isBlank()) tip = r.tip();
            modules.add(r.moduleLabel());
            if (!r.scope().isEmpty()) scopes.add(r.scope());
            canMove |= r.canMove();
        }
        return new Rollup(
                coordinate,
                display,
                List.copyOf(modules),
                spread(rows, Row::current),
                spread(rows, Row::compatible),
                latest,
                tip,
                List.copyOf(scopes),
                canMove);
    }

    private static List<Spread> spread(List<Row> rows, Function<Row, @Nullable String> cell) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            String v = cell.apply(r);
            counts.merge(v == null ? "" : v, 1, Integer::sum);
        }
        List<Spread> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) out.add(new Spread(e.getKey(), e.getValue()));
        out.sort((a, b) -> {
            String va = versionOf(a.version());
            String vb = versionOf(b.version());
            if (va == null || vb == null) return va == null ? (vb == null ? 0 : -1) : 1;
            return Versions.compare(va, vb);
        });
        return out;
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
