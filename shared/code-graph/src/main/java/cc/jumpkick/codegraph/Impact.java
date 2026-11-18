// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What else a change to a symbol or an anchor reaches, folded into the {@code callers} answer so an
 * agent asks once instead of once per accessor, column and template: a field's accessors and their
 * callers, the anchors it maps to (a column, a JSON or bean property, a configuration key) with the
 * places no compiler checks, and the tests that reach it; a type's anchors; an anchor's Java side.
 * Each list is bounded, the places that must change first, and every answer says which kinds it
 * looked at rather than that nothing else exists.
 */
final class Impact {

    /** The most places one anchor lists before a count and the question that lists the rest. */
    static final int PLACES = 6;

    /** The most callers one accessor lists before a count. */
    static final int CALLERS = 6;

    /** The most test files named. */
    static final int TESTS = 6;

    /** The most anchors a type's answer lists, one line each. */
    static final int TYPE_ANCHORS = 12;

    /** The anchor kinds a field can map to, as an answer names what it looked at. */
    static final String FIELD_KINDS = "column, json, bean, config";

    private final CodeGraph graph;
    private final Scope scope;
    private final GraphTools tools;

    Impact(CodeGraph graph, Scope scope, GraphTools tools) {
        this.graph = graph;
        this.scope = scope;
        this.tools = tools;
    }

    /** A field's accessor methods on {@code typeId}: {@code getX}, {@code isX}, {@code setX} and a record's {@code x()}. */
    List<String> accessors(String typeId, String field) {
        if (field.isEmpty()) return List.of();
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        Set<String> names = Set.of("get" + cap, "is" + cap, "set" + cap, field);
        List<String> out = new ArrayList<>();
        for (GraphEdge e : graph.incident(typeId)) {
            if (!e.source().equals(typeId) || !e.relation().equals(Relations.METHOD)) continue;
            GraphNode m = graph.node(e.target());
            if (m != null && m.label().endsWith("()") && names.contains(bare(m.label()))) out.add(m.id());
        }
        out.sort(Comparator.comparing(graph::display));
        return out;
    }

    /**
     * The anchors field {@code field} of {@code typeId} maps to: a column or configuration key its
     * declaration names, and a JSON or bean property the field or one of its accessors declares.
     */
    Set<String> anchors(String typeId, String field) {
        Set<String> out = new TreeSet<>();
        Set<String> members = new LinkedHashSet<>();
        members.add("field " + field);
        for (String a : accessors(typeId, field)) members.add("method " + bare(label(a)));
        for (GraphEdge e : graph.incident(typeId)) {
            if (e.source().equals(typeId) && members.contains(e.context()) && Anchors.is(graph.node(e.target())))
                out.add(e.target());
        }
        return out;
    }

    /**
     * The lines a field's answer carries: before its own uses, what was looked at and the places no
     * compiler checks under each anchor it maps to; after them, its accessors' callers and the tests
     * that reach it.
     */
    ImpactLines field(String typeId, String field) {
        List<String> accessors = accessors(typeId, field);
        Set<String> anchors = anchors(typeId, field);
        List<String> before = new ArrayList<>();
        before.add(lookedAt(accessors, anchors));
        places(anchors, Set.of(typeId), before);
        List<String> after = new ArrayList<>();
        callers(accessors, after);
        tests(accessors, anchors, after);
        return new ImpactLines(before, after);
    }

    /** The line naming what a field's answer looked at: never a claim that nothing else exists. */
    private String lookedAt(List<String> accessors, Set<String> anchors) {
        Set<String> kinds = new TreeSet<>();
        for (String a : anchors) kinds.add(String.valueOf(Anchors.kindOf(a)));
        return "Looked at: its Java uses, "
                + (accessors.isEmpty() ? "accessors (none declared)" : "its accessors' callers")
                + ", anchors it maps to (" + FIELD_KINDS + "; found "
                + (kinds.isEmpty() ? "none" : String.join(", ", kinds))
                + ") and the tests reaching those. Reflection and names built at run time are not looked at.";
    }

