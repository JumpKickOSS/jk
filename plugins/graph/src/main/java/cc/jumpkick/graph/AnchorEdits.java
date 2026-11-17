// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.host.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The anchors a hand edit left stale, by name: an anchor ({@link Anchors}) a changed file named
 * before the edit and no longer names, renamed (the file now names another of the same kind) or,
 * when the file defined it and nothing else does now, removed, while another file still carries the
 * old name. "Before" is the graph of the last refresh, which any graph question or successful build
 * makes; the rows are kept across refreshes, each dropped once its file no longer carries the old
 * name or the edit is undone, so a build that follows a graph question still names them.
 *
 * <p>A place is listed as one that must change when the old name sits in a structured position the
 * anchor links (a SQL column, a config key, an environment variable, a route's path, a CLI flag),
 * and as a judgment one when it sits in prose (markdown, text). A use a compiler or generator bound
 * ({@link Confidence#EXTRACTED}) is the build's to check and a generated file is nobody's to edit:
 * neither is ever listed. A file merely sharing an anchor with the edited one is never listed, and
 * an edit that changes no anchor's name changes nothing here.
 */
final class AnchorEdits {

    private AnchorEdits() {}

    /** The record's directory under the graph's output, and its file. */
    static final String DIR = "anchors";

    static final String FILE = "stale";

    /** Files whose words are prose: an old name there is a judgment call, never a must. */
    private static final Set<String> PROSE = Set.of("md", "markdown", "adoc", "asciidoc", "rst", "txt");

    /** Path segments of generated sources: never listed, whatever they name. */
    private static final Pattern GENERATED = Pattern.compile("(^|/)(generated|generated-sources|gen)/");

    /**
     * One place that still carries a name an edit changed: {@code must} or a judgment call, the kind
     * of file it is ({@code markdown}, {@code sql}, {@code yml}), the anchor's kind, its old and new
     * name ({@code now} empty when the edit removed it), the place, the name as written there when it
     * is another anchor's ({@code PETCLINIC_VISITS_MAXPERDAY}, the variable that sets a config key), and
     * the edited file.
     */
    record Row(
            boolean must,
            String place,
            String kind,
            String old,
            String now,
            String rel,
            int line,
            String written,
            String edited) {

        String key() {
            return rel + "\t" + line + "\t" + kind + "\t" + old;
        }
    }

    /**
     * The places the edits to {@code edited} left stale, between graph {@code before} and {@code
     * after}; {@code changed} is every file the refresh found changed, whose places are listed only
     * where their text still carries the old name, {@code files} every file of the tree (its prose is
     * searched for the old name), and {@code text} reads a file as it is now ({@code null} when it is
     * gone).
     */
    static List<Row> diff(
            CodeGraph before,
            CodeGraph after,
            Set<String> edited,
            Set<String> changed,
            Collection<String> files,
            Function<String, @Nullable String> text) {
        Map<String, Map<String, List<GraphEdge>>> was = byFile(before, edited);
        if (was.isEmpty()) return List.of();
        Map<String, Map<String, List<GraphEdge>>> is = byFile(after, edited);
        Map<String, Row> out = new LinkedHashMap<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        Now present = new Now(after, byFile(after, changed), changed, text, lines);
        for (Map.Entry<String, Map<String, List<GraphEdge>>> file : was.entrySet()) {
            String f = file.getKey();
            Map<String, List<GraphEdge>> had = file.getValue();
            Map<String, List<GraphEdge>> has = is.getOrDefault(f, Map.of());
            List<String> added = has.keySet().stream()
                    .filter(a -> !had.containsKey(a))
                    .sorted()
                    .toList();
            for (Map.Entry<String, List<GraphEdge>> anchor : had.entrySet()) {
                String a = anchor.getKey();
                if (has.containsKey(a)) continue;
                String kind = Anchors.kindOf(a);
                if (kind == null) continue;
                String to = renamedTo(a, kind, anchor.getValue(), added, has, after);
                boolean defined =
                        anchor.getValue().stream().anyMatch(e -> e.relation().equals(Relations.DEFINES));
                // A use dropped leaves nothing stale; a definition dropped only while nothing else defines it.
                if (to == null && (!defined || definedElsewhere(after, a, f))) continue;
                String old = label(before, a);
                String now = to == null ? "" : label(after, to);
                for (GraphEdge e : others(before, a, f)) {
                    rows(e, kind, old, now, "", f, present, out);
                }
                if (Anchors.CONFIG.equals(kind)) {
                    for (GraphEdge b : before.incident(a)) {
                        if (!b.relation().equals(Relations.BINDS) || !b.target().equals(a)) continue;
                        String written = label(before, b.source());
                        for (GraphEdge e : others(before, b.source(), f)) {
                            rows(e, kind, old, now, written, f, present, out);
                        }
                    }
                }
                prose(kind, old, now, f, files, text, lines, out);
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * {@code rows} still standing after a refresh: each whose file, if it changed, still names the old
     * anchor (in the new graph while the anchor is there, else, and always for prose, by its text), at
     * the line nearest the
     * one recorded, and whose edit is not undone (the edited file names the old anchor again).
     */
    static List<Row> kept(
            List<Row> rows, CodeGraph after, Set<String> changed, Function<String, @Nullable String> text) {
        if (changed.isEmpty()) return rows;
        Map<String, List<String>> lines = new LinkedHashMap<>();
        Map<String, Map<String, List<GraphEdge>>> is = byFile(after, changed);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            String id;
            try {
                id = Anchors.id(r.kind(), r.old());
            } catch (IllegalArgumentException notAnAnchor) {
                continue;
            }
            if (changed.contains(r.edited())
                    && is.getOrDefault(r.edited(), Map.of()).containsKey(id)) continue;
            if (!changed.contains(r.rel())) {
                out.add(r);
                continue;
            }
            // The place's anchor: the variable's for a key set through the environment.
            String target = r.written().isEmpty() ? id : Anchors.id(Anchors.ENV, r.written());
            int at;
            if (after.has(target) && !PROSE.contains(r.place())) {
                List<GraphEdge> still = is.getOrDefault(r.rel(), Map.of()).get(target);
                if (still == null) continue;
                at = nearest(still, r.line());
            } else {
                at = carriedAt(r.kind(), r.old(), r.written(), r.line(), linesOf(r.rel(), text, lines));
                if (at < 1) continue;
            }
            out.add(new Row(r.must(), r.place(), r.kind(), r.old(), r.now(), r.rel(), at, r.written(), r.edited()));
        }
        return out;
    }

    /**
     * Brings the record under {@code out} up to the refresh from {@code before} to {@code after}:
     * the rows still standing, then the new ones, a later row replacing one at the same place.
     */
    static void update(
            Path out,
            @Nullable CodeGraph before,
            CodeGraph after,
            Set<String> changed,
            Set<String> unknown,
            Collection<String> files,
            Function<String, @Nullable String> text)
            throws IOException {
        if (changed.isEmpty()) return;
        Path file = out.resolve(DIR).resolve(FILE);
        List<Row> had = read(file);
        Set<String> edited = new TreeSet<>(changed);
        edited.removeAll(unknown);
        List<Row> fresh = before == null ? List.of() : diff(before, after, edited, changed, files, text);
        if (had.isEmpty() && fresh.isEmpty()) return;
        Map<String, Row> all = new LinkedHashMap<>();
        for (Row r : kept(had, after, changed, text)) all.put(r.key(), r);
        for (Row r : fresh) all.put(r.key(), r);
        write(file, List.copyOf(all.values()));
    }

    /** The rows of {@code file}; none when it is missing or unreadable. */
    static List<Row> read(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        List<Row> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length != 9) continue;
                out.add(new Row(
                        f[0].equals("must"),
                        RenameStore.unesc(f[1]),
                        f[2],
                        RenameStore.unesc(f[3]),
                        RenameStore.unesc(f[4]),
                        RenameStore.unesc(f[5]),
                        Integer.parseInt(f[6]),
                        RenameStore.unesc(f[7]),
                        RenameStore.unesc(f[8])));
            }
        } catch (IOException | NumberFormatException unreadable) {
            return List.of();
        }
        return out;
    }

    /**
     * {@code must|may \t place \t kind \t old \t now \t rel \t line \t written \t edited}, one row per
     * place; the file is removed when none is left.
     */
    static void write(Path file, List<Row> rows) throws IOException {
        if (rows.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        StringBuilder s = new StringBuilder();
        for (Row r : rows) {
            s.append(r.must() ? "must" : "may")
                    .append('\t')
                    .append(RenameStore.esc(r.place()))
                    .append('\t')
                    .append(r.kind())
                    .append('\t')
                    .append(RenameStore.esc(r.old()))
                    .append('\t')
                    .append(RenameStore.esc(r.now()))
                    .append('\t')
                    .append(RenameStore.esc(r.rel()))
                    .append('\t')
                    .append(r.line())
                    .append('\t')
                    .append(RenameStore.esc(r.written()))
                    .append('\t')
                    .append(RenameStore.esc(r.edited()))
                    .append('\n');
        }
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, s.toString());
    }

    /** For each of {@code files}, the anchors it defines or uses, each with its edges, by anchor id. */
    static Map<String, Map<String, List<GraphEdge>>> byFile(CodeGraph g, Collection<String> files) {
        Map<String, Map<String, List<GraphEdge>>> out = new TreeMap<>();
        if (files.isEmpty()) return out;
        for (GraphNode n : g.nodes()) {
            if (!Anchors.is(n)) continue;
            for (GraphEdge e : g.incident(n.id())) {
                if (!e.target().equals(n.id()) || !link(e) || !files.contains(e.sourceFile())) continue;
                out.computeIfAbsent(e.sourceFile(), k -> new TreeMap<>())
                        .computeIfAbsent(n.id(), k -> new ArrayList<>())
                        .add(e);
            }
        }
        return out;
    }

    private static boolean link(GraphEdge e) {
        return e.relation().equals(Relations.DEFINES) || e.relation().equals(Relations.USES);
    }

    /**
     * The anchor of {@code removed}'s kind the file names now in its place: the one added, of that
     * kind, nearest its old line (a column on the same table first); {@code null} when none was added.
     */
    private static @Nullable String renamedTo(
            String removed,
            String kind,
            List<GraphEdge> was,
            List<String> added,
            Map<String, List<GraphEdge>> has,
            CodeGraph after) {
        String table = parentOf(removed);
        int line = firstLine(was);
        String best = null;
        long bestScore = Long.MAX_VALUE;
        for (String a : added) {
            if (!kind.equals(Anchors.kindOf(a)) || !after.has(a)) continue;
            long score = Math.abs((long) firstLine(has.getOrDefault(a, List.of())) - line)
                    + (table.isEmpty() || table.equals(parentOf(a)) ? 0 : 1_000_000L);
            if (score < bestScore) {
                best = a;
                bestScore = score;
            }
        }
        return best;
    }

    /** {@code owners} for {@code column:owners.telephone}; empty for a kind with no parent. */
    private static String parentOf(String id) {
        if (!Anchors.COLUMN.equals(Anchors.kindOf(id))) return "";
        int dot = id.lastIndexOf('.');
        return dot < 0 ? "" : id.substring(0, dot);
    }

    private static int firstLine(List<GraphEdge> edges) {
        int min = Integer.MAX_VALUE;
        for (GraphEdge e : edges) min = Math.min(min, lineOf(e.sourceLocation()));
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static boolean definedElsewhere(CodeGraph g, String anchor, String file) {
        for (GraphEdge e : g.incident(anchor)) {
            if (e.target().equals(anchor)
                    && e.relation().equals(Relations.DEFINES)
                    && !e.sourceFile().equals(file)) return true;
        }
        return false;
    }

    /** The edges into {@code anchor} that define or use it from a file other than {@code file}. */
    private static List<GraphEdge> others(CodeGraph g, String anchor, String file) {
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : g.incident(anchor)) {
            if (e.target().equals(anchor)
                    && link(e)
                    && !e.sourceFile().isEmpty()
                    && !e.sourceFile().equals(file)) out.add(e);
        }
        return out;
    }

    /**
     * The tree after a refresh, as a diff asks it: the new graph, the anchor edges of the changed
     * files in it, the changed files, and their text as it is now (read once).
     */
    private record Now(
            CodeGraph after,
            Map<String, Map<String, List<GraphEdge>>> edges,
            Set<String> changed,
            Function<String, @Nullable String> text,
            Map<String, List<String>> lines) {

        List<String> lines(String rel) {
            return linesOf(rel, text, lines);
        }
    }

    /**
     * The rows edge {@code e}'s sites make. A changed file's places are its edges to the anchor in the
     * new graph while the anchor is there (an entity remapped to the new column is fixed though its
     * property keeps the old word), else the lines whose text still carries the name.
     */
    private static void rows(
            GraphEdge e,
            String kind,
            String old,
            String now,
            String written,
            String edited,
            Now present,
            Map<String, Row> out) {
        String rel = e.sourceFile();
        if (e.confidence() == Confidence.EXTRACTED || GENERATED.matcher(rel).find()) return;
        String place = place(rel);
        boolean must = !PROSE.contains(place) && e.confidence() != Confidence.AMBIGUOUS;
        List<GraphEdge> found = List.of(e);
        boolean byText = false;
        if (present.changed().contains(rel)) {
            if (present.after().has(e.target())) {
                List<GraphEdge> still =
                        present.edges().getOrDefault(rel, Map.of()).get(e.target());
                if (still == null) return;
                found = still;
            } else {
                byText = true;
            }
        }
        for (GraphEdge s : found) {
            for (String site : s.sites().isEmpty() ? List.of("") : s.sites()) {
                int line = lineOf(site);
                if (byText) {
                    line = carriedAt(kind, old, written, line, present.lines(rel));
                    if (line < 1) continue;
                } else if (line < 1) {
                    // A place compiled facts give no line for (a field's mapping): its declaration, else its text.
                    List<String> body = present.lines(rel);
                    int at = declaredAt(s, body);
                    line = at > 0 ? at : carriedAt(kind, old, written, 0, body);
                }
                Row r = new Row(must, place, kind, old, now, rel, Math.max(line, 0), written, edited);
                out.putIfAbsent(r.key(), r);
            }
        }
    }

    /** The site of {@code edges} nearest {@code line}; {@code line} itself when none has one. */
    private static int nearest(List<GraphEdge> edges, int line) {
        int best = 0;
        for (GraphEdge e : edges) {
            for (String site : e.sites()) {
                int l = lineOf(site);
                if (l > 0 && (best == 0 || Math.abs(l - line) < Math.abs(best - line))) best = l;
            }
        }
        return best > 0 ? best : line;
    }

    /** The most prose places one changed name lists: they are counted, never shown one by one. */
    static final int PROSE_LIMIT = 50;

    /**
     * The prose that names {@code old} as a word: a judgment call each, since a README or a design
     * note may mean the old name on purpose. The graph links no prose to an anchor, so its text is
     * searched, and only for a name an edit changed.
     */
    private static void prose(
            String kind,
            String old,
            String now,
            String edited,
            Collection<String> files,
            Function<String, @Nullable String> text,
            Map<String, List<String>> lines,
            Map<String, Row> out) {
        Pattern word = word(kind, old, "");
        int found = 0;
        for (String rel :
                files.stream().filter(r -> PROSE.contains(place(r))).sorted().toList()) {
            if (rel.equals(edited) || GENERATED.matcher(rel).find()) continue;
            List<String> body = linesOf(rel, text, lines);
            for (int i = 0; i < body.size() && found < PROSE_LIMIT; i++) {
                if (!word.matcher(body.get(i)).find()) continue;
                Row r = new Row(false, place(rel), kind, old, now, rel, i + 1, "", edited);
                if (out.putIfAbsent(r.key(), r) == null) found++;
            }
        }
    }

    /**
     * The line that declares the field a mapping edge comes from ({@code private String telephone;}),
     * whose context is {@code field <name>}; 0 for any other edge, or when no line declares it.
     */
    static int declaredAt(GraphEdge e, List<String> lines) {
        String c = e.context();
        if (c == null || !c.startsWith("field ")) return 0;
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(c.substring("field ".length())) + "\\s*[;=]");
        for (int i = 0; i < lines.size(); i++) {
            if (declaration.matcher(lines.get(i)).find()) return i + 1;
        }
        return 0;
    }

    /** A file's kind: its extension lower-cased, or its name when it has none ({@code Dockerfile}). */
    static String place(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }

    /** {@code rel}'s lines as it is now, read once per diff; empty when it is gone. */
    private static List<String> linesOf(
            String rel, Function<String, @Nullable String> text, Map<String, List<String>> cache) {
        return cache.computeIfAbsent(rel, r -> {
            String t = text.apply(r);
            return t == null ? List.of() : t.lines().toList();
        });
    }

    /**
     * The line nearest {@code near} of {@code lines} that still carries anchor name {@code old} of
     * {@code kind}, as {@code written} when that is set; 0 when no line does.
     */
    static int carriedAt(String kind, String old, String written, int near, List<String> lines) {
        Pattern word = word(kind, old, written);
        int best = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean hit = Anchors.CONFIG.equals(kind) && written.isEmpty()
                    ? Anchors.relaxed(line).contains(Anchors.relaxed(lastPart(old)))
                    : word.matcher(line).find();
            if (hit && (best == 0 || Math.abs(i + 1 - near) < Math.abs(best - near))) best = i + 1;
        }
        return best;
    }

    /** The word a line carries the old name by: as written, else the last part of the name. */
    private static Pattern word(String kind, String old, String written) {
        String w = written;
        if (w.isEmpty()) {
            w = switch (kind) {
                case Anchors.COLUMN, Anchors.TABLE -> old.substring(old.lastIndexOf('.') + 1);
                case Anchors.ROUTE -> lastLiteral(old);
                default -> old;
            };
        }
        return Pattern.compile(
                "(?<![\\w-])" + Pattern.quote(w) + "(?![\\w-])",
                Anchors.COLUMN.equals(kind) || Anchors.TABLE.equals(kind) ? Pattern.CASE_INSENSITIVE : 0);
    }

    /**
     * {@code max-per-day} for {@code petclinic.visits.max-per-day}: what a class binding the key under
     * its prefix names its field.
     */
    private static String lastPart(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /** {@code pets} for {@code GET /owners/{}/pets}: the last path segment that is no variable. */
    private static String lastLiteral(String route) {
        String path = route.substring(route.indexOf(' ') + 1);
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty() && !parts[i].equals("{}")) return parts[i];
        }
        return path;
    }

    private static String label(CodeGraph g, String id) {
        GraphNode n = g.node(id);
        return n == null ? id.substring(id.indexOf(':') + 1) : n.label();
    }

    private static int lineOf(String location) {
        try {
            return location.startsWith("L") ? Integer.parseInt(location.substring(1)) : 0;
        } catch (NumberFormatException notALine) {
            return 0;
        }
    }
}
