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
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Optional;
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

    private static final Pattern GENERATED_PATH =
            Pattern.compile("(^|/)(generated|generated-sources|gen|__generated__)/");
    static final Pattern SCRIPT = Pattern.compile(".*\\.(ts|tsx|js|jsx|mjs|cjs|mts|cts|vue|svelte)$");
    static final Pattern SHELL =
            Pattern.compile(".*(\\.(sh|bash|zsh|ya?ml|mk)|/Makefile|^Makefile|Justfile|justfile)$");
    static final Pattern JVM = Pattern.compile(".*\\.(java|kt|groovy|scala)$");
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

    /**
     * One class a route's body reaches: its internal and dotted names, its anchor key (its simple name,
     * or its dotted name where two classes the bodies reach share the simple one), the node and file
     * that declare it, and the properties it adds.
     */
    private record Body(
            String internal, String dotted, String key, String nodeId, String rel, int line, JsonDtos.Dto dto) {

        String simple() {
            return dotted.substring(dotted.lastIndexOf('.') + 1);
        }
    }

    /** A source with its comments and literals blanked, and its line starts. */
    private record Masked(String masked, int[] starts) {
        static Masked of(@Nullable String source) {
            String s = source == null ? "" : source;
            return new Masked(CodeText.blank(s, CodeText.Blank.COMMENTS_AND_STRINGS), JsonReaders.lineStarts(s));
        }
    }

    /** What one client place names, before it is graded. */
    private record Tie(String rel, int line, String name, Set<String> routes, boolean close) {}

    /**
     * {@code g} with the JSON property anchors its routes' bodies and their tied clients make; {@code
     * g} itself when there are none. {@code files} is every file of the tree, {@code text} reads one
     * as it is now ({@code null} when it is gone) and {@code compiled} reads the workspace's classes.
     */
    static CodeGraph add(
            CodeGraph g,
            Collection<String> files,
            Function<String, @Nullable String> text,
            Classfiles compiled,
            JsonScriptMemo scripts) {
        Map<String, List<GraphEdge>> handlers = handlers(g);
        if (handlers.isEmpty()) return g;
        Classes classes = new Classes(g, compiled);
        // Route -> the classes its bodies reach, then each class as Jackson writes it.
        Map<String, Set<String>> routeClasses = new TreeMap<>();
        Map<String, JsonDtos.Dto> dtos = new LinkedHashMap<>();
        for (Map.Entry<String, List<GraphEdge>> h : handlers.entrySet()) {
            Set<String> roots = new LinkedHashSet<>();
            for (GraphEdge e : h.getValue()) {
                GraphNode type = g.owner(e.source());
                GraphNode method = g.node(e.source());
                ClassModel owner = type == null ? null : classes.model(type);
                if (owner == null || method == null) continue;
                for (String t : JsonDtos.bodyTypes(owner, methodName(method.label()))) {
                    if (classes.declared(t)) roots.add(t);
                }
            }
            if (roots.isEmpty()) continue;
            Deque<String> todo = new ArrayDeque<>(roots);
            Set<String> seen = new LinkedHashSet<>();
            while (!todo.isEmpty() && dtos.size() <= MAX_CLASSES) {
                String q = todo.poll();
                if (!seen.add(q)) continue;
                JsonDtos.Dto dto = dtos.containsKey(q) ? dtos.get(q) : classes.dto(q);
                if (dto == null) continue;
                dtos.put(q, dto);
                for (JsonDtos.Property p : dto.properties()) {
                    for (String t : p.types()) if (classes.declared(t) && !seen.contains(t)) todo.add(t);
                }
                for (String t : dto.supertypes()) if (classes.declared(t) && !seen.contains(t)) todo.add(t);
            }
            seen.retainAll(dtos.keySet());
            if (!seen.isEmpty()) routeClasses.put(h.getKey(), seen);
        }
        if (routeClasses.isEmpty()) return g;
        Map<String, Body> bodies = classes.bodies(dtos);
        Map<String, Map<String, Set<String>>> routeProps = new TreeMap<>();
        routeClasses.forEach((route, reached) -> {
            Map<String, Set<String>> props = new TreeMap<>();
            for (String q : reached) {
                Body b = bodies.get(q);
                if (b == null) continue;
                for (JsonDtos.Property p : b.dto().properties())
                    props.computeIfAbsent(p.name(), k -> new TreeSet<>()).add(id(b, p));
            }
            if (!props.isEmpty()) routeProps.put(route, props);
        });
        if (routeProps.isEmpty()) return g;

        Generated generated = new Generated(files, text);
        Map<String, Map<String, GraphEdge>> uses = new TreeMap<>();
        for (Tie t : routeTies(g, routeProps.keySet(), files, text, generated, scripts)) {
            Set<String> ids = new TreeSet<>();
            for (String route : t.routes())
                ids.addAll(routeProps.getOrDefault(route, Map.of()).getOrDefault(t.name(), Set.of()));
            if (ids.isEmpty()) continue;
            boolean sure = t.close() && ids.size() == 1;
            for (String id : ids) use(uses, g, t.rel(), id, t.line(), sure);
        }
        typed(g, files, text, generated, scripts, bodies, routeProps, uses);
        if (uses.isEmpty()) return g;

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, Masked> sources = new HashMap<>();
        for (Body b : bodies.values()) {
            Masked src = sources.computeIfAbsent(b.rel(), rel -> Masked.of(text.apply(rel)));
            for (JsonDtos.Property p : b.dto().properties()) {
                String name = b.key() + "." + p.name();
                nodes.add(Anchors.node(Anchors.JSON, name));
                int line = declarationLine(src.masked(), src.starts(), p.member(), !p.field(), b.line());
                edges.add(GraphEdge.of(
                        b.nodeId(),
                        Anchors.id(Anchors.JSON, name),
                        Relations.DEFINES,
                        Confidence.EXTRACTED,
                        b.rel(),
                        line));
            }
        }
        for (Map.Entry<String, Map<String, GraphEdge>> f : uses.entrySet()) {
            String fileId = GraphIds.file(f.getKey());
            if (!g.has(fileId)) {
                String rel = f.getKey();
                nodes.add(new GraphNode(fileId, rel.substring(rel.lastIndexOf('/') + 1), "code", rel, ""));
            }
            edges.addAll(f.getValue().values());
        }
        return CodeGraph.plus(g, new Fragment(nodes, edges));
    }

    private static String id(Body b, JsonDtos.Property p) {
        return Anchors.id(Anchors.JSON, b.key() + "." + p.name());
    }

    private static final Pattern DECLARATION = Pattern.compile("(?:class|record|interface|enum|object|trait)\\s+\\w");
    private static final Pattern MEMBER_END = Pattern.compile("[ \\t]*(?:[;=,:)}]|\\r?\\n|$)");

    /**
     * The line declaring {@code member} in a class's source: the first place at or after the class's
     * line {@code from} where it stands as a declaration directly in the class's body ({@code
     * telephone;}, {@code val telephone =}, or a {@code method}'s {@code getTelephone(}) or in the parentheses of
     * its header (a record's components, a Kotlin or Scala primary constructor), comments and
     * literals aside; {@code from} when there is none. Only the line is read from source; what the
     * property is comes from the class file.
     */
    static int declarationLine(String source, String member, boolean method, int from) {
        return declarationLine(
                CodeText.blank(source, CodeText.Blank.COMMENTS_AND_STRINGS),
                JsonReaders.lineStarts(source),
                member,
                method,
                from);
    }

    /** {@link #declarationLine(String, String, boolean, int)} on a source already {@code masked}, its line starts {@code starts}. */
    static int declarationLine(String masked, int[] starts, String member, boolean method, int from) {
        int start = from >= 1 && from <= starts.length ? starts[from - 1] : 0;
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(member) + "(?![\\w$])")
                .matcher(masked);
        m.region(start, masked.length());
        int at = start;
        int parens = 0;
        int braces = 0;
        boolean opened = false;
        boolean own = false;
        while (m.find()) {
            for (; at < m.start(); at++) {
                char c = masked.charAt(at);
                // The next declaration outside this class's braces ends the search: its members are not this class's.
                if (braces == 0 && parens == 0 && (at == 0 || !Character.isJavaIdentifierPart(masked.charAt(at - 1)))) {
                    Matcher d = DECLARATION.matcher(masked);
                    d.region(at, masked.length());
                    if (d.lookingAt()) {
                        if (own) return Math.max(1, from);
                        own = true;
                    }
                }
                if (c == '(') parens++;
                else if (c == ')') parens = Math.max(0, parens - 1);
                else if (c == '{') {
                    braces++;
                    opened = true;
                } else if (c == '}') {
                    if (--braces <= 0 && opened) return Math.max(1, from);
                }
            }
            boolean body = braces == 1 && parens == 0;
            boolean header = braces == 0 && parens == 1 && !opened;
            if (!body && !(header && !method)) continue;
            boolean ok;
            if (method) {
                int i = m.end();
                while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) i++;
                ok = i < masked.length() && masked.charAt(i) == '(';
            } else {
                Matcher end = MEMBER_END.matcher(masked);
                end.region(m.end(), masked.length());
                ok = end.lookingAt();
            }
            if (ok) return JsonReaders.line(starts, m.start());
        }
        return Math.max(1, from);
    }

    /** {@code get} for a method node's label {@code .get()}. */
    static String methodName(String label) {
        String n = label.startsWith(".") ? label.substring(1) : label;
        int paren = n.indexOf('(');
        return paren < 0 ? n : n.substring(0, paren);
    }

    /** Each route anchor a Java handler defines, with its defining edges. */
    static Map<String, List<GraphEdge>> handlers(CodeGraph g) {
        Map<String, List<GraphEdge>> out = new TreeMap<>();
        for (GraphNode n : g.nodes()) {
            if (!Anchors.is(n) || !Anchors.ROUTE.equals(Anchors.kindOf(n.id()))) continue;
            for (GraphEdge e : g.incident(n.id())) {
                if (e.target().equals(n.id())
                        && e.relation().equals(Relations.DEFINES)
                        && JVM.matcher(e.sourceFile()).matches()) {
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
            Generated generated,
            JsonScriptMemo scripts) {
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
            JsonScriptMemo.Reads r = scripts.reads(rel, text, JsonScriptMemo::read);
            if (r == null) continue;
            Map<String, List<Integer>> requested = f.getValue();
            JsonReaders.Blocks blocks = r.blocks();
            if (blocks != null) ties(rel, r.uses(), requested, blocks, r.starts(), r.body(), out);
            if (!r.jq().isEmpty()) {
                for (JsonReaders.Use u : r.jq()) {
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

    /**
     * The uses of one C-family file, each close when it sits in the innermost function holding a
     * request of the route (or a function nested in it), or, for a key of a body, when it is sent
     * with that request; any other literal's key is never close.
     */
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
            // A literal's key is JSON only as the body a request sends or a value compared with one.
            if (u.role() == JsonReaders.Role.OTHER) {
                out.add(new Tie(rel, u.line(), u.name(), requested.keySet(), false));
                continue;
            }
            if (u.role() == JsonReaders.Role.BODY && u.tie() > 0)
                requested.forEach((route, lines) -> {
                    if (lines.contains(u.tie())) close.add(route);
                });
            if (close.isEmpty())
                blocksOf.forEach((route, bs) -> {
                    for (int b : bs) if (blocks.within(u.block(), b)) close.add(route);
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
            JsonScriptMemo scripts,
            Map<String, Body> bodies,
            Map<String, Map<String, Set<String>>> routeProps,
            Map<String, Map<String, GraphEdge>> uses) {
        Map<String, List<Body>> byName = new HashMap<>();
        for (Body b : bodies.values())
            byName.computeIfAbsent(stem(b.simple()), k -> new ArrayList<>()).add(b);
        for (String rel : files) {
            boolean script = SCRIPT.matcher(rel).matches() || rel.endsWith(".d.ts");
            boolean openapi = !script && OPENAPI_NAME.matcher(rel).find();
            if (!script && !openapi || generated.byPath(rel)) continue;
            if (script) {
                JsonScriptMemo.Facts f = scripts.of(rel, text);
                if (f != null) for (JsonReaders.Member m : f.types()) member(g, rel, m, byName, uses);
                continue;
            }
            String body = text.apply(rel);
            if (body == null || generated.header(body)) continue;
            openApi(g, rel, body, byName, routeProps, uses);
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
                if (p.name().equals(m.name())) ids.add(id(b, p));
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
                        if (pr.name().equals(k.name())) ids.add(id(b, pr));
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

    /** The first line of a node's or edge's location ({@code L12} or {@code L12-30}); 0 for none. */
    static int line(String site) {
        try {
            return site.startsWith("L") ? Integer.parseInt(site.substring(1).replaceFirst("-.*$", "")) : 0;
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    /**
     * The compiled classes of a workspace, read from its class directories ({@code target/classes},
     * {@code target/test-classes}, {@code target/guard/classes} of each module) once each; a class
     * whose source was edited since it was compiled is not read, since its file no longer says what
     * the source does.
     */
    static final class Classfiles {
        private final List<Path> dirs;
        private final Set<String> stale;
        private final Map<String, Optional<ClassModel>> read = new HashMap<>();

        Classfiles(List<Path> dirs, Set<String> stale) {
            this.dirs = List.copyOf(dirs);
            this.stale = Set.copyOf(stale);
        }

        /** The class directories of {@code indexes} under {@code root}, and the sources edited since. */
        static Classfiles of(Path root, List<GraphBuild.ClassIndex> indexes) {
            List<Path> dirs = new ArrayList<>();
            Set<String> stale = new HashSet<>();
            for (GraphBuild.ClassIndex c : indexes) {
                Path d = root.resolve(c.modulePrefix()).resolve(ClassSources.classDir(c.sourceSet()));
                if (!dirs.contains(d)) dirs.add(d);
                stale.addAll(c.stale());
            }
            return new Classfiles(dirs, stale);
        }

        boolean stale(String rel) {
            return stale.contains(rel);
        }

        /** The class {@code internal} names, from the first directory holding it; null for none or an unreadable one. */
        @Nullable
        ClassModel model(String internal) {
            return read.computeIfAbsent(internal, k -> {
                        for (Path d : dirs) {
                            Path f = d.resolve(k + ".class");
                            if (!Files.isRegularFile(f)) continue;
                            try {
                                return Optional.of(ClassFile.of().parse(Files.readAllBytes(f)));
                            } catch (IOException | RuntimeException unreadable) {
                                Log.debug("graph: class unreadable at " + f, unreadable);
                                return Optional.empty();
                            }
                        }
                        return Optional.empty();
                    })
                    .orElse(null);
        }
    }

    /** The workspace's JVM classes by name, as the graph declares them and as their class files read. */
    private static final class Classes {
        final Map<String, GraphNode> byDotted = new HashMap<>();
        final Classfiles compiled;

        Classes(CodeGraph g, Classfiles compiled) {
            this.compiled = compiled;
            for (GraphNode n : g.nodes()) {
                if (!(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)
                        || !JVM.matcher(n.sourceFile()).matches()) continue;
                byDotted.putIfAbsent(q.replace('$', '.'), n);
            }
        }

        private static String dotted(String internal) {
            return internal.replace('/', '.').replace('$', '.');
        }

        /** Whether {@code internal} is a class the workspace declares (a library's is not followed). */
        boolean declared(String internal) {
            return byDotted.containsKey(dotted(internal));
        }

        /** The class file of the graph's type node {@code type}; null for a stale or missing one. */
        @Nullable
        ClassModel model(GraphNode type) {
            if (compiled.stale(type.sourceFile())) return null;
            if (!(type.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) return null;
            String internal = q.replace('.', '/');
            ClassModel cm = compiled.model(internal);
            // A nested class's qualified name may be dotted all the way: try each split from the right.
            for (int i = internal.lastIndexOf('/'); cm == null && i > 0; i = internal.lastIndexOf('/', i - 1)) {
                internal = internal.substring(0, i) + "$" + internal.substring(i + 1);
                cm = compiled.model(internal);
            }
            return cm;
        }

        JsonDtos.@Nullable Dto dto(String internal) {
            GraphNode n = byDotted.get(dotted(internal));
            if (n == null || compiled.stale(n.sourceFile())) return null;
            ClassModel cm = compiled.model(internal);
            return cm == null ? null : JsonDtos.dto(cm);
        }

        /** Each class read, keyed by its simple name unless another class read shares it. */
        Map<String, Body> bodies(Map<String, JsonDtos.Dto> dtos) {
            Map<String, Integer> simples = new HashMap<>();
            for (String q : dtos.keySet()) simples.merge(simple(dotted(q)), 1, Integer::sum);
            Map<String, Body> out = new LinkedHashMap<>();
            dtos.forEach((q, dto) -> {
                String dotted = dotted(q);
                GraphNode n = byDotted.get(dotted);
                if (n == null) return;
                String key = simples.getOrDefault(simple(dotted), 1) > 1 ? dotted : simple(dotted);
                out.put(q, new Body(q, dotted, key, n.id(), n.sourceFile(), line(n.sourceLocation()), dto));
            });
            return out;
        }

        private static String simple(String dotted) {
            return dotted.substring(dotted.lastIndexOf('.') + 1);
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

        /** Whether {@code rel}'s path says a tool writes it, its text unread. */
        boolean byPath(String rel) {
            return GENERATED_PATH.matcher(rel).find() || JsonReaders.generatedBy(rel, outputs);
        }

        boolean is(String rel) {
            if (byPath(rel)) return true;
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
