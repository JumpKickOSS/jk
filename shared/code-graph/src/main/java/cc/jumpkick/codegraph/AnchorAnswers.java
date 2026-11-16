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
        String[] listing = Anchors.listing(wanted);
        if (listing != null) {
            return SymbolFinder.of(Map.of("kind", listing[0]), listing[1].isEmpty() ? null : listing[1])
                    .find(graph, scope, budget);
        }
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
            GraphNode owner = graph.owner(id);
            GraphNode n = graph.node(id);
            String property = n == null ? null : property(n.label());
            if (owner != null && property != null) anchors.addAll(fieldAnchors(owner.id(), property));
        }
        return hints(anchors);
    }

    /** The anchors type {@code typeId} maps its field {@code field} to: a JPA entity's column. */
    List<String> fieldAnchors(String typeId, String field) {
        Set<String> out = new TreeSet<>();
        for (GraphEdge e : graph.incident(typeId)) {
            if (e.source().equals(typeId)
                    && ("field " + field).equals(e.context())
                    && Anchors.is(graph.node(e.target()))) out.add(e.target());
        }
        return List.copyOf(out);
    }

    /** {@link #hints} for the anchors field {@code field} of {@code typeId} maps to. */
    List<String> fieldHints(String typeId, String field) {
        return hints(new TreeSet<>(fieldAnchors(typeId, field)));
    }

    /**
     * One line per anchor: its uses no compiler checks, counted, and the question that lists them. A
     * route's clients call it over HTTP and a column's queries are strings, so a callers answer from the
     * compiled classes never names them.
     */
    private List<String> hints(Set<String> anchors) {
        List<String> out = new ArrayList<>();
        for (String a : anchors) {
            int uses = (int) edges(a, Relations.USES).stream()
                    .filter(e -> e.confidence() != Confidence.EXTRACTED)
                    .count();
            out.add((Anchors.ROUTE.equals(Anchors.kindOf(a)) ? "It defines " : "It maps to ") + GraphQuery.clean(a)
                    + ", "
                    + (uses == 0
                            ? "which nothing in the graph uses."
                            : count(uses, "use") + " no compiler checks:" + " callers '" + GraphQuery.clean(a)
                                    + "' lists them."));
        }
        return out;
    }

    /** {@code telephone} for {@code .getTelephone()}, {@code .setTelephone()} or {@code .isTelephone()}; else null. */
    private static @Nullable String property(String label) {
        String name = label.startsWith(".") ? label.substring(1) : label;
        if (name.endsWith("()")) name = name.substring(0, name.length() - 2);
        for (String prefix : List.of("get", "set", "is")) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length())) + name.substring(prefix.length() + 1);
            }
        }
        return null;
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
        List<GraphEdge> drops = edges(id, Relations.DROPS);
        if (!drops.isEmpty()) {
            lines.add("dropped or renamed away:");
            for (GraphEdge e : drops) line(e, "  ", lines);
        }
        bindings(id, lines);
        if (defs.isEmpty()) lines.add("No file defines it.");
        boolean binds = graph.incident(id).stream()
                .anyMatch(
                        e -> e.relation().equals(Relations.BINDS) && e.source().equals(id));
        if (uses.isEmpty() && !binds) lines.add("Nothing uses it.");
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

    /**
     * The anchors {@code id} binds to or is bound from ({@link Relations#BINDS}: a variable and the key
     * it sets): for a variable, each key with its definitions and uses; for a key, each variable with
     * the lines that set it.
     */
    private void bindings(String id, List<String> lines) {
        for (GraphEdge b : graph.incident(id)) {
            if (!b.relation().equals(Relations.BINDS)) continue;
            boolean out = b.source().equals(id);
            String other = out ? b.target() : b.source();
            List<GraphEdge> defs = edges(other, Relations.DEFINES);
            List<GraphEdge> uses = out ? edges(other, Relations.USES) : List.of();
            lines.add((out ? "sets " : "set by ") + GraphQuery.clean(other) + GraphTools.confidence(b) + ": "
                    + count(defs.size(), "definition") + (out ? ", " + count(uses.size(), "use") : "") + ":");
            for (GraphEdge e : defs) line(e, "    ", lines);
            for (GraphEdge e : uses) line(e, "    ", lines);
        }
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
        String context = e.context();
        String written = context == null
                ? ""
                : context.startsWith("field ") ? "  " + GraphQuery.clean(context) : "  as " + GraphQuery.clean(context);
        lines.add(indent + who + at + written + GraphTools.confidence(e));
        // The line that sets a variable holds its value, which may be a secret: name the place, quote nothing.
        boolean setsVariable = e.relation().equals(Relations.DEFINES) && Anchors.ENV.equals(Anchors.kindOf(e.target()));
        if (!setsVariable) lines.addAll(tools.quotes(e).stream().limit(1).toList());
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
