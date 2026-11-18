// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Relations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code callers file:<path>} (or {@code file:<path>:<line>}): the anchors a file defines or uses
 * ({@link Anchors}), each with the other files that name it, so one call says what an edit to the
 * file may leave behind. The places the file's last edit left under an old name ({@link
 * AnchorEdits}) come first; then each anchor in line order, the places that define or use it in a
 * structured position, then the ones matched less surely. A use a compiler bound is counted, never
 * listed: the build checks it.
 */
final class FileAnchors {

    private FileAnchors() {}

    /** The address prefix that asks for a file. */
    static final String PREFIX = "file:";

    /** Places named per anchor before the rest are counted. */
    static final int PLACES = 6;

    /** True when {@code address} asks for a file. */
    static boolean asks(String address) {
        return address.strip().startsWith(PREFIX);
    }

    /** The answer for {@code address} in the graph of the workspace at {@code root}, cut to {@code budget}. */
    static String answer(Path root, Path out, CodeGraph g, String address, int budget) {
        String path = address.strip().substring(PREFIX.length()).strip().replace('\\', '/');
        int line = 0;
        int colon = path.lastIndexOf(':');
        if (colon > 0 && path.substring(colon + 1).matches("\\d+")) {
            line = Integer.parseInt(path.substring(colon + 1));
            path = path.substring(0, colon);
        }
        String rel = relative(root, path);
        int wanted = line;
        List<String> lines = new ArrayList<>();
        List<AnchorEdits.Row> left = AnchorEdits.read(
                        out.resolve(AnchorEdits.DIR).resolve(AnchorEdits.FILE))
                .stream()
                .filter(r -> r.edited().equals(rel))
                .toList();
        if (!left.isEmpty()) {
            lines.add("Your last edit of it changed these names; these places still use the old one:");
            Map<String, List<AnchorEdits.Row>> byAnchor = new LinkedHashMap<>();
            for (AnchorEdits.Row r : left) {
                byAnchor.computeIfAbsent(r.kind() + ":" + r.old(), k -> new ArrayList<>())
                        .add(r);
            }
            byAnchor.forEach((anchor, rows) -> {
                List<String> at = new ArrayList<>();
                List<String> prose = new ArrayList<>();
                for (AnchorEdits.Row r : rows) {
                    String place = r.rel()
                            + (r.line() > 0 ? ":" + r.line() : "")
                            + (r.written().isEmpty() ? "" : " as " + r.written());
                    (r.must() ? at : prose).add(place);
                }
                AnchorEdits.Row first = rows.getFirst();
                lines.add("  " + anchor + (first.now().isEmpty() ? ", no longer defined" : ", now " + first.now())
                        + ": " + (at.isEmpty() ? "no structured place" : String.join(", ", at))
                        + (prose.isEmpty() ? "" : "; prose: " + String.join(", ", prose)));
            });
        }
        Map<String, List<GraphEdge>> own = AnchorEdits.byFile(g, Set.of(rel)).getOrDefault(rel, Map.of());
        List<Map.Entry<String, List<GraphEdge>>> anchors = new ArrayList<>();
        for (Map.Entry<String, List<GraphEdge>> e : own.entrySet()) {
            if (wanted == 0
                    || e.getValue().stream().anyMatch(edge -> sitesOf(edge).contains(wanted))) {
                anchors.add(e);
            }
        }
        anchors.sort((a, b) -> Integer.compare(first(a.getValue()), first(b.getValue())));
        if (anchors.isEmpty() && left.isEmpty()) {
            if (g.node(GraphIds.file(rel)) == null && own.isEmpty()) {
                return "No file " + rel + " in the graph.";
            }
            return "No anchor " + (line == 0 ? "in " + rel : "at " + rel + ":" + line)
                    + ": no route, SQL table or column, config key or environment variable is named there.";
        }
        if (!anchors.isEmpty()) {
            lines.add((line == 0 ? rel : rel + ":" + line) + ": " + anchors.size()
                    + (anchors.size() == 1 ? " anchor" : " anchors") + ", each with the other places that name it:");
        }
        for (Map.Entry<String, List<GraphEdge>> e : anchors) {
            String id = e.getKey();
            boolean defines = e.getValue().stream().anyMatch(x -> x.relation().equals(Relations.DEFINES));
            List<String> sure = new ArrayList<>();
            List<String> loose = new ArrayList<>();
            int bound = 0;
            for (Place p : others(root, g, id, rel)) {
                if (p.grade() == Confidence.EXTRACTED) bound++;
                else (p.grade().marked() ? loose : sure).add(p.text());
            }
            List<String> all = new ArrayList<>(sure);
            for (String l : loose) all.add(l + " (inferred)");
            String places = all.isEmpty()
                    ? "no other place names it"
                    : String.join(", ", all.subList(0, Math.min(PLACES, all.size())))
                            + (all.size() > PLACES
                                    ? ", +" + (all.size() - PLACES) + " more: callers(address=\"" + clean(g, id) + "\")"
                                    : "");
            lines.add("  L" + first(e.getValue()) + " " + clean(g, id) + (defines ? " (defined here)" : "") + ": "
                    + places + (bound == 0 ? "" : "; " + bound + " bound by the compiler"));
        }
        return Budget.lines(lines, budget, "Raise the budget, or ask for one line: file:" + rel + ":<line>");
    }

