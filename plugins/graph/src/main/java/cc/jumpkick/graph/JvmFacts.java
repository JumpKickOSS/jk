// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Confidence;
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
 * node and list their descriptors in {@code attributes.signatures}.
 */
final class JvmFacts {

    private JvmFacts() {}

    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_BRIDGE = 0x0040;

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
        for (ClassFacts c : index.classList()) {
            if (c.isPackageInfo() || c.name().endsWith("module-info") || c.sourceFile() == null) continue;
            String pkg = c.name().contains("/") ? c.name().substring(0, c.name().lastIndexOf('/')) : "";
            String rel = sources.find(pkg, c.sourceFile());
            if (rel == null) continue;
            Owner owner = owner(c, rel, nodes, edges);
            if (owner == null) continue;
            if (owner.named()) {
                supertypes(c, owner.id(), rel, mentions);
                annotations(c.annotations(), owner.id(), mentions);
                for (FieldFacts f : c.fields()) {
                    if ((f.access() & ACC_SYNTHETIC) != 0) continue;
                    typeMention(f.desc(), owner.id(), 0, "field", mentions);
                    annotations(f.annotations(), owner.id(), mentions);
                }
            }
            for (MethodFacts m : c.methods()) method(c, m, owner, rel, nodes, edges, mentions, signatures);
        }
        signatures.forEach((id, sigs) -> {
            GraphNode n = nodes.get(id);
            if (n != null && sigs.size() > 1) {
                Map<String, Object> extra = new LinkedHashMap<>(n.extra());
                extra.put("attributes", Map.of("signatures", List.copyOf(sigs)));
                nodes.put(
                        id, new GraphNode(n.id(), n.label(), n.fileType(), n.sourceFile(), n.sourceLocation(), extra));
            }
        });
        return new Fragment(List.copyOf(nodes.values()), edges, mentions);
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
                id = GraphIds.member(id, seg);
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
            Map<String, Set<String>> signatures) {
        if (m.name().equals("<clinit>")) return;
        String name = m.name();
        Matcher lambda = LAMBDA.matcher(name);
        boolean synthetic = (m.access() & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0 || name.contains("$");
        if (lambda.matches()) {
            name = lambda.group(1) != null
                    ? lambda.group(1)
                    : lambda.group(2) != null ? lambda.group(2) : lambda.group(3);
        }
        String from;
        if (name.equals("<init>") || !owner.named() && !owner.facade() || synthetic && !lambda.matches()) {
            from = owner.id();
        } else {
            from = GraphIds.member(owner.id(), name);
            if (!synthetic) {
                String label = owner.facade() ? name + "()" : "." + name + "()";
                nodes.putIfAbsent(from, new GraphNode(from, label, "code", rel, GraphNode.location(m.firstLine())));
                edges.add(GraphEdge.of(
                        owner.id(),
                        from,
                        owner.facade() ? Relations.CONTAINS : Relations.METHOD,
                        Confidence.EXTRACTED,
                        rel,
                        m.firstLine()));
                signatures.computeIfAbsent(from, k -> new TreeSet<>()).add(m.desc());
                signature(m.desc(), from, mentions);
                annotations(m.annotations(), from, mentions);
            }
        }
        for (CallSite site : m.calls()) {
            if (platform(site.owner())) continue;
            String target = Descriptors.binaryName(site.owner());
            if (site.name().equals("<init>")) {
                mentions.add(new Mention(from, Mention.Kind.CLASS, target, site.line(), Relations.CALLS, "call"));
            } else if (!site.name().startsWith("access$")) {
                mentions.add(new Mention(from, Mention.Kind.MEMBER, target + "#" + site.name(), site.line()));
            }
        }
        for (CallSite site : m.calls()) {
            for (String literal : site.literals()) configKeys(literal, from, site.line(), mentions);
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