    /**
     * Under an {@code anchors} title, each anchor with its places outside {@code home} (the type or
     * methods whose own declaration links it): definitions, the variables that set it, then uses that
     * must change, then judgment calls, at most {@link #PLACES}; the rest counted with the question
     * that lists them. A use a compiler binds (generated code) is counted, not listed.
     */
    void places(Set<String> anchors, Set<String> home, List<String> lines) {
        if (anchors.isEmpty()) return;
        lines.add("anchors (places no compiler checks; must-change first):");
        for (String a : anchors) {
            List<GraphEdge> defs = new ArrayList<>();
            List<GraphEdge> musts = new ArrayList<>();
            List<GraphEdge> judged = new ArrayList<>();
            int bound = 0;
            for (GraphEdge e : graph.incident(a)) {
                if (!e.target().equals(a) || home.contains(e.source()) || !scope.containsPath(e.sourceFile())) continue;
                if (e.relation().equals(Relations.DEFINES)) defs.add(e);
                else if (e.relation().equals(Relations.USES) && e.confidence() == Confidence.EXTRACTED) bound++;
                else if (e.relation().equals(Relations.USES))
                    (e.confidence() == Confidence.AMBIGUOUS ? judged : musts).add(e);
            }
            // A variable that sets the key (Spring's relaxed binding) is set where it is defined: those lines change
            // too.
            List<GraphEdge> sets = new ArrayList<>();
            for (GraphEdge b : graph.incident(a)) {
                if (!b.relation().equals(Relations.BINDS) || !b.target().equals(a)) continue;
                for (GraphEdge e : graph.incident(b.source())) {
                    if (e.target().equals(b.source())
                            && e.relation().equals(Relations.DEFINES)
                            && scope.containsPath(e.sourceFile())) sets.add(e);
                }
            }
            List<String> counts = new ArrayList<>();
            if (!defs.isEmpty()) counts.add(count(defs.size(), "definition"));
            if (!sets.isEmpty()) counts.add(count(sets.size(), "variable setting it", "variables setting it"));
            int uses = musts.size() + judged.size();
            if (uses > 0)
                counts.add(count(uses, "use") + (judged.isEmpty() ? "" : " (" + judged.size() + " judgment)"));
            String checked = bound == 0 ? "" : " (and " + count(bound, "use") + " a compiler checks)";
            lines.add("  " + GraphQuery.clean(a) + checked + ": "
                    + (counts.isEmpty()
                            ? "nothing outside the code that declares it."
                            : String.join(", ", counts) + ":"));
            List<GraphEdge> ranked = new ArrayList<>();
            for (List<GraphEdge> set : List.of(defs, sets, musts, judged)) {
                set.sort(Comparator.comparing(GraphEdge::sourceFile)
                        .thenComparingInt(e -> GraphTools.lineNumber(e.sourceLocation())));
                ranked.addAll(set);
            }
            for (GraphEdge e : ranked.subList(0, Math.min(ranked.size(), PLACES))) {
                String at = GraphTools.at(e).strip();
                if (at.isEmpty()) at = tools.where(e.source()).strip();
                boolean file = e.source().equals(GraphIds.file(e.sourceFile()));
                String who = file || graph.node(e.source()) == null
                        ? ""
                        : GraphQuery.clean(graph.display(e.source())) + "  ";
                // The line that sets a variable holds its value, which may be a secret: name the place, quote nothing.
                boolean variable = sets.contains(e);
                lines.add("    " + who + at
                        + (variable
                                ? " (sets " + GraphQuery.clean(e.target()) + ")"
                                : e.relation().equals(Relations.DEFINES) ? " (defines)" : "")
                        + GraphTools.confidence(e));
                boolean secret =
                        variable || e.relation().equals(Relations.DEFINES) && Anchors.ENV.equals(Anchors.kindOf(a));
                if (!secret)
                    lines.addAll(
                            tools.quotes(e).stream().limit(1).map(q -> "  " + q).toList());
            }
            if (ranked.size() > PLACES) {
                lines.add("    +" + (ranked.size() - PLACES) + " more: callers '" + GraphQuery.clean(a)
                        + "' lists them.");
            }
        }
    }

