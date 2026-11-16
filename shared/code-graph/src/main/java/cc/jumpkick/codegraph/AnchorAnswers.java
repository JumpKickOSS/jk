// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The answers about anchors ({@link Anchors}) that {@link GraphTools} gives: an anchor's definitions
 * and uses for {@code callers}, the place an anchor shows in other answers, and the anchors a method
 * defines, under its callers.
 */
final class AnchorAnswers {

    private final CodeGraph graph;
    private final Scope scope;
    private final GraphTools tools;

    AnchorAnswers(CodeGraph graph, Scope scope, GraphTools tools) {
        this.graph = graph;
        this.scope = scope;
        this.tools = tools;
    }

    /**
     * The anchor answer {@code callers} gives for {@code wanted}: always when it is written {@code
     * <kind>:<name>} or is an anchor's id, and for a bare label only when no method answers to it
     * ({@code noMethod}); {@code null} otherwise.
     */
    @Nullable
    String answer(String wanted, boolean noMethod, int budget) {
        if (Anchors.parse(wanted) != null || Anchors.is(graph.node(wanted))) {
            return String.valueOf(uses(wanted, budget));
        }
        return noMethod ? uses(wanted, budget) : null;
    }

    /**
     * One line per anchor the methods {@code named} define (a handler's route): its uses no compiler
     * links to the method, counted, and the question that lists them. A route's clients call it over
     * HTTP, so a callers answer from the compiled classes never names them.
     */
    List<String> defined(List<String> named) {
        Set<String> anchors = new TreeSet<>();
        for (String id : named) {
            for (GraphEdge e : graph.incident(id)) {
                if (e.source().equals(id)
                        && e.relation().equals(Relations.DEFINES)
                        && Anchors.is(graph.node(e.target()))) anchors.add(e.target());
            }
        }
        List<String> out = new ArrayList<>();
        for (String a : anchors) {
            int uses = (int) edges(a, Relations.USES).stream()
                    .filter(e -> e.confidence() != Confidence.EXTRACTED)
                    .count();
            out.add("It defines " + GraphQuery.clean(a) + ", "
                    + (uses == 0
                            ? "which nothing in the graph uses."
                            : count(uses, "use") + " no compiler checks:" + " callers '" + GraphQuery.clean(a)
                                    + "' lists them."));
        }
        return out;
    }

    /** The most definitions an anchor's place lists. */
    private static final int ANCHOR_PLACES = 3;

    /** An anchor's place: two spaces and its definitions' {@code file:line}, at most {@link #ANCHOR_PLACES}. */
    String where(String id) {
        List<String> places = new ArrayList<>();
        for (GraphEdge e : edges(id, Relations.DEFINES)) {
            String at = GraphTools.at(e).strip();
            places.add(at.isEmpty() ? GraphQuery.clean(e.sourceFile()) : at);
        }
        if (places.isEmpty()) return "";
        int more = places.size() - ANCHOR_PLACES;
        return "  " + String.join(", ", places.subList(0, Math.min(places.size(), ANCHOR_PLACES)))
                + (more > 0 ? " and " + more + " more" : "");
    }

    /**
     * The anchor {@code name} names: written {@code <kind>:<name>} ({@code route:GET /orders/{id}}), or
     * the label of exactly one anchor; {@code null} for anything else.
     */
    @Nullable
    String anchor(String name) {
        String parsed = Anchors.parse(name);
        if (parsed != null) return graph.has(parsed) ? parsed : null;
        String wanted = name.strip();
        if (Anchors.is(graph.node(wanted))) return wanted;
        List<String> hits = new ArrayList<>();
        for (GraphNode n : graph.nodes()) {
            if (Anchors.is(n) && n.label().equalsIgnoreCase(wanted)) hits.add(n.id());
        }
        return hits.size() == 1 ? hits.getFirst() : null;
    }

