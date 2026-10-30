// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One source set's compiled classes as graph nodes and mentions, with the ids the source scan uses
 * so the two land on the same nodes. The compiler has resolved every call, so calls and type
 * references are {@code EXTRACTED}. Lambda and anonymous-class bodies count toward the method or
 * class that holds them; a Kotlin file facade's functions belong to its file; overloads share one
 * node and list their descriptors in {@code attributes.signatures}; every method's descriptors are
 * under {@link Linker#DESCRIPTORS}, and each call and override edge names the overloads it joins
 * ({@link GraphEdge#overloads()}).
 */
final class JvmFacts {

    private JvmFacts() {}

    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_BRIDGE = 0x0040;
    private static final int ACC_ENUM = 0x4000;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_PROTECTED = 0x0004;

    /** Members the compiler writes for every record or enum: not the author's API, so not nodes. */
    private static final Set<String> RECORD_GENERATED = Set.of("equals", "hashCode", "toString");

    private static final Set<String> ENUM_GENERATED = Set.of("values", "valueOf");

    /** Library packages never declared in a workspace: not worth a mention the linker would drop. */
    private static final List<String> PLATFORM =
            List.of("java/", "javax/", "jdk/", "sun/", "kotlin/", "groovy/", "org/codehaus/groovy/", "scala/");

    private static final Set<String> ROOT_TYPES =
            Set.of("java/lang/Object", "java/lang/Enum", "java/lang/Record", "kotlin/Any");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-\\[\\]]+)(?::[^}]*)?}");
    private static final Pattern KEY_LITERAL = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+");
    private static final Pattern LAMBDA =
            Pattern.compile("^lambda\\$(.+?)\\$\\d+$|^(.+?)\\$lambda\\$\\d+$|^(.+?)\\$default$");

    /** Finds the repo-relative source file a class was compiled from. */
    interface SourceLookup {
        @Nullable
        String find(String packagePath, String fileName);
    }

    static Fragment map(FactsIndex index, SourceLookup sources) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<Mention> mentions = new ArrayList<>();
        Map<String, Set<String>> signatures = new LinkedHashMap<>();
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        for (ClassFacts c : index.classList()) {
            if (c.isPackageInfo() || c.name().endsWith("module-info") || c.sourceFile() == null) continue;
            String pkg = c.name().contains("/") ? c.name().substring(0, c.name().lastIndexOf('/')) : "";
            String rel = sources.find(pkg, c.sourceFile());
            if (rel == null) continue;
            Owner owner = owner(c, rel, nodes, edges);
            if (owner == null) continue;
            Set<String> generated = generated(c);
            if (owner.named()) {
                boolean nested =
                        c.name().substring(c.name().lastIndexOf('/') + 1).contains("$");
                Map<String, Object> type = new LinkedHashMap<>();
                type.put(Decl.KIND, typeKind(c));
                if (!nested) type.put(Decl.VIS, visibility(c.access()));
                List<String> ann = annotationNames(c.annotations());
                if (!ann.isEmpty()) type.put(Decl.ANN, ann);
                String sup = c.superName();
                if (sup != null && !ROOT_TYPES.contains(sup)) type.put(Decl.EXT, List.of(Descriptors.binaryName(sup)));
                if (!c.interfaces().isEmpty()) {
                    type.put(
                            Decl.IMPL,
                            c.interfaces().stream()
                                    .map(Descriptors::binaryName)
                                    .sorted()
                                    .toList());
                }
                facts.merge(owner.id(), type, Decl::merge);
                supertypes(c, owner.id(), rel, mentions);
                annotations(c.annotations(), owner.id(), mentions);
                for (FieldFacts f : c.fields()) {
                    if ((f.access() & ACC_SYNTHETIC) != 0) continue;
                    if ((f.access() & ACC_ENUM) != 0) {
                        String constant = GraphIds.member(owner.id(), f.name());
                        nodes.putIfAbsent(constant, new GraphNode(constant, f.name(), "code", rel, ""));
                        facts.merge(constant, Map.of(Decl.KIND, "constant", Decl.VIS, "public"), Decl::merge);
                        edges.add(GraphEdge.of(owner.id(), constant, Relations.CONTAINS, Confidence.EXTRACTED, rel, 0));
                        continue;
                    }
                    typeMention(f.desc(), owner.id(), 0, "field", mentions);
                    annotations(f.annotations(), owner.id(), mentions);
                }
            }
            for (MethodFacts m : c.methods()) {
                if (generated.contains(m.name())) continue;
                method(c, m, owner, rel, nodes, edges, mentions, signatures, facts);
            }
        }
        facts.forEach((id, f) -> {
            GraphNode n = nodes.get(id);
            if (n != null) nodes.put(id, Decl.with(n, f));
        });
        signatures.forEach((id, sigs) -> {
            GraphNode n = nodes.get(id);
            if (n != null) {
                Map<String, Object> extra = new LinkedHashMap<>(n.extra());
                extra.put(Linker.DESCRIPTORS, List.copyOf(sigs));
                if (sigs.size() > 1) extra.put("attributes", Map.of("signatures", List.copyOf(sigs)));
                nodes.put(
                        id, new GraphNode(n.id(), n.label(), n.fileType(), n.sourceFile(), n.sourceLocation(), extra));
            }
        });
        return new Fragment(List.copyOf(nodes.values()), edges, mentions);
    }

    /**
     * The method names the compiler wrote for {@code c} that are not the author's API: a record's
     * object methods, an enum's lookups. A record's accessors are its API, so they stay nodes and
     * calls to them stay edges.
     */
    private static Set<String> generated(ClassFacts c) {
        if ("java/lang/Record".equals(c.superName())) return RECORD_GENERATED;
        return "java/lang/Enum".equals(c.superName()) ? ENUM_GENERATED : Set.of();
    }

    /**
     * The node a class's members hang off: its own for a named type, the enclosing named type for
     * an anonymous or synthetic one, the file for a Kotlin facade. {@code named} says whether the
     * class is the node itself.
     */
    private record Owner(String id, boolean named, boolean facade) {}

    private static @Nullable Owner owner(
            ClassFacts c, String rel, Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        String simple = c.name().substring(c.name().lastIndexOf('/') + 1);
        String sourceFile = c.sourceFile() == null ? "" : c.sourceFile();
        if (!simple.contains("$") && sourceFile.endsWith(".kt") && simple.equals(stem(sourceFile) + "Kt")) {
            String fileId = GraphIds.file(rel);
            nodes.putIfAbsent(
                    fileId,
                    new GraphNode(
                            fileId,
                            rel.substring(rel.lastIndexOf('/') + 1),
                            "code",
                            rel,
                            "L1",
                            Map.of(Linker.QUALIFIED_NAME, Descriptors.binaryName(c.name()))));
            return new Owner(fileId, false, true);
        }
        String[] segments = simple.split("\\$");
        String id = GraphIds.declared(rel, segments[0]);
        String parent = GraphIds.file(rel);
        String qualified = Descriptors.binaryName(c.name().substring(0, c.name().lastIndexOf('/') + 1) + segments[0]);
        boolean named = true;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals("Companion")) continue;
            if (seg.isEmpty() || Character.isDigit(seg.charAt(0)) || seg.startsWith("lambda")) {
                named = false;
                break;
            }
            if (i > 0) {
                parent = id;
                id = GraphIds.declared(rel, seg);
                qualified = qualified + "$" + seg;
            }
        }
        if (!named)
            return (c.access() & ACC_SYNTHETIC) != 0 && segments.length == 1 ? null : new Owner(id, false, false);
        int line = c.methods().stream()
                .mapToInt(MethodFacts::firstLine)
                .filter(l -> l > 0)
                .min()
                .orElse(0);
        nodes.putIfAbsent(
                id,
                new GraphNode(
                        id,
                        segments[segments.length - 1],
                        "code",
                        rel,
                        GraphNode.location(line),
                        Map.of(Linker.QUALIFIED_NAME, qualified)));
        edges.add(GraphEdge.of(parent, id, Relations.CONTAINS, Confidence.EXTRACTED, rel, line));
        return new Owner(id, true, false);
    }

    private static void method(
            ClassFacts c,
            MethodFacts m,
            Owner owner,
            String rel,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            List<Mention> mentions,
            Map<String, Set<String>> signatures,
            Map<String, Map<String, Object>> facts) {
        if (m.name().equals("<clinit>")) return;
        String name = m.name();
        Matcher lambda = LAMBDA.matcher(name);
        boolean synthetic = (m.access() & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0 || name.contains("$");
        if (lambda.matches()) {
            name = lambda.group(1) != null
                    ? lambda.group(1)
                    : lambda.group(2) != null ? lambda.group(2) : lambda.group(3);
        }
        if (owner.facade()) name = property(c, name);
        String from;
        if (name.equals("<init>") || !owner.named() && !owner.facade() || synthetic && !lambda.matches()) {
            from = owner.id();
        } else {
            from = GraphIds.member(owner.id(), name);
            if (!synthetic) {
                boolean property = owner.facade() && !name.equals(m.name());
                String label = property ? name : owner.facade() ? name + "()" : "." + name + "()";
                nodes.putIfAbsent(from, new GraphNode(from, label, "code", rel, GraphNode.location(m.firstLine())));
                edges.add(GraphEdge.of(
                        owner.id(),
                        from,
                        owner.facade() ? Relations.CONTAINS : Relations.METHOD,
                        Confidence.EXTRACTED,
                        rel,
                        m.firstLine()));
                signatures.computeIfAbsent(from, k -> new TreeSet<>()).add(m.desc());
                Map<String, Object> member = new LinkedHashMap<>();
                member.put(Decl.KIND, property ? "property" : owner.facade() ? "function" : "method");
                member.put(Decl.VIS, visibility(m.access()));
                List<String> ann = annotationNames(m.annotations());
                if (!ann.isEmpty()) member.put(Decl.ANN, ann);
                facts.merge(from, member, Decl::merge);
                signature(m.desc(), from, mentions);
                annotations(m.annotations(), from, mentions);
                if (!owner.facade() && (m.access() & (ACC_STATIC | ACC_PRIVATE)) == 0) {
                    overrides(c, name, from, m.firstLine(), m.desc() + ">" + m.desc(), mentions);
                }
            }
        }
        if ((m.access() & ACC_BRIDGE) != 0 && owner.named() && !owner.facade()) {
            // A bridge carries the supertype's erased descriptor and calls the method that overrides it.
            for (CallSite site : m.calls()) {
                if (site.owner().equals(c.name()) && site.name().equals(m.name())) {
                    overrides(
                            c,
                            m.name(),
                            GraphIds.member(owner.id(), m.name()),
                            m.firstLine(),
                            site.desc() + ">" + m.desc(),
                            mentions);
                }
            }
        }
        String caller = from.equals(owner.id()) ? "*" : callerOverload(c, m, name, lambda.matches());
        for (CallSite site : m.calls()) {
            if (platform(site.owner())) continue;
            String target = Descriptors.binaryName(site.owner());
            if (site.name().equals("<init>")) {
                mentions.add(new Mention(from, Mention.Kind.CLASS, target, site.line(), Relations.CALLS, "call"));
            } else if (!site.name().startsWith("access$")) {
                String callee = site.name().endsWith("$default")
                        ? site.name().substring(0, site.name().length() - "$default".length())
                        : site.name();
                String called = callee.equals(site.name()) ? site.desc() : "*";
                mentions.add(new Mention(
                        from,
                        Mention.Kind.MEMBER,
                        target + "#" + callee,
                        site.line(),
                        Relations.CALLS,
                        null,
                        caller + ">" + called));
            }
        }
        for (CallSite site : m.calls()) {
            for (String literal : site.literals()) configKeys(literal, from, site.line(), mentions);
        }
    }

    /**
     * A Kotlin top-level property's accessor named as the property, as source names it: {@code
     * getAppModule} is {@code appModule} when the facade holds that field. Any other name is kept.
     */
    static String property(ClassFacts c, String method) {
        for (String prefix : List.of("get", "is")) {
            if (method.length() <= prefix.length() || !method.startsWith(prefix)) continue;
            char first = method.charAt(prefix.length());
            if (!Character.isUpperCase(first)) continue;
            String field =
                    prefix.equals("is") ? method : Character.toLowerCase(first) + method.substring(prefix.length() + 1);
            for (FieldFacts f : c.fields()) {
                if (f.name().equals(field)) return field;
            }
        }
        return method;
    }

    /**
     * The descriptor of the method whose body made {@code m}'s calls: its own, or for a lambda the one
     * method of that name in the class; {@code *} when the class declares several, or for a Kotlin
     * {@code $default}, whose own descriptor is not the method's.
     */
    private static String callerOverload(ClassFacts c, MethodFacts m, String name, boolean lambda) {
        if (!lambda) return m.desc();
        if (m.name().endsWith("$default")) return "*";
        List<String> named = c.methods().stream()
                .filter(o -> o.name().equals(name) && (o.access() & (ACC_SYNTHETIC | ACC_BRIDGE)) == 0)
                .map(MethodFacts::desc)
                .toList();
        return named.size() == 1 ? named.getFirst() : "*";
    }

    /**
     * The member {@code from} as an override of {@code name} on each direct supertype, or on the
     * nearest one above it that declares {@code name}: the linker resolves each, and drops the
     * mention when no supertype in the workspace declares one.
     */
    private static void overrides(
            ClassFacts c, String name, String from, int line, String overload, List<Mention> mentions) {
        List<String> direct = new ArrayList<>();
        if (c.superName() != null && !ROOT_TYPES.contains(c.superName())) direct.add(c.superName());
        direct.addAll(c.interfaces());
        for (String sup : direct) {
            if (platform(sup)) continue;
            mentions.add(new Mention(
                    from,
                    Mention.Kind.MEMBER,
                    Descriptors.binaryName(sup) + "#" + name,
                    line,
                    Relations.OVERRIDES,
                    "override",
                    overload));
        }
    }

    private static void supertypes(ClassFacts c, String id, String rel, List<Mention> mentions) {
        boolean iface = (c.access() & ACC_INTERFACE) != 0;
        String sup = c.superName();
        if (sup != null && !ROOT_TYPES.contains(sup) && !platform(sup)) {
            mentions.add(new Mention(id, Mention.Kind.CLASS, Descriptors.binaryName(sup), 0, Relations.INHERITS));
        }
        for (String i : c.interfaces()) {
            if (platform(i)) continue;
            String relation = iface ? Relations.INHERITS : Relations.IMPLEMENTS;
            mentions.add(new Mention(id, Mention.Kind.CLASS, Descriptors.binaryName(i), 0, relation));
        }
    }

    private static void annotations(List<AnnotationFacts> annotations, String from, List<Mention> mentions) {
        for (AnnotationFacts a : annotations) {
            String type = a.typeName();
            if (!platform(Descriptors.internalName(type))) {
                mentions.add(new Mention(from, Mention.Kind.CLASS, type, 0, Relations.REFERENCES, "attribute"));
            }
            boolean bindsPrefix = type.endsWith(".ConfigurationProperties");
            for (Map.Entry<String, List<String>> v : a.values().entrySet()) {
                for (String value : v.getValue()) {
                    if (bindsPrefix
                            && (v.getKey().equals("prefix") || v.getKey().equals("value"))
                            && !value.isEmpty()) {
                        mentions.add(new Mention(from, Mention.Kind.CONFIG_KEY, value + ".*", 0));
                    }
                    Matcher p = PLACEHOLDER.matcher(value);
                    while (p.find()) mentions.add(new Mention(from, Mention.Kind.CONFIG_KEY, p.group(1), 0));
                }
            }
        }
    }

    private static void configKeys(String literal, String from, int line, List<Mention> mentions) {
        Matcher p = PLACEHOLDER.matcher(literal);
        boolean any = false;
        while (p.find()) {
            mentions.add(new Mention(from, Mention.Kind.CONFIG_KEY, p.group(1), line));
            any = true;
        }
        if (!any && KEY_LITERAL.matcher(literal).matches()) {
            mentions.add(new Mention(from, Mention.Kind.CONFIG_KEY, literal, line));
        }
    }

    private static String typeKind(ClassFacts c) {
        if ((c.access() & ACC_ANNOTATION) != 0) return "annotation";
        if ((c.access() & ACC_INTERFACE) != 0) return "interface";
        if ((c.access() & ACC_ENUM) != 0) return "enum";
        return "java/lang/Record".equals(c.superName()) ? "record" : "class";
    }

    private static String visibility(int access) {
        if ((access & ACC_PUBLIC) != 0) return "public";
        if ((access & ACC_PROTECTED) != 0) return "protected";
        if ((access & ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    /** Simple names, sorted: {@code Transactional} for {@code org.springframework…Transactional}. */
    private static List<String> annotationNames(List<AnnotationFacts> annotations) {
        Set<String> out = new TreeSet<>();
        for (AnnotationFacts a : annotations) {
            String name = a.typeName();
            out.add(name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1));
        }
        return List.copyOf(out);
    }

    /** Parameter and return types of a method descriptor, as references. */
    private static void signature(String desc, String from, List<Mention> mentions) {
        int close = desc.indexOf(')');
        if (close < 0) return;
        for (String t : objectTypes(desc.substring(1, close))) {
            typeMention("L" + t + ";", from, 0, "parameter_type", mentions);
        }
        typeMention(desc.substring(close + 1), from, 0, "return_type", mentions);
    }

    private static void typeMention(String desc, String from, int line, String context, List<Mention> mentions) {
        String d = desc.replace("[", "");
        if (!d.startsWith("L") || !d.endsWith(";")) return;
        String internal = d.substring(1, d.length() - 1);
        if (platform(internal)) return;
        mentions.add(new Mention(
                from, Mention.Kind.CLASS, Descriptors.binaryName(internal), line, Relations.REFERENCES, context));
    }

    /** The internal names of the object types in a descriptor's parameter list. */
    static List<String> objectTypes(String params) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < params.length(); i++) {
            if (params.charAt(i) == 'L') {
                int end = params.indexOf(';', i);
                if (end < 0) break;
                out.add(params.substring(i + 1, end));
                i = end;
            }
        }
        return out;
    }

    private static boolean platform(String internalName) {
        for (String p : PLATFORM) {
            if (internalName.startsWith(p)) return true;
        }
        return false;
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