    /** Under an {@code accessors} title, each accessor's direct callers, main before tests, bounded. */
    private void callers(List<String> accessors, List<String> lines) {
        if (accessors.isEmpty()) return;
        lines.add("accessors (callers from the compiled classes):");
        for (String a : accessors) {
            List<GraphEdge> calls = sorted(tools.directCalls(a));
            long tests = calls.stream().filter(e -> test(e.sourceFile())).count();
            lines.add("  " + GraphQuery.clean(graph.display(a)) + ": "
                    + (calls.isEmpty()
                            ? "nothing in the compiled classes calls it."
                            : (calls.size() - tests) + " in main sources, " + tests + " in tests:"));
            for (GraphEdge e : calls.subList(0, Math.min(calls.size(), CALLERS))) {
                lines.add("    " + GraphQuery.clean(graph.display(e.source())) + GraphTools.at(e));
                lines.addAll(
                        tools.quotes(e).stream().limit(1).map(q -> "  " + q).toList());
            }
            if (calls.size() > CALLERS) {
                lines.add("    +" + (calls.size() - CALLERS) + " more: callers '" + GraphQuery.clean(graph.display(a))
                        + "' lists them.");
            }
        }
    }

    /** Calls by file and line, main sources before tests. */
    private List<GraphEdge> sorted(List<GraphEdge> calls) {
        List<GraphEdge> out = new ArrayList<>(calls);
        out.sort(Comparator.comparing((GraphEdge e) -> test(e.sourceFile()))
                .thenComparing(GraphEdge::sourceFile)
                .thenComparingInt(e -> GraphTools.lineNumber(e.sourceLocation())));
        return out;
    }

    /**
     * The test classes that reach {@code methods}: a test calling one, or calling a main-source method
     * that calls one, and a test file that uses one of {@code anchors}; at most {@link #TESTS}.
     */
    void tests(List<String> methods, Set<String> anchors, List<String> lines) {
        Map<String, Set<String>> byFile = new TreeMap<>();
        for (String m : methods) {
            for (GraphEdge e : tools.directCalls(m)) {
                if (test(e.sourceFile())) {
                    add(byFile, e);
                    continue;
                }
                for (GraphEdge up : tools.directCalls(e.source())) {
                    if (test(up.sourceFile())) add(byFile, up);
                }
            }
        }
        for (String a : anchors) {
            for (GraphEdge e : graph.incident(a)) {
                if (e.target().equals(a) && e.relation().equals(Relations.USES) && test(e.sourceFile())) add(byFile, e);
            }
        }
        if (byFile.isEmpty()) return;
        List<String> named = new ArrayList<>();
        for (Map.Entry<String, Set<String>> f : byFile.entrySet()) {
            if (named.size() == TESTS) break;
            named.add(f.getKey() + (f.getValue().isEmpty() ? "" : " (" + String.join(", ", f.getValue()) + ")"));
        }
        lines.add("tests that reach it (" + byFile.size() + (byFile.size() == 1 ? " class" : " classes") + "): "
                + String.join("; ", named) + (byFile.size() > TESTS ? "; +" + (byFile.size() - TESTS) + " more" : ""));
    }

    private void add(Map<String, Set<String>> byFile, GraphEdge e) {
        if (!scope.containsPath(e.sourceFile())) return;
        Set<String> methods = byFile.computeIfAbsent(stem(e.sourceFile()), k -> new TreeSet<>());
        GraphNode n = graph.node(e.source());
        if (n != null && n.label().startsWith(".") && methods.size() < 4)
            methods.add(GraphQuery.clean(bare(n.label())));
    }

