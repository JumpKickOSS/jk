// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.LocalVariableInfo;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The public API of the jars the lockfile pins, for a type the workspace does not declare. Each
 * jar's public and protected type names are read once and kept in {@code cache} under a key of the
 * jar's path, size and modification time (store jars never change in place), and in memory while
 * the worker is served; a type's members are read from the jar when asked for. Nothing here enters
 * the graph.
 */
final class LibraryApi {

    /** A locked jar: its coordinate as the lock spells it and where the store holds it. */
    record Jar(String coord, Path path) {}

    /** A type a jar declares: binary name with {@code $} for nesting, and its access flags. */
    record Type(String binary, int access, Jar jar) {

        String dotted() {
            return binary.replace('$', '.');
        }

        String simple() {
            return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
        }
    }

    private static final int ACC_PUBLIC = ClassFile.ACC_PUBLIC;
    private static final int ACC_PROTECTED = ClassFile.ACC_PROTECTED;

    /** Index files by key, kept while the worker is served. */
    private static final Map<String, List<String>> MEMORY = new ConcurrentHashMap<>();

    private final List<Jar> jars;
    private final @Nullable Path cache;
    private @Nullable List<Type> types;

    LibraryApi(List<Jar> jars, @Nullable Path cache) {
        this.jars = List.copyOf(jars);
        this.cache = cache;
    }

    boolean empty() {
        return jars.isEmpty();
    }

    /** Every public type of every jar, in jar order. */
    List<Type> types() {
        List<Type> have = types;
        if (have != null) return have;
        List<Type> out = new ArrayList<>();
        for (Jar jar : jars) {
            for (String row : names(jar)) {
                int tab = row.indexOf('\t');
                if (tab > 0) out.add(new Type(row.substring(0, tab), Integer.parseInt(row.substring(tab + 1)), jar));
            }
        }
        types = out;
        return out;
    }

    /** The types {@code name} names: its qualified name, else a type whose name ends with it ({@code Outer.Inner}). */
    List<Type> resolve(String name) {
        String wanted = name.replace('$', '.');
        List<Type> qualified = new ArrayList<>();
        List<Type> partial = new ArrayList<>();
        for (Type t : types()) {
            if (t.dotted().equals(wanted)) qualified.add(t);
            else if (t.dotted().endsWith("." + wanted)) partial.add(t);
        }
        return qualified.isEmpty() ? partial : qualified;
    }

    /**
     * The structure of the library type {@code target} names ({@code Type} or {@code Type.member}),
     * or {@code null} when no locked jar declares it.
     */
    @Nullable
    String describe(String target) {
        List<Type> found = resolve(target);
        String member = null;
        if (found.isEmpty() && target.lastIndexOf('.') > 0) {
            int dot = target.lastIndexOf('.');
            found = resolve(target.substring(0, dot));
            member = target.substring(dot + 1);
        }
        if (found.isEmpty()) return null;
        if (found.size() > 1 && found.stream().map(Type::dotted).distinct().count() > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("'" + target + "' names " + found.size() + " library types; qualify it:");
            for (Type t : found.subList(0, Math.min(10, found.size()))) {
                lines.add("  " + t.dotted() + "  (" + t.jar().coord() + ")");
            }
            return String.join("\n", lines);
        }
        Type t = found.getFirst();
        ClassModel model = model(t);
        if (model == null) return null;
        return render(t, model, member);
    }

    /** One line per library type whose simple name matches {@code glob} and whose kind is in {@code kinds} (any when empty). */
    List<String> find(Pattern glob, List<String> kinds) {
        List<String> out = new ArrayList<>();
        types().stream()
                .filter(t -> glob.matcher(t.simple()).matches())
                .filter(t -> kinds.isEmpty() || kinds.contains(kind(t.access())) || kinds.contains("type"))
                .sorted(Comparator.comparing(Type::dotted))
                .forEach(t -> out.add(
                        kind(t.access()) + "  " + t.dotted() + "  (" + t.jar().coord() + ")"));
        return out;
    }

    static String kind(int access) {
        if ((access & ClassFile.ACC_ANNOTATION) != 0) return "annotation";
        if ((access & ClassFile.ACC_INTERFACE) != 0) return "interface";
        if ((access & ClassFile.ACC_ENUM) != 0) return "enum";
        return "class";
    }