    /** One other place an anchor is named: as written, graded. */
    private record Place(String text, Confidence grade) {}

    /**
     * The places other than {@code rel} that define or use anchor {@code id}, and for a config key the
     * lines that set the environment variables binding it.
     */
    private static List<Place> others(Path root, CodeGraph g, String id, String rel) {
        List<Place> out = new ArrayList<>();
        for (GraphEdge e : g.incident(id)) {
            if (!e.target().equals(id)
                    || e.sourceFile().isEmpty()
                    || e.sourceFile().equals(rel)) continue;
            if (!e.relation().equals(Relations.DEFINES) && !e.relation().equals(Relations.USES)) continue;
            out.add(new Place(at(root, g, e), e.confidence()));
        }
        for (GraphEdge b : g.incident(id)) {
            if (!b.relation().equals(Relations.BINDS) || !b.target().equals(id)) continue;
            GraphNode env = g.node(b.source());
            for (GraphEdge e : g.incident(b.source())) {
                if (!e.target().equals(b.source())
                        || !e.relation().equals(Relations.DEFINES)
                        || e.sourceFile().equals(rel)) continue;
                out.add(new Place(
                        at(root, g, e) + " as " + (env == null ? b.source() : env.label()), Confidence.INFERRED));
            }
        }
        return out;
    }

    /**
     * {@code file:line} of {@code e}; a place compiled facts give no line for (a field's column) at
     * the field's declaration, else the first line of its file that carries the anchor's name.
     */
    private static String at(Path root, CodeGraph g, GraphEdge e) {
        int line = first(List.of(e));
        if (line < 1) {
            String kind = Anchors.kindOf(e.target());
            GraphNode n = g.node(e.target());
            if (kind != null && n != null) {
                try {
                    List<String> text = Files.readAllLines(root.resolve(e.sourceFile()), StandardCharsets.UTF_8);
                    line = AnchorEdits.declaredAt(e, text);
                    if (line < 1) line = AnchorEdits.carriedAt(kind, n.label(), "", 0, text);
                } catch (IOException | RuntimeException unreadable) {
                    line = 0;
                }
            }
        }
        return line > 0 ? e.sourceFile() + ":" + line : e.sourceFile();
    }

    private static List<Integer> sitesOf(GraphEdge e) {
        List<Integer> out = new ArrayList<>();
        for (String s : e.sites()) out.add(lineOf(s));
        return out;
    }

    private static int first(List<GraphEdge> edges) {
        int min = Integer.MAX_VALUE;
        for (GraphEdge e : edges) {
            int l = lineOf(e.sourceLocation());
            if (l > 0) min = Math.min(min, l);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int lineOf(String location) {
        try {
            return location.startsWith("L") ? Integer.parseInt(location.substring(1)) : 0;
        } catch (NumberFormatException notALine) {
            return 0;
        }
    }

    /** {@code column:owners.telephone}: an anchor as callers takes it. */
    private static String clean(CodeGraph g, String id) {
        String kind = Anchors.kindOf(id);
        GraphNode n = g.node(id);
        return kind == null || n == null ? id : kind + ":" + n.label();
    }

    /** {@code path} relative to {@code root}, as the graph names files: {@code /} separators, no {@code ./}. */
    static String relative(Path root, String path) {
        String p = path;
        Path asPath = Path.of(p);
        if (asPath.isAbsolute()) {
            Path base = root.toAbsolutePath().normalize();
            Path abs = asPath.normalize();
            if (abs.startsWith(base)) p = base.relativize(abs).toString().replace('\\', '/');
        }
        while (p.startsWith("./")) p = p.substring(2);
        return p;
    }
}
