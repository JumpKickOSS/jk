// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The type kotlinc gave a Kotlin function or property that writes none, read from its compiled
 * class: the method's generic {@code Signature} (its descriptor when it has none), named as Kotlin
 * names it ({@code Int}, {@code List<Owner>}), with {@code ?} where kotlinc marked the result
 * {@code @Nullable}. Nothing is claimed for a source edited since its classes were built, a module
 * with no classes, or a name the class declares more than once.
 */
final class KotlinTypes {

    private KotlinTypes() {}

    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_BRIDGE = 0x0040;

    private static final String NULLABLE = "Lorg/jetbrains/annotations/Nullable;";

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("java/lang/Object", "Any"),
            Map.entry("java/lang/Integer", "Int"),
            Map.entry("java/lang/Character", "Char"),
            Map.entry("java/lang/Void", "Unit"),
            Map.entry("java/lang/Iterable", "Iterable"),
            Map.entry("java/util/Collection", "Collection"),
            Map.entry("java/util/List", "List"),
            Map.entry("java/util/Set", "Set"),
            Map.entry("java/util/Map", "Map"),
            Map.entry("java/util/Map$Entry", "Map.Entry"));

    /** The inferred type of each node, as {@link cc.jumpkick.codegraph.SymbolReader} asks for it. */
    static Function<GraphNode, @Nullable String> of(GraphService.Workspace w, CodeGraph g) {
        Set<String> stale = new HashSet<>();
        Set<String> built = new HashSet<>();
        for (GraphBuild.ClassIndex c : w.indexes()) {
            stale.addAll(c.stale());
            built.add(c.modulePrefix() + " " + c.sourceSet());
        }
        Map<Path, @Nullable ClassModel> classes = new HashMap<>();
        return n -> {
            String rel = n.sourceFile();
            if (!rel.endsWith(".kt") || stale.contains(rel)) return null;
            Modules modules = g.modules();
            Modules.Module m = modules.of(rel);
            String prefix = m == null ? "" : m.prefix();
            String set = ClassSources.set(modules, rel);
            if (set == null || !built.contains(prefix + " " + set)) return null;
            String owner = owner(w.root(), g, n);
            if (owner == null) return null;
            String dir =
                    switch (set) {
                        case "main" -> "target/classes";
                        case "guard" -> "target/guard/classes";
                        default -> "target/test-classes";
                    };
            Path file = w.root().resolve(prefix).resolve(dir).resolve(owner + ".class");
            ClassModel cm = classes.computeIfAbsent(file, KotlinTypes::parse);
            return cm == null ? null : type(cm, n.label());
        };
    }

    /** The internal name of the class declaring {@code n}: its type's, or its file's facade class. */
    private static @Nullable String owner(Path root, CodeGraph g, GraphNode n) {
        GraphNode type = g.owner(n.id());
        if (type != null && type.extra().get(Linker.QUALIFIED_NAME) instanceof String q) return q.replace('.', '/');
        GraphNode file = g.node(GraphIds.file(n.sourceFile()));
        if (file != null && file.extra().get(Linker.QUALIFIED_NAME) instanceof String q) return q.replace('.', '/');
        String rel = n.sourceFile();
        String stem = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - ".kt".length());
        try {
            Matcher p = PACKAGE.matcher(Files.readString(root.resolve(rel)));
            String pkg = p.find() ? p.group(1).replace('.', '/') + "/" : "";
            return pkg + Character.toUpperCase(stem.charAt(0)) + stem.substring(1) + "Kt";
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable ClassModel parse(Path file) {
        try {
            return Files.isRegularFile(file) ? ClassFile.of().parse(Files.readAllBytes(file)) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The result type of the one method {@code label} names in {@code cm}: a function {@code
     * name()}'s own, a property {@code name}'s getter's; {@code null} for none, or for several.
     */
    static @Nullable String type(ClassModel cm, String label) {
        String bare = label.startsWith(".") ? label.substring(1) : label;
        boolean function = bare.endsWith("()");
        String name = function ? bare.substring(0, bare.length() - 2) : bare;
        Set<String> wanted = new HashSet<>();
        if (function) {
            wanted.add(name);
        } else if (!name.isEmpty()) {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            wanted.add("get" + cap);
            if (name.startsWith("is")) wanted.add(name);
        }
        List<MethodModel> found = new ArrayList<>();
        for (MethodModel mm : cm.methods()) {
            int flags = mm.flags().flagsMask();
            if ((flags & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0) continue;
            String method = mm.methodName().stringValue();
            if (method.contains("$")) continue;
            if (wanted.contains(JvmFacts.kotlinName(method))) found.add(mm);
        }
        if (found.size() != 1) return null;
        MethodModel mm = found.getFirst();
        Signature result = mm.findAttribute(Attributes.signature())
                .map(s -> MethodSignature.parseFrom(s.signature().stringValue()).result())
                .orElseGet(() -> Signature.of(mm.methodTypeSymbol().returnType()));
        String text = render(result);
        return nullable(mm) && !(result instanceof Signature.BaseTypeSig) ? text + "?" : text;
    }

    private static boolean nullable(MethodModel mm) {
        List<Annotation> all = new ArrayList<>();
        mm.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> all.addAll(a.annotations()));
        mm.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> all.addAll(a.annotations()));
        return all.stream().anyMatch(a -> a.className().stringValue().equals(NULLABLE));
    }

    /** {@code sig} as Kotlin writes a type: simple names, {@code out}/{@code in} for wildcards. */
    static String render(Signature sig) {
        return switch (sig) {
            case Signature.BaseTypeSig b -> base(b.baseType());
            case Signature.TypeVarSig v -> v.identifier();
            case Signature.ArrayTypeSig a ->
                a.componentSignature() instanceof Signature.BaseTypeSig b
                        ? base(b.baseType()) + "Array"
                        : "Array<" + render(a.componentSignature()) + ">";
            case Signature.ClassTypeSig c -> {
                String internal = c.className();
                String simple = NAMES.getOrDefault(
                        internal,
                        internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.'));
                if (c.typeArgs().isEmpty()) yield simple;
                List<String> args = new ArrayList<>();
                for (Signature.TypeArg t : c.typeArgs()) args.add(arg(t));
                yield simple + "<" + String.join(", ", args) + ">";
            }
        };
    }

    private static String arg(Signature.TypeArg t) {
        return switch (t) {
            case Signature.TypeArg.Unbounded u -> "*";
            case Signature.TypeArg.Bounded b ->
                switch (b.wildcardIndicator()) {
                    case EXTENDS -> "out " + render(b.boundType());
                    case SUPER -> "in " + render(b.boundType());
                    case NONE -> render(b.boundType());
                };
        };
    }

    private static String base(char c) {
        return switch (c) {
            case 'I' -> "Int";
            case 'J' -> "Long";
            case 'Z' -> "Boolean";
            case 'D' -> "Double";
            case 'F' -> "Float";
            case 'S' -> "Short";
            case 'B' -> "Byte";
            case 'C' -> "Char";
            case 'V' -> "Unit";
            default -> String.valueOf(c);
        };
    }
}
