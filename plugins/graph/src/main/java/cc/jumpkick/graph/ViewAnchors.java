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
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
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
 * Bean property anchors: {@code bean:<Class>.<property>}, a property a server-side template reads
 * off a model attribute ({@code ${owner.telephone}}, {@code *{telephone}} under {@code
 * th:object="${owner}"}, {@code th:field="*{telephone}"}, a fragment called with {@code 'telephone'}
 * as the field it binds), defined at the member that declares it. Renaming {@code
 * Owner.telephone} compiles and fails only when the page renders; this names the page.
 *
 * <p>A template's names are tied through the Spring MVC {@code @Controller} handler that renders it
 * and the model it fills, both read from the compiled classes: a handler (or a method of its class
 * it calls) renders the template whose name it loads as a string ({@code "owners/ownerDetails"},
 * a constant folded in, a {@code ModelAndView}'s); the model's attributes are what it puts with
 * {@code Model.addAttribute}, {@code ModelMap.addAttribute}, {@code Map.put} or {@code
 * ModelAndView.addObject} (the value's type from the local variable's generic signature, the call
 * that made it or the class it constructs; with no name, Spring's {@code owner} or {@code
 * ownerList}), the class's {@code @ModelAttribute} methods, and its form-backing parameters. Each
 * segment of a path is a property of the type the one before it names (a collection's element
 * class for {@code th:each}). A use is a place that must change when one type answers for its root,
 * a judgment call when handlers rendering the template give its root different types that both carry
 * the name; a root nothing types links nowhere. Read: Thymeleaf templates under {@code
 * src/main/resources/templates}. Not read: JSP and other engines, a view resolved at run time, a
 * model filled outside the controller's class, expressions built by preprocessing other than a
 * fragment's {@code *{__${name}__}} field.
 */
final class ViewAnchors {

    private ViewAnchors() {}

    /** The anchor kind of a bean property a template reads. */
    static final String KIND = Anchors.BEAN;

    private static final String TEMPLATES = "src/main/resources/templates/";
    private static final String SPRING = "Lorg/springframework/web/bind/annotation/";
    private static final Set<String> MODEL_OWNERS = Set.of(
            "org/springframework/ui/Model",
            "org/springframework/ui/ModelMap",
            "org/springframework/ui/ExtendedModelMap",
            "org/springframework/ui/ConcurrentModel",
            "org/springframework/validation/support/BindingAwareModelMap",
            "java/util/Map",
            "java/util/HashMap",
            "org/springframework/web/servlet/ModelAndView");
    private static final Set<String> MODEL_METHODS = Set.of("addAttribute", "put", "addObject");
    private static final Set<String> BINDING_PARAMS = Set.of(
            SPRING + "PathVariable;",
            SPRING + "RequestParam;",
            SPRING + "RequestBody;",
            SPRING + "RequestHeader;",
            SPRING + "CookieValue;",
            SPRING + "RequestAttribute;",
            SPRING + "SessionAttribute;");

    /** A template's root names and the types each may be. */
    private static final class Model {
        final Map<String, Set<List<String>>> byName = new LinkedHashMap<>();

        void put(String name, List<String> types) {
            if (!types.isEmpty())
                byName.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(types);
        }
    }

    /**
     * {@code g} with the bean property anchors its templates read off its controllers' models;
     * {@code g} itself when there are none. A workspace with no Thymeleaf template reads nothing.
     */
    static CodeGraph add(
            CodeGraph g,
            Collection<String> files,
            Function<String, @Nullable String> text,
            JsonAnchors.Classfiles compiled) {
        Map<String, String> templates = new TreeMap<>();
        for (String rel : files) {
            int at = rel.indexOf(TEMPLATES);
            if (at >= 0 && rel.endsWith(".html")) {
                templates.put(rel.substring(at + TEMPLATES.length(), rel.length() - ".html".length()), rel);
            }
        }
        if (templates.isEmpty()) return g;
        Map<ClassModel, List<GraphNode>> handlers = handlers(g, compiled);
        if (handlers.isEmpty()) return g;
        Map<String, GraphNode> byDotted = new HashMap<>();
        for (GraphNode n : g.nodes()) {
            if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q
                    && JsonAnchors.JVM.matcher(n.sourceFile()).matches()) byDotted.putIfAbsent(q.replace('$', '.'), n);
        }
        Function<String, Boolean> declared = internal -> byDotted.containsKey(dotted(internal));
        Map<String, Model> models = models(handlers, templates.keySet(), declared);
        if (models.isEmpty()) return g;

