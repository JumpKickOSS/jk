// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.host.CodeText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * JSON property anchors: {@code json:<Class>.<property>}, one per property a route's body carries,
 * by the name Jackson writes ({@link JsonDtos}), defined at the Java member that declares it. A
 * route's body is what its handler returns and reads as its request body, and every class reachable
 * from those through properties and supertypes.
 *
 * <p>A bare property name ({@code id}, {@code name}) says nothing about which class it belongs to,
 * so a client's mention is linked only where it is tied to a route or a class: a read, destructured
 * name or object key in the same function as a request to that route ({@link Confidence#INFERRED},
 * a place that must change), or elsewhere in a file that requests it ({@link Confidence#AMBIGUOUS},
 * a judgment call); a jq field on the pipeline that requests the route, or elsewhere in its script;
 * a JSONPath in the test method that calls it; a TypeScript interface, type or class named for the
 * class ({@code Owner}, {@code OwnerDto}, {@code IOwner}); an OpenAPI schema named for it or under the
 * route's path; the keys of a JSON fixture a file requesting the route names. A name several of the
 * route's classes carry is a judgment call wherever it is tied. Nothing else is linked, and a file
 * a generator writes (by its header, its path, or a build file naming it as a generator's output)
 * is never read. A workspace with no route whose handler returns or reads a class of its own, or with
 * no client file tied to one, gets no anchor of this kind and pays for nothing but finding that out.
 */
final class JsonAnchors {

    private JsonAnchors() {}

    /** Most classes followed from one workspace's routes: a body graph past this is a data model, not a DTO. */
    static final int MAX_CLASSES = 400;

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?!static)([\\w.]+?)(\\.\\*)?\\s*;");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern GENERATED_PATH =
            Pattern.compile("(^|/)(generated|generated-sources|gen|__generated__)/");
    private static final Pattern SCRIPT = Pattern.compile(".*\\.(ts|tsx|js|jsx|mjs|cjs|mts|cts|vue|svelte)$");
    private static final Pattern SHELL =
            Pattern.compile(".*(\\.(sh|bash|zsh|ya?ml|mk)|/Makefile|^Makefile|Justfile|justfile)$");
    private static final Pattern JVM = Pattern.compile(".*\\.(java|kt|groovy|scala)$");
    private static final Pattern OPENAPI_NAME = Pattern.compile(
            "(?i)(^|/)(openapi|swagger|api-docs?)(/|[^/]*\\.(ya?ml|json)$)|(^|/)[^/]*(openapi|swagger)[^/]*\\.(ya?ml|json)$");
    private static final Pattern FIXTURE_DIR =
            Pattern.compile("(^|/)(test|tests|__tests__|__fixtures__|fixtures|testdata|test-data|e2e|cypress|it)/");
    private static final List<String> NAME_SUFFIXES = List.of(
            "Dto",
            "DTO",
            "Response",
            "Request",
            "Resource",
            "Model",
            "Json",
            "Data",
            "Payload",
            "Body",
            "View",
            "Entity");
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /** One class a route's body reaches: where it is declared and the properties it adds. */
    private record Body(String nodeId, String simple, String rel, JsonDtos.Dto dto) {}

    /** What one client place names, before it is graded. */
    private record Tie(String rel, int line, String name, Set<String> routes, boolean close) {}

    /**
     * {@code g} with the JSON property anchors its routes' bodies and their tied clients make; {@code
     * g} itself when there are none. {@code files} is every file of the tree and {@code text} reads one
     * as it is now ({@code null} when it is gone).
     */
    static CodeGraph add(CodeGraph g, Collection<String> files, Function<String, @Nullable String> text) {
        Map<String, List<GraphEdge>> handlers = handlers(g);
        if (handlers.isEmpty()) return g;
        Classes classes = new Classes(g, text);
        // Route -> property name -> the anchors of that name its body carries.
        Map<String, Map<String, Set<String>>> routeProps = new TreeMap<>();
        Map<String, Body> bodies = new LinkedHashMap<>();
        Map<String, JsonDtos.Property> defined = new LinkedHashMap<>();
        Map<String, String> definedBy = new HashMap<>();
        for (Map.Entry<String, List<GraphEdge>> h : handlers.entrySet()) {
            Set<String> roots = new LinkedHashSet<>();
            for (GraphEdge e : h.getValue()) {
                GraphNode method = g.node(e.source());
                if (method == null) continue;
                JsonDtos.Source src = classes.source(e.sourceFile());
                if (src == null) continue;
                for (String t : JsonDtos.bodyTypes(src, methodName(method.label()))) {
                    String q = classes.resolve(t, e.sourceFile());
                    if (q != null) roots.add(q);
                }
            }
            if (roots.isEmpty()) continue;
            Map<String, Set<String>> props = new TreeMap<>();
            Deque<String> todo = new ArrayDeque<>(roots);
            Set<String> seen = new LinkedHashSet<>();
            while (!todo.isEmpty() && bodies.size() <= MAX_CLASSES) {
                String q = todo.poll();
                if (!seen.add(q)) continue;
                Body b = bodies.get(q);
                if (b == null) {
                    b = classes.body(q);
                    if (b == null) continue;
                    bodies.put(q, b);
                }
                for (JsonDtos.Property p : b.dto().properties()) {
                    String id = Anchors.id(Anchors.JSON, b.simple() + "." + p.name());
                    props.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(id);
                    defined.putIfAbsent(id, p);
                    definedBy.putIfAbsent(id, q);
                    for (String t : p.types()) {
                        String r = classes.resolve(t, b.rel());
                        if (r != null && !seen.contains(r)) todo.add(r);
                    }
                }
                String sup = b.dto().supertype();
                String r = sup == null ? null : classes.resolve(sup, b.rel());
                if (r != null && !seen.contains(r)) todo.add(r);
            }
            if (!props.isEmpty()) routeProps.put(h.getKey(), props);
        }
        if (routeProps.isEmpty()) return g;

        Generated generated = new Generated(files, text);
        Map<String, Map<String, GraphEdge>> uses = new TreeMap<>();
        for (Tie t : routeTies(g, routeProps.keySet(), files, text, generated)) {
            Set<String> ids = new TreeSet<>();
            for (String route : t.routes())
                ids.addAll(routeProps.getOrDefault(route, Map.of()).getOrDefault(t.name(), Set.of()));
            if (ids.isEmpty()) continue;
            boolean sure = t.close() && ids.size() == 1;
            for (String id : ids) use(uses, g, t.rel(), id, t.line(), sure);
        }
        typed(g, files, text, generated, bodies, routeProps, uses);
        if (uses.isEmpty()) return g;

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (Map.Entry<String, JsonDtos.Property> d : defined.entrySet()) {
            Body b = bodies.get(definedBy.get(d.getKey()));
            if (b == null) continue;
            String name = d.getKey().substring(Anchors.JSON.length() + 1);
            nodes.add(Anchors.node(Anchors.JSON, name));
            edges.add(GraphEdge.of(
                    b.nodeId(),
                    d.getKey(),
                    Relations.DEFINES,
                    Confidence.EXTRACTED,
                    b.rel(),
                    d.getValue().line()));
        }
        for (Map.Entry<String, Map<String, GraphEdge>> f : uses.entrySet()) {
            String fileId = GraphIds.file(f.getKey());
            if (!g.has(fileId)) {
                String rel = f.getKey();
                nodes.add(new GraphNode(fileId, rel.substring(rel.lastIndexOf('/') + 1), "code", rel, ""));
            }
            edges.addAll(f.getValue().values());
        }
        Fragment linked = new Fragment(new ArrayList<>(g.nodes()), g.edges());
        CodeGraph out = CodeGraph.merge(List.of(linked, new Fragment(nodes, edges)));
        out.modules(g.modules());
        out.stale(g.stale());
        return out;
    }

    /** {@code get} for a method node's label {@code .get()}. */
    static String methodName(String label) {
        String n = label.startsWith(".") ? label.substring(1) : label;
        int paren = n.indexOf('(');
        return paren < 0 ? n : n.substring(0, paren);
    }

    /** Each route anchor a Java handler defines, with its defining edges. */
    private static Map<String, List<GraphEdge>> handlers(CodeGraph g) {
        Map<String, List<GraphEdge>> out = new TreeMap<>();
        for (GraphNode n : g.nodes()) {
            if (!Anchors.is(n) || !Anchors.ROUTE.equals(Anchors.kindOf(n.id()))) continue;
            for (GraphEdge e : g.incident(n.id())) {
                if (e.target().equals(n.id())
                        && e.relation().equals(Relations.DEFINES)
                        && e.sourceFile().endsWith(".java")) {
                    out.computeIfAbsent(n.id(), k -> new ArrayList<>()).add(e);
                }
            }
        }
        return out;
    }

    /** One use edge from {@code rel}'s file to anchor {@code id}; a sure line outranks a judgment call in one file. */
    private static void use(
            Map<String, Map<String, GraphEdge>> uses, CodeGraph g, String rel, String id, int line, boolean sure) {
        Confidence c = sure ? Confidence.INFERRED : Confidence.AMBIGUOUS;
        GraphEdge e = GraphEdge.of(GraphIds.file(rel), id, Relations.USES, c, rel, line);
        Map<String, GraphEdge> byId = uses.computeIfAbsent(rel, k -> new TreeMap<>());
        GraphEdge had = byId.get(id);
        if (had == null || had.confidence() == Confidence.AMBIGUOUS && sure) byId.put(id, e);
        else if (had.confidence() == c) byId.put(id, had.withSitesOf(e));
    }

    /**
     * The client places tied to a route: the files that request one (an edge into its anchor), read
     * by their kind, each name graded close when it sits in the request's function, pipeline or test
     * method; and the JSON fixtures such a file names.
     */
    private static List<Tie> routeTies(
            CodeGraph g,
            Set<String> routes,
            Collection<String> files,
            Function<String, @Nullable String> text,
            Generated generated) {
        Map<String, Map<String, List<Integer>>> byFile = new TreeMap<>();
        for (String route : routes) {
            for (GraphEdge e : g.incident(route)) {
                if (!e.target().equals(route)
                        || !e.relation().equals(Relations.USES)
                        || e.sourceFile().isEmpty()) continue;
                List<Integer> lines = byFile.computeIfAbsent(e.sourceFile(), k -> new TreeMap<>())
                        .computeIfAbsent(route, k -> new ArrayList<>());
                for (String site : e.sites().isEmpty() ? List.of(e.sourceLocation()) : e.sites()) {
                    int l = line(site);
                    if (l > 0) lines.add(l);
                }
            }
        }
        List<Tie> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Integer>>> f : byFile.entrySet()) {
            String rel = f.getKey();
            if (generated.is(rel)) continue;
            String body = text.apply(rel);
            if (body == null) continue;
            Map<String, List<Integer>> requested = f.getValue();
            if (SCRIPT.matcher(rel).matches()) {
                JsonReaders.Blocks blocks =
                        JsonReaders.functions(CodeText.blank(body, CodeText.Blank.COMMENTS_AND_STRINGS, true));
                ties(rel, JsonReaders.scriptUses(body), requested, blocks, JsonReaders.lineStarts(body), body, out);
            } else if (JVM.matcher(rel).matches()) {
                JsonReaders.Blocks blocks =
                        JsonReaders.functions(CodeText.blank(body, CodeText.Blank.COMMENTS_AND_STRINGS));
                ties(rel, JsonReaders.jsonPathUses(body), requested, blocks, JsonReaders.lineStarts(body), body, out);
            }
            if (SHELL.matcher(rel).matches() || SCRIPT.matcher(rel).matches()) {
                for (JsonReaders.Use u : JsonReaders.jqUses(body)) {
                    Set<String> close = new TreeSet<>();
                    requested.forEach((route, lines) -> {
                        for (int l : lines) if (l >= u.block() && l <= u.line()) close.add(route);
                    });
                    out.add(new Tie(
                            rel, u.block(), u.name(), close.isEmpty() ? requested.keySet() : close, !close.isEmpty()));
                }
            }
        }
        fixtures(files, text, generated, byFile, out);
        return out;
    }

    /** The uses of one C-family file, each close when a request of the route sits in its function. */
    private static void ties(
            String rel,
            List<JsonReaders.Use> uses,
            Map<String, List<Integer>> requested,
            JsonReaders.Blocks blocks,
            int[] starts,
            String body,
            List<Tie> out) {
        Map<String, Set<Integer>> blocksOf = new TreeMap<>();
        requested.forEach((route, lines) -> {
            for (int l : lines) {
                if (l > starts.length) continue;
                int at = starts[l - 1];
                while (at < body.length() && Character.isWhitespace(body.charAt(at)) && body.charAt(at) != '\n') at++;
                blocksOf.computeIfAbsent(route, k -> new TreeSet<>()).add(blocks.at(at));
            }
        });
        for (JsonReaders.Use u : uses) {
            Set<String> close = new TreeSet<>();
            if (u.block() >= 0)
                blocksOf.forEach((route, b) -> {
                    if (b.contains(u.block())) close.add(route);
                });
            out.add(new Tie(rel, u.line(), u.name(), close.isEmpty() ? requested.keySet() : close, !close.isEmpty()));
        }
    }

    /**
     * The keys of each JSON fixture (a {@code .json} under a test or fixtures directory) that a file
     * requesting a route names by its file name: tied to those routes, every key a judgment call unless
     * one class of the route carries it.
     */
    private static void fixtures(
            Collection<String> files,
            Function<String, @Nullable String> text,
            Generated generated,
            Map<String, Map<String, List<Integer>>> requesting,
            List<Tie> out) {
        List<String> fixtures = new ArrayList<>();
        for (String rel : files) {
            if (rel.endsWith(".json")
                    && FIXTURE_DIR.matcher(rel).find()
                    && !OPENAPI_NAME.matcher(rel).find()
                    && !generated.is(rel)) fixtures.add(rel);
        }
        if (fixtures.isEmpty()) return;
        Map<String, Integer> names = new HashMap<>();
        for (String f : fixtures) names.merge(f.substring(f.lastIndexOf('/') + 1), 1, Integer::sum);
        for (String f : fixtures) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            int slash = f.lastIndexOf('/');
            int parentSlash = slash <= 0 ? -1 : f.lastIndexOf('/', slash - 1);
            // A name two fixtures share is matched with its directory.
            String needle = names.getOrDefault(name, 1) > 1 ? f.substring(parentSlash + 1) : name;
            Set<String> routes = new TreeSet<>();
            requesting.forEach((rel, byRoute) -> {
                String body = text.apply(rel);
                if (body != null && body.contains(needle)) routes.addAll(byRoute.keySet());
            });
            if (routes.isEmpty()) continue;
            String body = text.apply(f);
            if (body == null) continue;
            for (JsonReaders.Key k : JsonReaders.jsonKeys(body)) out.add(new Tie(f, k.line(), k.name(), routes, true));
        }
    }

    /**
     * Typed places: a TypeScript interface, type or class named for one of the bodies' classes, and an
     * OpenAPI schema named for one or used under a route's path.
     */
    private static void typed(
            CodeGraph g,
            Collection<String> files,
            Function<String, @Nullable String> text,
            Generated generated,
            Map<String, Body> bodies,
            Map<String, Map<String, Set<String>>> routeProps,
            Map<String, Map<String, GraphEdge>> uses) {
        Map<String, List<Body>> byName = new HashMap<>();
        for (Body b : bodies.values())
            byName.computeIfAbsent(stem(b.simple()), k -> new ArrayList<>()).add(b);
        for (String rel : files) {
            boolean script = SCRIPT.matcher(rel).matches() || rel.endsWith(".d.ts");
            boolean openapi = !script && OPENAPI_NAME.matcher(rel).find();
            if (!script && !openapi || generated.is(rel)) continue;
            String body = text.apply(rel);
            if (body == null || generated.header(body)) continue;
            if (script) {
                for (JsonReaders.Member m : JsonReaders.scriptTypes(body)) member(g, rel, m, byName, uses);
            } else {
                openApi(g, rel, body, byName, routeProps, uses);
            }
        }
    }

    /** A member of a type named for some of the bodies' classes, linked to theirs of its name. */
    private static void member(
            CodeGraph g,
            String rel,
            JsonReaders.Member m,
            Map<String, List<Body>> byName,
            Map<String, Map<String, GraphEdge>> uses) {
        List<Body> named = byName.getOrDefault(stem(m.type()), List.of());
        List<String> ids = new ArrayList<>();
        for (Body b : named) {
            for (JsonDtos.Property p : b.dto().properties()) {
                if (p.name().equals(m.name())) ids.add(Anchors.id(Anchors.JSON, b.simple() + "." + p.name()));
            }
        }
        for (String id : ids) use(uses, g, rel, id, m.line(), ids.size() == 1);
    }

    /**
     * An OpenAPI document's places: the properties of a schema named for a body's class, and of a
     * schema (inline, or by {@code $ref}) under a path and method one of the routes answers.
     */
    private static void openApi(
            CodeGraph g,
            String rel,
            String body,
            Map<String, List<Body>> byName,
            Map<String, Map<String, Set<String>>> routeProps,
            Map<String, Map<String, GraphEdge>> uses) {
        boolean json = rel.toLowerCase(Locale.ROOT).endsWith(".json");
        List<JsonReaders.Key> keys = json ? JsonReaders.jsonKeys(body) : JsonReaders.yamlKeys(body);
        boolean marked = false;
        String base = "";
        for (JsonReaders.Key k : keys) {
            if (k.path().isEmpty() && (k.name().equals("openapi") || k.name().equals("swagger"))) marked = true;
            if (!json && k.path().equals(List.of("servers")) && k.name().equals("url"))
                base = basePath(JsonReaders.yamlValue(body, k.line()));
            if (!json && k.path().isEmpty() && k.name().equals("basePath"))
                base = basePath(JsonReaders.yamlValue(body, k.line()));
        }
        if (!marked) return;
        // Schema name -> the routes whose operations use it.
        Map<String, Set<String>> schemaRoutes = new HashMap<>();
        for (JsonReaders.Key k : keys) {
            if (!k.name().equals("$ref")
                    || k.path().size() < 3
                    || !k.path().getFirst().equals("paths")) continue;
            String ref = json ? jsonValue(body, k.line(), "$ref") : JsonReaders.yamlValue(body, k.line());
            String schema = ref.substring(ref.lastIndexOf('/') + 1);
            for (String route : routesOf(k.path(), base, routeProps.keySet()))
                schemaRoutes.computeIfAbsent(schema, s -> new TreeSet<>()).add(route);
        }
        for (JsonReaders.Key k : keys) {
            List<String> p = k.path();
            int props = p.lastIndexOf("properties");
            if (props < 0 || props != p.size() - 1) continue;
            String schema = schemaOf(p);
            Set<String> ids = new TreeSet<>();
            boolean sure = false;
            if (schema != null) {
                for (Body b : byName.getOrDefault(stem(schema), List.of())) {
                    for (JsonDtos.Property pr : b.dto().properties()) {
                        if (pr.name().equals(k.name())) ids.add(Anchors.id(Anchors.JSON, b.simple() + "." + pr.name()));
                    }
                }
                sure = ids.size() == 1;
                if (ids.isEmpty()) {
                    for (String route : schemaRoutes.getOrDefault(schema, Set.of())) {
                        ids.addAll(routeProps.getOrDefault(route, Map.of()).getOrDefault(k.name(), Set.of()));
                    }
                    sure = ids.size() == 1;
                }
            } else if (!p.isEmpty() && p.getFirst().equals("paths")) {
                for (String route : routesOf(p, base, routeProps.keySet())) {
                    ids.addAll(routeProps.getOrDefault(route, Map.of()).getOrDefault(k.name(), Set.of()));
                }
                sure = ids.size() == 1;
            }
            for (String id : ids) use(uses, g, rel, id, k.line(), sure);
        }
    }

    /** {@code Owner} for {@code components.schemas.Owner.properties} or {@code definitions.Owner.properties}; null otherwise. */
    private static @Nullable String schemaOf(List<String> path) {
        if (path.size() >= 4 && path.get(0).equals("components") && path.get(1).equals("schemas")) return path.get(2);
        if (path.size() >= 3 && path.get(0).equals("definitions")) return path.get(1);
        return null;
    }

    /** The routes an operation at {@code path} ({@code paths./owners/{id}.get…}) answers, its path under {@code base}. */
    private static Set<String> routesOf(List<String> path, String base, Set<String> routes) {
        Set<String> out = new TreeSet<>();
        if (path.size() < 3 || !path.getFirst().equals("paths")) return out;
        String method = path.get(2).toLowerCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(method)) return out;
        for (String prefix : base.isEmpty() ? List.of("") : List.of("", base)) {
            String id = Anchors.id(Anchors.ROUTE, method.toUpperCase(Locale.ROOT) + " " + prefix + path.get(1));
            String any = Anchors.id(Anchors.ROUTE, "* " + prefix + path.get(1));
            if (routes.contains(id)) out.add(id);
            if (routes.contains(any)) out.add(any);
        }
        return out;
    }

    private static String basePath(String url) {
        String u = url.replaceFirst("^[a-z]+://[^/]*", "");
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.startsWith("/") ? u : "";
    }

    private static String jsonValue(String body, int line, String key) {
        String[] lines = body.split("\n", -1);
        if (line < 1 || line > lines.length) return "";
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(lines[line - 1]);
        return m.find() ? m.group(1) : "";
    }

    /** {@code Owner} for {@code OwnerDto}, {@code IOwner}, {@code OwnerResponse}: the name a class and its client types share. */
    static String stem(String name) {
        String n = name;
        if (n.length() > 2
                && n.charAt(0) == 'I'
                && Character.isUpperCase(n.charAt(1))
                && Character.isLowerCase(n.charAt(2))) {
            n = n.substring(1);
        }
        for (String s : NAME_SUFFIXES) {
            if (n.length() > s.length() && n.endsWith(s)) return n.substring(0, n.length() - s.length());
        }
        return n;
    }

    private static int line(String site) {
        try {
            return site.startsWith("L") ? Integer.parseInt(site.substring(1).replaceFirst("-.*$", "")) : 0;
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    /** The workspace's Java classes by name, and their sources read once. */
    private static final class Classes {
        final Map<String, GraphNode> byQualified = new HashMap<>();
        final Map<String, List<String>> bySimple = new HashMap<>();
        final Map<String, JsonDtos.Source> sources = new HashMap<>();
        final Set<String> unreadable = new HashSet<>();
        final Function<String, @Nullable String> text;

        Classes(CodeGraph g, Function<String, @Nullable String> text) {
            this.text = text;
            for (GraphNode n : g.nodes()) {
                if (!(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)
                        || !n.sourceFile().endsWith(".java")) continue;
                String dotted = q.replace('$', '.');
                byQualified.putIfAbsent(dotted, n);
                bySimple.computeIfAbsent(dotted.substring(dotted.lastIndexOf('.') + 1), k -> new ArrayList<>())
                        .add(dotted);
            }
        }

        JsonDtos.@Nullable Source source(String rel) {
            JsonDtos.Source had = sources.get(rel);
            if (had != null || unreadable.contains(rel)) return had;
            String t = text.apply(rel);
            if (t == null) {
                unreadable.add(rel);
                return null;
            }
            JsonDtos.Source s = JsonDtos.Source.of(t);
            sources.put(rel, s);
            return s;
        }

        /** The workspace class {@code name} means in {@code rel}; null for a library's, or one it cannot place. */
        @Nullable
        String resolve(String name, String rel) {
            if (byQualified.containsKey(name)) return name;
            JsonDtos.Source src = source(rel);
            String code = src == null ? "" : src.code();
            String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            String rest = name.contains(".") ? name.substring(name.indexOf('.')) : "";
            Matcher imp = IMPORT.matcher(code);
            List<String> wildcards = new ArrayList<>();
            while (imp.find()) {
                if (imp.group(2) != null) wildcards.add(imp.group(1));
                else if (imp.group(1).endsWith("." + first)) {
                    String q = imp.group(1) + rest;
                    return byQualified.containsKey(q) ? q : null;
                }
            }
            Matcher pkg = PACKAGE.matcher(code);
            String p = pkg.find() ? pkg.group(1) + "." : "";
            if (byQualified.containsKey(p + name)) return p + name;
            // A class nested in one this file declares.
            for (String q : bySimple.getOrDefault(name.substring(name.lastIndexOf('.') + 1), List.of())) {
                GraphNode n = byQualified.get(q);
                if (n != null && n.sourceFile().equals(rel) && q.endsWith("." + name)) return q;
            }
            for (String w : wildcards) if (byQualified.containsKey(w + "." + name)) return w + "." + name;
            List<String> same = bySimple.getOrDefault(name, List.of());
            return same.size() == 1 && !name.contains(".") && !isJavaLang(name) ? same.getFirst() : null;
        }

        private static boolean isJavaLang(String name) {
            return Set.of(
                            "String",
                            "Object",
                            "Integer",
                            "Long",
                            "Boolean",
                            "Double",
                            "Float",
                            "Short",
                            "Byte",
                            "Character")
                    .contains(name);
        }

        @Nullable
        Body body(String qualified) {
            GraphNode n = byQualified.get(qualified);
            if (n == null) return null;
            JsonDtos.Source src = source(n.sourceFile());
            if (src == null) return null;
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            JsonDtos.Dto dto = JsonDtos.dto(src, simple);
            return dto == null ? null : new Body(n.id(), simple, n.sourceFile(), dto);
        }
    }

    /**
     * Files a tool writes: under a {@code generated} directory, headed as generated, or named as an
     * output by a build file's OpenAPI or client generator.
     */
    private static final class Generated {
        final List<String> outputs = new ArrayList<>();
        final Function<String, @Nullable String> text;
        final Map<String, Boolean> headers = new HashMap<>();

        Generated(Collection<String> files, Function<String, @Nullable String> text) {
            this.text = text;
            for (String rel : files) {
                String name = rel.substring(rel.lastIndexOf('/') + 1);
                if (!name.equals("pom.xml") && !name.startsWith("build.gradle") && !name.equals("package.json"))
                    continue;
                String body = text.apply(rel);
                if (body == null) continue;
                String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
                outputs.addAll(JsonReaders.generatorOutputs(name, dir, body));
            }
        }

        boolean is(String rel) {
            if (GENERATED_PATH.matcher(rel).find() || JsonReaders.generatedBy(rel, outputs)) return true;
            Boolean had = headers.get(rel);
            if (had != null) return had;
            String body = text.apply(rel);
            boolean headed = body != null && JsonReaders.generatedHeader(body);
            headers.put(rel, headed);
            return headed;
        }

        boolean header(String body) {
            return JsonReaders.generatedHeader(body);
        }
    }
}