    /**
     * The lines a method's callers answer ends with: under each anchor the method defines (a
     * handler's route) or maps to (an accessor's column), its places; for an accessor, the other
     * accessors of its property with their callers.
     */
    List<String> method(List<String> named) {
        Set<String> anchors = new TreeSet<>();
        List<String> siblings = new ArrayList<>();
        Set<String> home = new TreeSet<>(named);
        for (String id : named) {
            for (GraphEdge e : graph.incident(id)) {
                if (e.source().equals(id)
                        && e.relation().equals(Relations.DEFINES)
                        && Anchors.is(graph.node(e.target()))) anchors.add(e.target());
            }
            GraphNode owner = graph.owner(id);
            String property = property(label(id));
            if (owner == null || property == null) continue;
            home.add(owner.id());
            anchors.addAll(anchors(owner.id(), property));
            for (String a : accessors(owner.id(), property)) {
                if (!named.contains(a) && !siblings.contains(a)) siblings.add(a);
            }
        }
        List<String> lines = new ArrayList<>();
        places(anchors, home, lines);
        if (!siblings.isEmpty()) {
            List<String> said = new ArrayList<>();
            for (String s : siblings) {
                List<GraphEdge> calls = sorted(tools.directCalls(s));
                said.add(GraphQuery.clean(graph.display(s)) + " " + count(calls.size(), "caller") + sites(calls));
            }
            lines.add("same property: " + String.join("; ", said) + ".");
        }
        return lines;
    }

    /**
     * The lines an anchor's answer ends with: the Java side of each type whose field maps to it (the
     * field's accessors and their callers, its other anchors), and the tests that reach it.
     */
    List<String> anchor(String id) {
        List<String> lines = new ArrayList<>();
        Set<String> methods = new LinkedHashSet<>();
        Set<String> anchors = new TreeSet<>(Set.of(id));
        Set<String> seen = new TreeSet<>();
        for (GraphEdge e : graph.incident(id)) {
            if (!e.target().equals(id) || e.context() == null) continue;
            String context = e.context();
            if (!context.startsWith("field ") && !context.startsWith("method ")) continue;
            GraphNode type = graph.node(e.source());
            if (type == null || !Decl.isType(type)) continue;
            String member = context.substring(context.indexOf(' ') + 1);
            String field = context.startsWith("field ") ? member : property("." + member + "()");
            if (field == null || !seen.add(type.id() + "#" + field)) continue;
            List<String> accessors = accessors(type.id(), field);
            Set<String> others = new TreeSet<>(anchors(type.id(), field));
            others.remove(id);
            List<String> said = new ArrayList<>();
            for (String a : accessors) {
                List<GraphEdge> calls = sorted(tools.directCalls(a));
                methods.add(a);
                said.add(GraphQuery.clean(graph.display(a)) + " " + count(calls.size(), "caller") + sites(calls));
            }
            lines.add("java side: " + GraphQuery.clean(graph.display(type.id())) + "." + field
                    + (said.isEmpty() ? "" : "; " + String.join("; ", said))
                    + (others.isEmpty()
                            ? ""
                            : "; it also maps to "
                                    + String.join(
                                            ", ",
                                            others.stream()
                                                    .map(GraphQuery::clean)
                                                    .toList()))
                    + ": callers '" + GraphQuery.clean(graph.display(type.id())) + "." + field + "' lists them all.");
        }
        for (GraphEdge e : graph.incident(id)) {
            GraphNode from = graph.node(e.source());
            if (e.target().equals(id)
                    && e.relation().equals(Relations.DEFINES)
                    && from != null
                    && from.label().endsWith("()")) methods.add(e.source());
        }
        tests(new ArrayList<>(methods), anchors, lines);
        return lines;
    }