        Beans beans = new Beans(compiled, byDotted);
        Map<String, Map<String, GraphEdge>> uses = new TreeMap<>();
        Map<String, Fragments> fragments = new HashMap<>();
        for (Map.Entry<String, Model> m : models.entrySet()) {
            String rel = templates.get(m.getKey());
            if (rel == null) continue;
            String body = text.apply(rel);
            if (body == null) continue;
            new TemplateReader(rel, body, m.getValue(), beans, name -> {
                        String frel = templates.get(name);
                        if (frel == null) return Fragments.NONE;
                        return fragments.computeIfAbsent(frel, r -> Fragments.of(text.apply(r)));
                    })
                    .read(uses);
        }
        if (uses.isEmpty()) return g;

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, String> keys = beans.keys();
        for (Map.Entry<String, Map<String, Bean>> c : beans.read.entrySet()) {
            GraphNode type = byDotted.get(dotted(c.getKey()));
            if (type == null || c.getValue().isEmpty()) continue;
            String source = text.apply(type.sourceFile());
            String masked = source == null ? "" : CodeText.blank(source, CodeText.Blank.COMMENTS_AND_STRINGS);
            int[] starts = JsonReaders.lineStarts(source == null ? "" : source);
            int from = JsonAnchors.line(type.sourceLocation());
            for (Bean b : c.getValue().values()) {
                String name = keys.get(c.getKey()) + "." + b.name();
                nodes.add(Anchors.node(KIND, name));
                int line = JsonAnchors.declarationLine(masked, starts, b.member(), !b.field(), from);
                edges.add(GraphEdge.of(
                        type.id(),
                        Anchors.id(KIND, name),
                        Relations.DEFINES,
                        Confidence.EXTRACTED,
                        type.sourceFile(),
                        line));
            }
        }
        for (Map.Entry<String, Map<String, GraphEdge>> f : uses.entrySet()) {
            Map<String, GraphEdge> byId = f.getValue();
            List<GraphEdge> kept = new ArrayList<>();
            for (GraphEdge e : byId.values()) {
                // The use names a class's anchor by its internal name until the keys are known.
                String internal =
                        e.target().substring(KIND.length() + 1, e.target().lastIndexOf('.'));
                String prop = e.target().substring(e.target().lastIndexOf('.') + 1);
                String key = keys.get(internal);
                if (key == null) continue;
                GraphEdge k = GraphEdge.of(
                        e.source(),
                        Anchors.id(KIND, key + "." + prop),
                        e.relation(),
                        e.confidence(),
                        e.sourceFile(),
                        JsonAnchors.line(e.sourceLocation()));
                kept.add(k.withSitesOf(e));
            }
            String fileId = GraphIds.file(f.getKey());
            if (!g.has(fileId)) {
                String rel = f.getKey();
                nodes.add(new GraphNode(fileId, rel.substring(rel.lastIndexOf('/') + 1), "code", rel, ""));
            }
            edges.addAll(kept);
        }
        return CodeGraph.plus(g, new Fragment(nodes, edges));
    }

    static String dotted(String internal) {
        return internal.replace('/', '.').replace('$', '.');
    }

    // ---------------------------------------------------------------- controllers and their models

    /**
     * The methods defining a route, by the class file of the Spring MVC {@code @Controller} (not a
     * {@code @RestController}) that declares them, where that file is current.
     */
    private static Map<ClassModel, List<GraphNode>> handlers(CodeGraph g, JsonAnchors.Classfiles compiled) {
        Map<String, List<GraphNode>> byType = new LinkedHashMap<>();
        for (List<GraphEdge> defines : JsonAnchors.handlers(g).values()) {
            for (GraphEdge e : defines) {
                GraphNode type = g.owner(e.source());
                GraphNode method = g.node(e.source());
                if (type == null || method == null || compiled.stale(type.sourceFile())) continue;
                List<GraphNode> ms = byType.computeIfAbsent(type.id(), k -> new ArrayList<>());
                if (!ms.contains(method)) ms.add(method);
            }
        }
        Map<ClassModel, List<GraphNode>> out = new LinkedHashMap<>();
        byType.forEach((id, methods) -> {
            GraphNode type = g.node(id);
            if (type == null || !(type.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) return;
            ClassModel cm = compiled.model(q.replace('.', '/'));
            List<Annotation> annotations = cm == null ? List.of() : JsonDtos.annotations(cm);
            if (JsonDtos.has(annotations, "Lorg/springframework/stereotype/Controller;")
                    && !JsonDtos.has(annotations, SPRING + "RestController;")) out.put(cm, methods);
        });
        return out;
    }

    /**
     * Each template a {@code @Controller} renders, with the model it is rendered with: per handler
     * (a method defining a route), the view names among the strings its code (and that of the methods
     * of its class it calls) loads, and the attributes it and the class's {@code @ModelAttribute}
     * methods put.
     */
    private static Map<String, Model> models(
            Map<ClassModel, List<GraphNode>> handlers, Set<String> views, Function<String, Boolean> declared) {
        Map<String, Model> out = new TreeMap<>();
        for (Map.Entry<ClassModel, List<GraphNode>> t : handlers.entrySet()) {
            ClassModel cm = t.getKey();
            Model shared = new Model();
            for (MethodModel m : cm.methods()) {
                for (Annotation a : methodAnnotations(m)) {
                    if (!a.className().equalsString(SPRING + "ModelAttribute;")) continue;
                    List<String> types = workspace(returnNames(m), declared);
                    if (types.isEmpty()) continue;
                    List<String> v = JsonDtos.values(a, "value");
                    if (v.isEmpty()) v = JsonDtos.values(a, "name");
                    shared.put(
                            v.isEmpty() || v.getFirst().isEmpty() ? variable(types, returnNames(m)) : v.getFirst(),
                            types);
                }
            }
            for (GraphNode method : t.getValue()) {
                String name = JsonAnchors.methodName(method.label());
                for (MethodModel m : cm.methods()) {
                    if (!m.methodName().equalsString(name) || (m.flags().flagsMask() & 0x1040) != 0) continue;
                    Set<String> rendered = new TreeSet<>();
                    Model model = new Model();
                    shared.byName.forEach((k, v) -> v.forEach(types -> model.put(k, types)));
                    parameters(m, model, declared);
                    walk(cm, m, views, model, rendered, declared, new LinkedHashSet<>());
                    for (String view : rendered) {
                        Model into = out.computeIfAbsent(view, k -> new Model());
                        model.byName.forEach((k, v) -> v.forEach(types -> into.put(k, types)));
                    }
                }
            }
        }
        out.values().removeIf(m -> m.byName.isEmpty());
        return out;
    }

    /** A handler's form-backing parameters: a workspace class no binding annotation claims, by its {@code @ModelAttribute} name or Spring's. */
    private static void parameters(MethodModel m, Model model, Function<String, Boolean> declared) {
        MethodSignature sig = m.findAttribute(Attributes.signature())
                .map(s -> MethodSignature.parseFrom(s.signature().stringValue()))
                .orElse(null);
        List<ClassDesc> params = m.methodTypeSymbol().parameterList();
        List<List<Annotation>> byParam = new ArrayList<>();
        m.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .ifPresent(a -> a.parameterAnnotations().forEach(p -> byParam.add(new ArrayList<>(p))));
        for (int i = 0; i < params.size(); i++) {
            List<Annotation> pa = i < byParam.size() ? byParam.get(i) : List.of();
            if (pa.stream().anyMatch(a -> BINDING_PARAMS.contains(a.className().stringValue()))) continue;
            Signature s = sig != null && sig.arguments().size() == params.size()
                    ? sig.arguments().get(i)
                    : Signature.of(params.get(i));
            List<String> names = JsonDtos.names(s);
            List<String> types = workspace(names, declared);
            if (types.isEmpty()) continue;
            String name = null;
            for (Annotation a : pa) {
                if (!a.className().equalsString(SPRING + "ModelAttribute;")) continue;
                List<String> v = JsonDtos.values(a, "value");
                if (!v.isEmpty() && !v.getFirst().isEmpty()) name = v.getFirst();
            }
            model.put(name != null ? name : variable(types, names), types);
        }
    }

    /**
     * Reads {@code m}'s code: the view names it loads, the attributes it puts, and the same of each
     * method of its class it calls, two calls deep.
     */
    private static void walk(
            ClassModel cm,
            MethodModel m,
            Set<String> views,
            Model model,
            Set<String> rendered,
            Function<String, Boolean> declared,
            Set<String> seen) {
        if (!seen.add(m.methodName().stringValue() + m.methodType().stringValue()) || seen.size() > 3) return;
        MethodCode code = MethodCode.of(m);
        if (code == null) return;
        String self = cm.thisClass().asInternalName();
        for (int i = 0; i < code.instructions.size(); i++) {
            Instruction ins = code.instructions.get(i);
            if (ins instanceof ConstantInstruction c && c.constantValue() instanceof String s) {
                if (views.contains(s)) rendered.add(s);
                else if (s.startsWith("/") && views.contains(s.substring(1))) rendered.add(s.substring(1));
            } else if (ins instanceof InvokeInstruction inv) {
                String owner = inv.owner().asInternalName();
                String name = inv.name().stringValue();
                if (MODEL_OWNERS.contains(owner) && MODEL_METHODS.contains(name)) {
                    attribute(code, i, inv, model, declared);
                } else if (owner.equals(self)) {
                    for (MethodModel callee : cm.methods()) {
                        if (callee.methodName().equalsString(name)
                                && callee.methodType().equalsString(inv.type().stringValue()))
                            walk(cm, callee, views, model, rendered, declared, seen);
                    }
                }
            }
        }
    }

    /** The attribute the model call at {@code i} puts: its name from the string loaded before its value, else Spring's name for the value's type. */
    private static void attribute(
            MethodCode code, int i, InvokeInstruction inv, Model model, Function<String, Boolean> declared) {
        int params = inv.typeSymbol().parameterCount();
        if (params < 1 || params > 2 || i < 1) return;
        List<String> names = code.typeBefore(i);
        List<String> types = workspace(names, declared);
        if (types.isEmpty()) return;
        String name = null;
        if (params == 2) {
            for (int j = i - 2; j >= Math.max(0, i - 4); j--) {
                if (code.instructions.get(j) instanceof ConstantInstruction c
                        && c.constantValue() instanceof String s) {
                    name = s;
                    break;
                }
            }
            if (name == null) return;
        }
        model.put(name != null ? name : variable(types, names), types);
    }

    /** Spring's name for a value of {@code types} ({@code Conventions.getVariableName}): {@code owner}, {@code ownerList} for a collection. */
    private static String variable(List<String> types, List<String> names) {
        String simple = types.getFirst().substring(types.getFirst().lastIndexOf('/') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1);
        String v = JsonDtos.mangle(simple);
        boolean collection = !names.isEmpty() && !names.getFirst().equals(types.getFirst());
        return collection ? v + "List" : v;
    }

    private static List<String> workspace(List<String> names, Function<String, Boolean> declared) {
        List<String> out = new ArrayList<>();
        for (String n : names) if (declared.apply(n)) out.add(n);
        return out;
    }

    private static List<String> returnNames(MethodModel m) {
        return m.findAttribute(Attributes.signature())
                .map(s -> JsonDtos.names(
                        MethodSignature.parseFrom(s.signature().stringValue()).result()))
                .orElseGet(
                        () -> JsonDtos.names(Signature.of(m.methodTypeSymbol().returnType())));
    }

    private static List<Annotation> methodAnnotations(MethodModel m) {
        List<Annotation> out = new ArrayList<>();
        m.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        return out;
    }

    // ---------------------------------------------------------------- bean properties

    /** One bean property: its name, the member standing for it, whether that is a field, the class names of its type. */
    private record Bean(String name, String member, boolean field, List<String> types) {}

    /** The bean properties of the workspace's classes, read once each from their class files. */
    private static final class Beans {
        final JsonAnchors.Classfiles compiled;
        final Map<String, GraphNode> byDotted;
        final Map<String, Map<String, Bean>> read = new LinkedHashMap<>();
        final Map<String, @Nullable String> supers = new HashMap<>();

        Beans(JsonAnchors.Classfiles compiled, Map<String, GraphNode> byDotted) {
            this.compiled = compiled;
            this.byDotted = byDotted;
        }

        boolean declared(String internal) {
            return byDotted.containsKey(dotted(internal));
        }

        /** The class among {@code internal} and its workspace superclasses that declares {@code property}, with it; null for none. */
        Map.@Nullable Entry<String, Bean> find(String internal, String property) {
            @Nullable String c = internal;
            for (int depth = 0; c != null && depth < 10; depth++) {
                Map<String, Bean> props = props(c);
                Bean b = props.get(property);
                if (b != null) return Map.entry(c, b);
                c = supers.get(c);
            }
            return null;
        }

        private Map<String, Bean> props(String internal) {
            Map<String, Bean> had = read.get(internal);
            if (had != null) return had;
            Map<String, Bean> out = new LinkedHashMap<>();
            GraphNode n = byDotted.get(dotted(internal));
            ClassModel cm = n == null || compiled.stale(n.sourceFile()) ? null : compiled.model(internal);
            supers.put(internal, null);
            if (cm != null) {
                cm.superclass().ifPresent(s -> {
                    if (declared(s.asInternalName())) supers.put(internal, s.asInternalName());
                });
                Set<String> fields = new LinkedHashSet<>();
                for (var f : cm.fields()) {
                    int flags = f.flags().flagsMask();
                    if ((flags & 0x1008) != 0) continue;
                    String name = f.fieldName().stringValue();
                    fields.add(name);
                    if ((flags & 0x0001) != 0) {
                        Signature s = f.findAttribute(Attributes.signature())
                                .map(x -> Signature.parseFrom(x.signature().stringValue()))
                                .orElseGet(() -> Signature.of(f.fieldTypeSymbol()));
                        out.put(name, new Bean(name, name, true, JsonDtos.names(s)));
                    }
                }
                for (MethodModel m : cm.methods()) {
                    int flags = m.flags().flagsMask();
                    if ((flags & 0x0001) == 0 || (flags & 0x1048) != 0) continue;
                    if (m.methodTypeSymbol().parameterCount() != 0) continue;
                    String name = m.methodName().stringValue();
                    String ret = m.methodTypeSymbol().returnType().descriptorString();
                    String prop = null;
                    if (name.length() > 3 && name.startsWith("get") && !name.equals("getClass") && !ret.equals("V")) {
                        prop = JsonDtos.mangle(name.substring(3));
                    } else if (name.length() > 2 && name.startsWith("is") && ret.equals("Z")) {
                        prop = JsonDtos.mangle(name.substring(2));
                    } else if (cm.findAttribute(Attributes.record()).isPresent() && fields.contains(name)) {
                        prop = name;
                    }
                    if (prop == null || out.containsKey(prop)) continue;
                    List<String> types = m.findAttribute(Attributes.signature())
                            .map(s -> JsonDtos.names(
                                    MethodSignature.parseFrom(s.signature().stringValue())
                                            .result()))
                            .orElseGet(() -> JsonDtos.names(
                                    Signature.of(m.methodTypeSymbol().returnType())));
                    boolean field = fields.contains(prop);
                    out.put(prop, new Bean(prop, field ? prop : name, field, types));
                }
            }
            read.put(internal, out);
            return out;
        }

        /** Each class read with a property used, keyed by its simple name unless another such class shares it. */
        Map<String, String> keys() {
            Map<String, Integer> simples = new HashMap<>();
            for (String c : read.keySet()) simples.merge(simple(c), 1, Integer::sum);
            Map<String, String> out = new HashMap<>();
            for (String c : read.keySet()) out.put(c, simples.getOrDefault(simple(c), 1) > 1 ? dotted(c) : simple(c));
            return out;
        }

        private static String simple(String internal) {
            String d = dotted(internal);
            return d.substring(d.lastIndexOf('.') + 1);
        }
    }

    // ---------------------------------------------------------------- templates

    /** A fragment template's fragments: by name, the indexes of the parameters it binds as a selected field ({@code *{__${name}__}}). */
    private record Fragments(Map<String, Set<Integer>> fields) {
        static final Fragments NONE = new Fragments(Map.of());
        private static final Pattern DEF =
                Pattern.compile("th:fragment\\s*=\\s*\"\\s*([\\w-]+)\\s*(?:\\(([^)]*)\\))?\\s*\"");

        static Fragments of(@Nullable String body) {
            if (body == null) return NONE;
            Map<String, Set<Integer>> out = new HashMap<>();
            Matcher d = DEF.matcher(body);
            while (d.find()) {
                if (d.group(2) == null) continue;
                String[] params = d.group(2).split(",");
                Set<Integer> bound = new TreeSet<>();
                for (int i = 0; i < params.length; i++) {
                    String p = params[i].strip();
                    if (!p.isEmpty() && body.contains("*{__${" + p + "}__}")) bound.add(i);
                }
                if (!bound.isEmpty()) out.put(d.group(1), bound);
            }
            return new Fragments(out);
        }
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w:.-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern VAR_EXPR = Pattern.compile("\\$\\{([^{}]*)}");
    private static final Pattern SEL_EXPR = Pattern.compile("\\*\\{([^{}]*)}");
    private static final Pattern INLINE = Pattern.compile(
            "\\[\\[\\s*(\\$\\{[^{}]*}|\\*\\{[^{}]*})\\s*]]|\\[\\(\\s*(\\$\\{[^{}]*}|\\*\\{[^{}]*})\\s*\\)]");
    private static final Pattern PATH =
            Pattern.compile("(?<![\\w.#$@'?])([A-Za-z_]\\w*)((?:\\s*\\??\\.\\s*[A-Za-z_]\\w*)*)(?![\\w(])");
    private static final Pattern EACH =
            Pattern.compile("\\s*([A-Za-z_]\\w*)\\s*(?:,\\s*\\w+\\s*)?:\\s*\\$\\{([^{}]*)}\\s*");
    private static final Pattern CALL =
            Pattern.compile("~?\\{?\\s*([\\w/.-]+)\\s*::\\s*([\\w-]+)\\s*\\((.*)\\)\\s*}?\\s*");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Set<String> KEYWORDS = Set.of(
            "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "true", "false", "null", "instanceof", "new", "T");
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track",
            "wbr");

    /** One template read against its model: each bean path a use of the property it ends on, and of each it passes. */
    private static final class TemplateReader {
        final String rel;
        final String body;
        final Model model;
        final Beans beans;
        final Function<String, Fragments> fragments;
        final int[] starts;

        /** What an element's scope knows: each variable's types (one list per handler's model) and the selected object's. */
        record Scope(Map<String, Set<List<String>>> vars, Set<List<String>> object) {}

        TemplateReader(String rel, String body, Model model, Beans beans, Function<String, Fragments> fragments) {
            this.rel = rel;
            this.body = body;
            this.model = model;
            this.beans = beans;
            this.fragments = fragments;
            this.starts = JsonReaders.lineStarts(body);
        }

        void read(Map<String, Map<String, GraphEdge>> uses) {
            String text = COMMENT.matcher(body).replaceAll(m -> m.group().replaceAll("[^\\n]", " "));
            Deque<Map.Entry<String, Scope>> stack = new ArrayDeque<>();
            stack.push(Map.entry("", new Scope(new HashMap<>(model.byName), Set.of())));
            Matcher t = TAG.matcher(text);
            int last = 0;
            while (t.find()) {
                inline(text, last, t.start(), stack.getFirst().getValue(), uses);
                last = t.end();
                String name = t.group(2).toLowerCase(Locale.ROOT);
                if (!t.group(1).isEmpty()) {
                    while (stack.size() > 1) {
                        String open = stack.pop().getKey();
                        if (open.equals(name)) break;
                    }
                    continue;
                }
                Scope outer = stack.getFirst().getValue();
                Map<String, Set<List<String>>> vars = new HashMap<>(outer.vars());
                Set<List<String>> object = outer.object();
                Map<String, String> attrs = new LinkedHashMap<>();
                Map<String, Integer> at = new HashMap<>();
                Matcher a = ATTR.matcher(t.group(3));
                while (a.find()) {
                    String key = a.group(1).toLowerCase(Locale.ROOT).replaceFirst("^data-th-", "th:");
                    attrs.put(key, a.group(3) != null ? a.group(3) : a.group(4));
                    at.put(key, t.start(3) + a.start(2) + 1);
                }
                Scope here = new Scope(vars, object);
                String each = attrs.get("th:each");
                if (each != null) {
                    Matcher e = EACH.matcher(each);
                    if (e.matches()) {
                        Set<List<String>> types =
                                expression(e.group(2), here, at.getOrDefault("th:each", 0), false, uses);
                        if (!types.isEmpty()) vars.put(e.group(1), types);
                    }
                }
                String obj = attrs.get("th:object");
                if (obj != null) {
                    Matcher o = VAR_EXPR.matcher(obj);
                    if (o.find())
                        object = expression(
                                o.group(1), new Scope(vars, object), at.getOrDefault("th:object", 0), false, uses);
                }
                here = new Scope(vars, object);
                for (Map.Entry<String, String> attr : attrs.entrySet()) {
                    String k = attr.getKey();
                    if (!k.startsWith("th:") || k.equals("th:each") || k.equals("th:object")) continue;
                    int off = at.getOrDefault(k, 0);
                    if (k.equals("th:replace") || k.equals("th:insert") || k.equals("th:include")) {
                        fragmentCall(attr.getValue(), off, here, uses);
                    }
                    expressions(attr.getValue(), off, here, uses);
                }
                boolean selfClosing = t.group(3).endsWith("/");
                if (!VOID.contains(name) && !selfClosing) stack.push(Map.entry(name, here));
            }
        }

        private void inline(String text, int from, int to, Scope scope, Map<String, Map<String, GraphEdge>> uses) {
            if (to <= from) return;
            Matcher m = INLINE.matcher(text.substring(from, to));
            while (m.find()) {
                String e = m.group(1) != null ? m.group(1) : m.group(2);
                int g = m.group(1) != null ? 1 : 2;
                expressions(e, from + m.start(g), scope, uses);
            }
        }

        /** The {@code ${...}} and {@code *{...}} expressions of one attribute's value, read at {@code off}. */
        private void expressions(String value, int off, Scope scope, Map<String, Map<String, GraphEdge>> uses) {
            if (value.contains("__")) return;
            Matcher v = VAR_EXPR.matcher(value);
            while (v.find()) {
                if (v.start() > 0 && value.charAt(v.start() - 1) == '*') continue;
                expression(v.group(1), scope, off + v.start(1), false, uses);
            }
            Matcher s = SEL_EXPR.matcher(value);
            while (s.find()) expression(s.group(1), scope, off + s.start(1), true, uses);
        }

        /** A fragment call's arguments, split at the commas outside brackets. */
        private static List<String> arguments(String text) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            int from = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') depth = Math.max(0, depth - 1);
                else if (c == ',' && depth == 0) {
                    out.add(text.substring(from, i));
                    from = i + 1;
                }
            }
            out.add(text.substring(from));
            return out;
        }

        /** A fragment called with a literal field name its {@code *{__${name}__}} binds: a selection of that name here. */
        private void fragmentCall(String value, int off, Scope scope, Map<String, Map<String, GraphEdge>> uses) {
            Matcher c = CALL.matcher(value);
            if (!c.matches()) return;
            Set<Integer> bound = fragments.apply(c.group(1)).fields().getOrDefault(c.group(2), Set.of());
            if (bound.isEmpty()) return;
            List<String> args = arguments(c.group(3));
            for (int i : bound) {
                if (i >= args.size()) continue;
                String arg = args.get(i).strip();
                if (arg.length() > 2
                        && arg.startsWith("'")
                        && arg.endsWith("'")
                        && arg.substring(1, arg.length() - 1).matches("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*")) {
                    expression(
                            arg.substring(1, arg.length() - 1),
                            scope,
                            off + c.start(3) + c.group(3).indexOf(arg),
                            true,
                            uses);
                }
            }
        }

        /**
         * The bean paths of one expression ({@code owner.pets}, or under a selection {@code
         * telephone}), each segment a use; the types the last path ends on, for a {@code th:each} or
         * {@code th:object} it sets.
         */
        private Set<List<String>> expression(
                String expr, Scope scope, int off, boolean selection, Map<String, Map<String, GraphEdge>> uses) {
            String masked =
                    QUOTED.matcher(expr).replaceAll(m -> " ".repeat(m.group().length()));
            Set<List<String>> result = new LinkedHashSet<>();
            Matcher p = PATH.matcher(masked);
            while (p.find()) {
                String root = p.group(1);
                if (KEYWORDS.contains(root)) continue;
                List<String> segments = new ArrayList<>();
                Matcher seg = SEGMENT.matcher(p.group(2));
                while (seg.find()) segments.add(seg.group());
                Set<List<String>> types;
                if (selection) {
                    types = scope.object();
                    segments.addFirst(root);
                } else {
                    types = scope.vars().getOrDefault(root, Set.of());
                }
                if (types.isEmpty()) continue;
                int line = JsonReaders.line(starts, off + p.start());
                result = follow(types, segments, line, uses);
            }
            return result;
        }

        /** {@code segments} read off each of {@code types}: a use per property found, graded by how many types answer. */
        private Set<List<String>> follow(
                Set<List<String>> types, List<String> segments, int line, Map<String, Map<String, GraphEdge>> uses) {
            Set<List<String>> current = types;
            for (String s : segments) {
                Map<String, Bean> found = new LinkedHashMap<>();
                Set<List<String>> next = new LinkedHashSet<>();
                for (List<String> t : current) {
                    for (String c : t) {
                        Map.Entry<String, Bean> b = beans.find(c, s);
                        if (b == null) continue;
                        found.put(b.getKey(), b.getValue());
                        List<String> ws = new ArrayList<>();
                        for (String n : b.getValue().types()) if (beans.declared(n)) ws.add(n);
                        if (!ws.isEmpty()) next.add(ws);
                        break;
                    }
                }
                if (found.isEmpty()) return Set.of();
                boolean sure = found.size() == 1 && current.size() == 1;
                for (Map.Entry<String, Bean> f : found.entrySet())
                    use(uses, f.getKey(), f.getValue().name(), line, sure);
                current = next;
                if (current.isEmpty()) return Set.of();
            }
            return current;
        }

        private void use(
                Map<String, Map<String, GraphEdge>> uses, String internal, String prop, int line, boolean sure) {
            Confidence c = sure ? Confidence.INFERRED : Confidence.AMBIGUOUS;
            String id = KIND + ":" + internal + "." + prop;
            GraphEdge e = GraphEdge.of(GraphIds.file(rel), id, Relations.USES, c, rel, line);
            Map<String, GraphEdge> byId = uses.computeIfAbsent(rel, k -> new TreeMap<>());
            GraphEdge had = byId.get(id);
            if (had == null || had.confidence() == Confidence.AMBIGUOUS && sure) byId.put(id, e);
            else if (had.confidence() == c) byId.put(id, had.withSitesOf(e));
        }
    }
}