    private List<String> names(Jar jar) {
        String key;
        try {
            key = Hashing.sha256Hex(jar.path().toAbsolutePath() + "\t" + Files.size(jar.path()) + "\t"
                    + Files.getLastModifiedTime(jar.path()).toMillis());
        } catch (IOException gone) {
            return List.of();
        }
        List<String> held = MEMORY.get(key);
        if (held != null) return held;
        Path file = cache == null ? null : cache.resolve(key + ".names");
        if (file != null && Files.isRegularFile(file)) {
            try {
                List<String> read = Files.readAllLines(file, StandardCharsets.UTF_8);
                MEMORY.put(key, read);
                return read;
            } catch (IOException unreadable) {
                Log.debug("graph: library index unreadable at " + file, unreadable);
            }
        }
        List<String> rows = scan(jar.path());
        MEMORY.put(key, rows);
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                AtomicWrites.replace(file, String.join("\n", rows) + "\n");
            } catch (IOException unwritable) {
                Log.debug("graph: cannot keep library index at " + file, unwritable);
            }
        }
        return rows;
    }

    /** {@code binaryName \t access} for every public or protected class in {@code jar}. */
    private static List<String> scan(Path jar) {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class")
                        || name.startsWith("META-INF/")
                        || name.endsWith("module-info.class")
                        || name.endsWith("package-info.class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    ClassModel m = ClassFile.of().parse(in.readAllBytes());
                    int access = access(m);
                    if ((access & (ACC_PUBLIC | ACC_PROTECTED)) == 0 || (access & ClassFile.ACC_SYNTHETIC) != 0)
                        continue;
                    String binary = m.thisClass().asInternalName().replace('/', '.');
                    if (anonymous(binary)) continue;
                    out.add(binary + "\t" + access);
                } catch (IllegalArgumentException malformed) {
                    Log.debug("graph: unreadable class " + name + " in " + jar, malformed);
                }
            }
        } catch (IOException unreadable) {
            Log.debug("graph: unreadable jar " + jar, unreadable);
        }
        out.sort(null);
        return out;
    }

    /** A class's own flags, or its {@code InnerClasses} entry's for a nested one (which says private or protected). */
    private static int access(ClassModel m) {
        String self = m.thisClass().asInternalName();
        Optional<InnerClassesAttribute> inner = m.findAttribute(Attributes.innerClasses());
        if (inner.isPresent()) {
            for (var info : inner.get().classes()) {
                if (info.innerClass().asInternalName().equals(self)) return info.flagsMask();
            }
        }
        return m.flags().flagsMask();
    }

    private static boolean anonymous(String binary) {
        int dollar = binary.lastIndexOf('$');
        return dollar >= 0 && dollar + 1 < binary.length() && Character.isDigit(binary.charAt(dollar + 1));
    }

    private static @Nullable ClassModel model(Type t) {
        try (ZipFile zip = new ZipFile(t.jar().path().toFile())) {
            ZipEntry e = zip.getEntry(t.binary().replace('.', '/') + ".class");
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return ClassFile.of().parse(in.readAllBytes());
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            Log.debug("graph: cannot read " + t.binary() + " from " + t.jar().path(), unreadable);
            return null;
        }
    }

    /** The type's header, then its public and protected members ({@code member} only, when set). */
    private static String render(Type t, ClassModel m, @Nullable String member) {
        List<String> out = new ArrayList<>();
        out.add("== " + t.dotted() + "  (" + t.jar().coord() + ", library)");
        out.add(header(t, m));
        String simple = t.simple();
        for (FieldModel f : m.fields()) {
            int a = f.flags().flagsMask();
            if (!visible(a) || (a & ClassFile.ACC_SYNTHETIC) != 0) continue;
            if (member != null && !f.fieldName().stringValue().equals(member)) continue;
            String type = f.findAttribute(Attributes.signature())
                    .map(s -> type(Signature.parseFrom(s.signature().stringValue())))
                    .orElseGet(() -> simple(ClassDesc.ofDescriptor(f.fieldType().stringValue())));
            out.add("  " + modifiers(a, false) + type + " " + f.fieldName().stringValue());
        }
        for (MethodModel method : m.methods()) {
            int a = method.flags().flagsMask();
            String name = method.methodName().stringValue();
            if (!visible(a) || (a & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0 || name.equals("<clinit>")) {
                continue;
            }
            boolean ctor = name.equals("<init>");
            if (member != null && !(ctor ? simple : name).equals(member)) continue;
            out.add("  "
                    + method(
                            method,
                            a,
                            ctor ? simple : name,
                            ctor,
                            (m.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0));
        }
        if (member != null && out.size() == 2) out.add("  (no public member '" + member + "')");
        return String.join("\n", out);
    }

    private static String header(Type t, ClassModel m) {
        int a = t.access();
        String kind = kind(a);
        StringBuilder h = new StringBuilder(modifiers(a, true));
        h.append(kind.equals("annotation") ? "@interface" : kind).append(' ').append(t.simple());
        Optional<ClassSignature> sig = m.findAttribute(Attributes.signature())
                .map(s -> ClassSignature.parseFrom(s.signature().stringValue()));
        if (sig.isPresent()) {
            ClassSignature cs = sig.get();
            h.append(typeParams(cs.typeParameters()));
            String sup = type(cs.superclassSignature());
            if (!sup.equals("Object")
                    && !kind.equals("interface")
                    && !kind.equals("enum")
                    && !sup.startsWith("Enum<")) {
                h.append(" extends ").append(sup);
            }
            List<String> ifaces =
                    cs.superinterfaceSignatures().stream().map(LibraryApi::type).toList();
            if (!ifaces.isEmpty())
                h.append(kind.equals("interface") ? " extends " : " implements ")
                        .append(String.join(", ", ifaces));
        } else {
            m.superclass().map(c -> simpleInternal(c.asInternalName())).ifPresent(sup -> {
                if (!sup.equals("Object") && !sup.equals("Enum") && !sup.equals("Record"))
                    h.append(" extends ").append(sup);
            });
            List<String> ifaces = m.interfaces().stream()
                    .map(c -> simpleInternal(c.asInternalName()))
                    .toList();
            if (!ifaces.isEmpty() && !kind.equals("annotation")) {
                h.append(kind.equals("interface") ? " extends " : " implements ")
                        .append(String.join(", ", ifaces));
            }
        }
        return h.toString();
    }

    private static String method(MethodModel m, int access, String name, boolean ctor, boolean inInterface) {
        List<String> params = new ArrayList<>();
        String result;
        String typeParams = "";
        List<String> throwsList = new ArrayList<>();
        Optional<MethodSignature> sig = m.findAttribute(Attributes.signature())
                .map(s -> MethodSignature.parseFrom(s.signature().stringValue()));
        MethodTypeDesc desc = MethodTypeDesc.ofDescriptor(m.methodType().stringValue());
        if (sig.isPresent()) {
            MethodSignature ms = sig.get();
            typeParams = typeParams(ms.typeParameters());
            ms.arguments().forEach(arg -> params.add(type(arg)));
            result = type(ms.result());
            ms.throwableSignatures().forEach(x -> throwsList.add(type(x)));
        } else {
            desc.parameterList().forEach(p -> params.add(simple(p)));
            result = simple(desc.returnType());
        }
        if (throwsList.isEmpty()) {
            m.findAttribute(Attributes.exceptions())
                    .ifPresent(x -> x.exceptions().forEach(c -> throwsList.add(simpleInternal(c.asInternalName()))));
        }
        if (params.size() != desc.parameterCount()) {
            params.clear();
            desc.parameterList().forEach(p -> params.add(simple(p)));
        }
        List<String> names = parameterNames(m, access, desc.parameterCount());
        List<String> args = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            String p = params.get(i);
            if (i == params.size() - 1 && (access & ClassFile.ACC_VARARGS) != 0 && p.endsWith("[]")) {
                p = p.substring(0, p.length() - 2) + "...";
            }
            args.add(p + " " + names.get(i));
        }
        int shown = inInterface && (access & ClassFile.ACC_ABSTRACT) != 0 ? access & ~ClassFile.ACC_ABSTRACT : access;
        String mods = modifiers(shown, false);
        if (inInterface && (access & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_STATIC)) == 0) mods += "default ";
        return mods + (typeParams.isEmpty() ? "" : typeParams.strip() + " ") + (ctor ? "" : result + " ") + name + "("
                + String.join(", ", args) + ")"
                + (throwsList.isEmpty() ? "" : " throws " + String.join(", ", throwsList));
    }

    /** Names from {@code MethodParameters}, else the local variable table, else {@code p0, p1, …}. */
    private static List<String> parameterNames(MethodModel m, int access, int count) {
        List<String> out = new ArrayList<>();
        Optional<MethodParametersAttribute> mp = m.findAttribute(Attributes.methodParameters());
        if (mp.isPresent() && mp.get().parameters().size() == count) {
            for (MethodParameterInfo p : mp.get().parameters())
                out.add(p.name().map(n -> n.stringValue()).orElse("p" + out.size()));
            return out;
        }
        Map<Integer, String> bySlot = new HashMap<>();
        m.code().flatMap(c -> c.findAttribute(Attributes.localVariableTable())).ifPresent(lvt -> {
            for (LocalVariableInfo v : lvt.localVariables())
                bySlot.putIfAbsent(v.slot(), v.name().stringValue());
        });
        MethodTypeDesc desc = MethodTypeDesc.ofDescriptor(m.methodType().stringValue());
        int slot = (access & ClassFile.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < count; i++) {
            out.add(bySlot.getOrDefault(slot, "p" + i));
            ClassDesc p = desc.parameterType(i);
            slot += p.descriptorString().equals("J") || p.descriptorString().equals("D") ? 2 : 1;
        }
        return out;
    }

    private static boolean visible(int access) {
        return (access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }

    private static String modifiers(int access, boolean type) {
        StringBuilder s = new StringBuilder();
        if ((access & ACC_PUBLIC) != 0) s.append("public ");
        if ((access & ACC_PROTECTED) != 0) s.append("protected ");
        if ((access & ClassFile.ACC_STATIC) != 0) s.append("static ");
        if ((access & ClassFile.ACC_ABSTRACT) != 0 && (!type || (access & ClassFile.ACC_INTERFACE) == 0)) {
            s.append("abstract ");
        }
        if ((access & ClassFile.ACC_FINAL) != 0 && !(type && (access & ClassFile.ACC_ENUM) != 0)) s.append("final ");
        return s.toString();
    }

    private static String typeParams(List<Signature.TypeParam> params) {
        if (params.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (Signature.TypeParam p : params) {
            List<String> bounds = new ArrayList<>();
            p.classBound().ifPresent(b -> bounds.add(type(b)));
            p.interfaceBounds().forEach(b -> bounds.add(type(b)));
            bounds.remove("Object");
            out.add(p.identifier() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
        }
        return "<" + String.join(", ", out) + ">";
    }

    /** A signature as source spells it, classes by simple name. */
    static String type(Signature s) {
        return switch (s) {
            case Signature.BaseTypeSig b ->
                ClassDesc.ofDescriptor(String.valueOf(b.baseType())).displayName();
            case Signature.TypeVarSig v -> v.identifier();
            case Signature.ArrayTypeSig a -> type(a.componentSignature()) + "[]";
            case Signature.ClassTypeSig c -> {
                String outer =
                        c.outerType().map(LibraryApi::type).map(o -> o + ".").orElse("");
                String name = outer.isEmpty() ? simpleInternal(c.className()) : c.className();
                List<String> args = new ArrayList<>();
                for (Signature.TypeArg arg : c.typeArgs()) {
                    args.add(
                            switch (arg) {
                                case Signature.TypeArg.Unbounded u -> "?";
                                case Signature.TypeArg.Bounded b ->
                                    switch (b.wildcardIndicator()) {
                                        case EXTENDS -> "? extends " + type(b.boundType());
                                        case SUPER -> "? super " + type(b.boundType());
                                        case NONE -> type(b.boundType());
                                    };
                            });
                }
                yield outer + name + (args.isEmpty() ? "" : "<" + String.join(", ", args) + ">");
            }
        };
    }

    private static String simple(ClassDesc d) {
        return d.displayName().replace('$', '.');
    }

    private static String simpleInternal(String internal) {
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        return s.replace('$', '.');
    }

    /** The jars a spec lists as {@code coord \t path}. */
    static List<Jar> jars(List<String> rows) {
        List<Jar> out = new ArrayList<>();
        for (String row : rows) {
            int tab = row.indexOf('\t');
            if (tab > 0) out.add(new Jar(row.substring(0, tab), Path.of(row.substring(tab + 1))));
        }
        return out;
    }
}