    /**
     * One line per anchor {@code typeId} declares or maps to (its table, its fields' columns and
     * properties, its methods' routes), with counts and the first places; at most {@link #TYPE_ANCHORS}.
     */
    List<String> type(String typeId) {
        Map<String, String> found = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>(List.of(typeId));
        for (GraphEdge e : graph.incident(typeId)) {
            if (e.source().equals(typeId) && e.relation().equals(Relations.METHOD)) sources.add(e.target());
        }
        for (String s : sources) {
            for (GraphEdge e : graph.incident(s)) {
                if (!e.source().equals(s) || !Anchors.is(graph.node(e.target()))) continue;
                String via =
                        e.context() == null ? s.equals(typeId) ? "" : GraphQuery.clean(graph.display(s)) : e.context();
                found.putIfAbsent(e.target(), via);
            }
        }
        if (found.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add(
                "anchors it maps to (" + found.size() + "; places no compiler checks, callers '<anchor>' lists each):");
        int shown = 0;
        for (Map.Entry<String, String> a : new TreeMap<>(found).entrySet()) {
            if (shown++ == TYPE_ANCHORS) break;
            List<String> places = new ArrayList<>();
            int defs = 0;
            int uses = 0;
            for (GraphEdge e : graph.incident(a.getKey())) {
                if (!e.target().equals(a.getKey()) || e.source().equals(typeId) || sources.contains(e.source()))
                    continue;
                if (e.relation().equals(Relations.DEFINES)) defs++;
                else if (e.relation().equals(Relations.USES)) uses++;
                else continue;
                if (places.size() < 3) places.add(GraphTools.at(e).strip());
            }
            lines.add("  " + GraphQuery.clean(a.getKey())
                    + (a.getValue().isEmpty() ? "" : " (" + GraphQuery.clean(a.getValue()) + ")")
                    + ": "
                    + (defs == 0
                            ? count(uses, "use")
                            : count(defs, "definition") + (uses == 0 ? "" : ", " + count(uses, "use")))
                    + (places.isEmpty()
                            ? ""
                            : "  "
                                    + String.join(
                                            ", ",
                                            places.stream()
                                                    .filter(p -> !p.isEmpty())
                                                    .toList())));
        }
        if (found.size() > TYPE_ANCHORS) {
            GraphNode type = graph.node(typeId);
            String file = type == null ? "" : GraphQuery.clean(type.sourceFile());
            lines.add("  +" + (found.size() - TYPE_ANCHORS) + " more: callers 'file:" + file + "' lists every one.");
        }
        return lines;
    }

    /**
     * {@code " (Tests.java:80,299; Other.java:12)"}: the calls' files by name, their lines after each, at
     * most four; empty for none.
     */
    private static String sites(List<GraphEdge> calls) {
        if (calls.isEmpty()) return "";
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        for (GraphEdge e : calls.subList(0, Math.min(calls.size(), 4))) {
            String rel = GraphQuery.clean(e.sourceFile());
            byFile.computeIfAbsent(rel.substring(rel.lastIndexOf('/') + 1), k -> new ArrayList<>())
                    .add(String.valueOf(GraphTools.lineNumber(e.sourceLocation())));
        }
        List<String> out = new ArrayList<>();
        byFile.forEach((file, at) -> out.add(file + ":" + String.join(",", at)));
        return " (" + String.join("; ", out) + (calls.size() > 4 ? "; …" : "") + ")";
    }

    /** {@code OwnerTests} for {@code src/test/java/…/OwnerTests.java}. */
    private static String stem(String rel) {
        String name = GraphQuery.clean(rel.substring(rel.lastIndexOf('/') + 1));
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean test(String rel) {
        return graph.modules().sourceSet(rel) == Modules.SourceSet.TEST;
    }

    private String label(String id) {
        GraphNode n = graph.node(id);
        return n == null ? id : n.label();
    }

    /** {@code telephone} for {@code .getTelephone()}, {@code .setTelephone()} or {@code .isTelephone()}; else null. */
    static @Nullable String property(String label) {
        String name = bare(label);
        for (String prefix : List.of("get", "set", "is")) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length())) + name.substring(prefix.length() + 1);
            }
        }
        return null;
    }

    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }

    private static String count(int n, String noun) {
        return count(n, noun, noun + "s");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