    /**
     * Every definition and use of an anchor, answered so each can be edited from the answer: its
     * definitions, then its uses (main sources, tests, then every other file), each at {@code
     * file:line} with the line quoted, the name as written when it differs, and {@code (inferred)} on a
     * use matched by text; counts by grade first. {@code null} when {@code name} is no anchor, and an
     * answer saying so when it is written as one the graph does not hold.
     */
    @Nullable
    String uses(String name, int budget) {
        String id = anchor(name);
        if (id == null) {
            String parsed = Anchors.parse(name);
            if (parsed == null) return null;
            return "Nothing in the graph defines or uses " + GraphQuery.clean(parsed) + ".";
        }
        List<GraphEdge> defs = edges(id, Relations.DEFINES);
        List<GraphEdge> uses = edges(id, Relations.USES);
        Map<String, Integer> grades = new TreeMap<>();
        for (GraphEdge e : uses) grades.merge(e.confidence().grade(), 1, Integer::sum);
        List<String> lines = new ArrayList<>();
        GraphNode node = graph.node(id);
        String label = node == null ? id : node.label();
        String head = GraphQuery.clean(id)
                + (Anchors.key(String.valueOf(Anchors.kindOf(id)), label).equals(label)
                        ? ""
                        : " (" + GraphQuery.clean(label) + ")");
        lines.add(head + ": " + count(defs.size(), "definition") + ", " + count(uses.size(), "use")
                + (grades.isEmpty()
                        ? "."
                        : " ("
                                + String.join(
                                        ", ",
                                        grades.entrySet().stream()
                                                .map(g -> g.getValue() + " " + g.getKey())
                                                .toList())
                                + ")."));
        if (!defs.isEmpty()) {
            lines.add("defined:");
            for (GraphEdge e : defs) line(e, "  ", lines);
        }
        Map<Modules.SourceSet, List<GraphEdge>> bySet = new TreeMap<>();
        for (GraphEdge e : uses)
            bySet.computeIfAbsent(place(e.sourceFile()), k -> new ArrayList<>()).add(e);
        bySet.forEach((set, edges) -> {
            lines.add(set.name().toLowerCase(Locale.ROOT) + ":");
            for (GraphEdge e : edges) line(e, "  ", lines);
        });
        if (defs.isEmpty()) lines.add("No file defines it.");
        if (uses.isEmpty()) lines.add("Nothing uses it.");
        if (grades.containsKey("inferred")) {
            lines.add("An inferred use was matched by text and nothing checked it; declared ones are read from"
                    + " annotations and definition files.");
        }
        return Budget.lines(lines, budget, "Raise the budget, or narrow with scope, modules or source_set");
    }

    /**
     * Where a use sits: main or test for a JVM source or a resource under a source set, every other
     * file elsewhere. A front end's {@code src/app/} reads as a test suite by layout alone.
     */
    private Modules.SourceSet place(String rel) {
        Modules.SourceSet set = graph.modules().sourceSet(rel);
        if (set == Modules.SourceSet.MAIN) return set;
        String lower = rel.toLowerCase(Locale.ROOT);
        boolean jvm = lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".groovy")
                || lower.endsWith(".scala")
                || lower.contains("/resources/");
        return set == Modules.SourceSet.TEST && jvm ? set : Modules.SourceSet.OTHER;
    }

    /** The edges {@code relation} into anchor {@code id} inside the scope, by file and line. */
    private List<GraphEdge> edges(String id, String relation) {
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : graph.incident(id)) {
            if (e.target().equals(id) && e.relation().equals(relation) && scope.containsPath(e.sourceFile())) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(GraphEdge::sourceFile)
                .thenComparingInt(e -> GraphTools.lineNumber(e.sourceLocation()))
                .thenComparing(GraphEdge::source));
        return out;
    }

    /** {@code Owner.member()  file:line  as written (inferred)} and its first quoted line; a file names itself. */
    private void line(GraphEdge e, String indent, List<String> lines) {
        GraphNode from = graph.node(e.source());
        boolean file = from != null && e.source().equals(GraphIds.file(from.sourceFile()));
        String at = GraphTools.at(e).strip();
        if (at.isEmpty()) at = tools.where(e.source()).strip();
        if (at.isEmpty()) at = GraphQuery.clean(e.sourceFile());
        String who = file ? "" : GraphQuery.clean(graph.display(e.source())) + "  ";
        String written = e.context() == null ? "" : "  as " + GraphQuery.clean(e.context());
        lines.add(indent + who + at + written + GraphTools.confidence(e));
        lines.addAll(tools.quotes(e).stream().limit(1).toList());
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
