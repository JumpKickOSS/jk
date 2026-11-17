// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The JSON a route handler reads and writes, read from its compiled classes as Jackson's defaults
 * see them, so a class javac, kotlinc, scalac or groovyc wrote reads alike. A handler's body types
 * are the classes named anywhere in its generic return type and its request-body parameter's
 * ({@code @RequestBody}, Micronaut's {@code @Body}, JAX-RS's one parameter no binding annotation
 * claims): {@code ResponseEntity<Page<OwnerDto>>} names {@code OwnerDto}, and the library wrappers
 * drop out when no class of the workspace answers for them. A Kotlin {@code suspend} handler's
 * result is its continuation's type argument.
 *
 * <p>A class's properties are its record components, public fields, public getters ({@code getX},
 * {@code isX} returning {@code boolean}), setters, and any field or method carrying {@code
 * @JsonProperty}; {@code @JsonProperty("name")} on any of them, or on the constructor parameter of
 * that name (where kotlinc puts an annotation written on a {@code val}), renames the property, and
 * {@code @JsonIgnore} or the class's {@code @JsonIgnoreProperties} drops it. A class-level {@code
 * @JsonNaming} translates every name not given explicitly, and {@code @JsonAutoDetect(fieldVisibility
 * = ANY)} shows private fields. A Kotlin property {@code isActive} keeps its name, as
 * jackson-module-kotlin writes it; a Scala case class's fields with their same-named accessors are
 * its properties, as jackson-module-scala writes them; Groovy's {@code metaClass} is not one.
 * Lombok's getters are read where it compiled them.
 *
 * <p>What a class file cannot tell is left out: a naming strategy or visibility set on an {@code
 * ObjectMapper} or in configuration, mix-ins, custom serializers, {@code @JsonView}, {@code
 * @JsonUnwrapped} prefixes and polymorphic type ids.
 */
final class JsonDtos {

    private JsonDtos() {}

    /**
     * One property: its JSON name, the member of the class whose declaration stands for it (the
     * field behind it, else its getter or setter), and the internal names of the classes its value's
     * type names.
     */
    record Property(String name, String member, boolean field, List<String> types) {}

    /** One class as Jackson writes it: its properties and the supertypes whose properties it carries. */
    record Dto(List<Property> properties, List<String> supertypes) {}

    private static final int STATIC = 0x0008;
    private static final int PUBLIC = 0x0001;
    private static final int PRIVATE = 0x0002;
    private static final int TRANSIENT = 0x0080;
    private static final int SYNTHETIC = 0x1000;
    private static final int BRIDGE = 0x0040;
    private static final int INTERFACE = 0x0200;
    private static final int ENUM = 0x4000;
    private static final int ANNOTATION = 0x2000;

    private static final String SPRING = "Lorg/springframework/web/bind/annotation/";
    private static final Pattern HANDLER = Pattern.compile("L(?:org/springframework/web/bind/annotation/"
            + "(?:Get|Post|Put|Delete|Patch|Request)Mapping"
            + "|(?:jakarta|javax)/ws/rs/(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|Path)"
            + "|io/micronaut/http/annotation/(?:Get|Post|Put|Delete|Patch));");
    private static final Pattern JAX_RS = Pattern.compile("L(?:jakarta|javax)/ws/rs/");
    private static final Pattern VALIDATION =
            Pattern.compile("L(?:jakarta/validation|javax/validation|org/springframework/validation)/");
    private static final Set<String> BODY_PARAMS =
            Set.of(SPRING + "RequestBody;", "Lio/micronaut/http/annotation/Body;");
    private static final String CONTINUATION = "kotlin/coroutines/Continuation";

    /**
     * The internal names of the classes the bodies of handler {@code method} of {@code owner} name:
     * those of every method of that name carrying a mapping annotation. A Spring MVC {@code
     * @Controller}'s return is a view unless {@code @ResponseBody} (on the method or the class) or a
     * {@code ResponseEntity} makes it the body.
     */
    static List<String> bodyTypes(ClassModel owner, String method) {
        Set<String> out = new LinkedHashSet<>();
        List<Annotation> classAnnotations = annotations(owner);
        boolean views = has(classAnnotations, "Lorg/springframework/stereotype/Controller;")
                && !has(classAnnotations, SPRING + "RestController;")
                && !has(classAnnotations, SPRING + "ResponseBody;");
        for (MethodModel m : owner.methods()) {
            if (!m.methodName().equalsString(method) || (m.flags().flagsMask() & (SYNTHETIC | BRIDGE)) != 0) continue;
            List<Annotation> own = annotations(m);
            if (own.stream()
                    .noneMatch(a -> HANDLER.matcher(a.className().stringValue()).matches())) continue;
            boolean jaxRs = own.stream()
                    .anyMatch(a -> JAX_RS.matcher(a.className().stringValue()).lookingAt());
            MethodTypeDesc desc = m.methodTypeSymbol();
            MethodSignature sig = m.findAttribute(Attributes.signature())
                    .map(s -> MethodSignature.parseFrom(s.signature().stringValue()))
                    .orElse(null);
            List<Signature> params = new ArrayList<>();
            if (sig != null && sig.arguments().size() == desc.parameterCount()) params.addAll(sig.arguments());
            else for (ClassDesc p : desc.parameterList()) params.add(Signature.of(p));
            Signature result = sig != null ? sig.result() : Signature.of(desc.returnType());
            // A suspend function returns Object and hands its result to its last parameter.
            if (!params.isEmpty()
                    && params.getLast() instanceof Signature.ClassTypeSig c
                    && CONTINUATION.equals(internalName(c))) {
                params.removeLast();
                if (!c.typeArgs().isEmpty() && c.typeArgs().getFirst() instanceof Signature.TypeArg.Bounded b)
                    result = b.boundType();
            }
            boolean entity = has(own, SPRING + "ResponseBody;")
                    || names(result).contains("org/springframework/http/ResponseEntity");
            if (!views || entity) out.addAll(names(result));
            List<List<Annotation>> byParam = parameterAnnotations(m);
            for (int i = 0; i < params.size(); i++) {
                List<Annotation> pa = i < byParam.size() ? byParam.get(i) : List.of();
                boolean body = pa.stream()
                                .anyMatch(
                                        a -> BODY_PARAMS.contains(a.className().stringValue()))
                        || jaxRs
                                && pa.stream().allMatch(a -> VALIDATION
                                        .matcher(a.className().stringValue())
                                        .lookingAt());
                if (body) out.addAll(names(params.get(i)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code cm} as Jackson writes it; {@code null} for an enum (written as a string), an annotation
     * or a synthetic class.
     */
    static @Nullable Dto dto(ClassModel cm) {
        int flags = cm.flags().flagsMask();
        if ((flags & (ENUM | ANNOTATION | SYNTHETIC)) != 0) return null;
        List<Annotation> classAnnotations = annotations(cm);
        boolean kotlin = has(classAnnotations, "Lkotlin/Metadata;");
        boolean scala = has(classAnnotations, "Lscala/reflect/ScalaSignature;")
                || cm.attributes().stream().anyMatch(a -> a.attributeName().equalsString("TASTY"));
        Model model = new Model(classAnnotations);
        Map<String, String> renamedParams = constructorNames(cm);

        Set<String> accessors = new LinkedHashSet<>();
        for (MethodModel m : cm.methods()) {
            int f = m.flags().flagsMask();
            if ((f & (STATIC | SYNTHETIC | BRIDGE)) == 0 && m.methodTypeSymbol().parameterCount() == 0)
                accessors.add(m.methodName().stringValue());
        }
        Optional<RecordAttribute> record = cm.findAttribute(Attributes.record());
        if (record.isPresent()) {
            for (RecordComponentInfo c : record.get().components()) {
                List<Annotation> a = new ArrayList<>();
                c.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(x -> a.addAll(x.annotations()));
                c.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(x -> a.addAll(x.annotations()));
                Signature s = c.findAttribute(Attributes.signature())
                        .map(x -> Signature.parseFrom(x.signature().stringValue()))
                        .orElseGet(() -> Signature.of(
                                ClassDesc.ofDescriptor(c.descriptor().stringValue())));
                String name = c.name().stringValue();
                model.add(name, name, true, a, names(s));
            }
        }
        for (FieldModel fm : cm.fields()) {
            int f = fm.flags().flagsMask();
            if ((f & (STATIC | SYNTHETIC)) != 0) continue;
            String name = fm.fieldName().stringValue();
            List<Annotation> a = annotations(fm);
            Signature s = fm.findAttribute(Attributes.signature())
                    .map(x -> Signature.parseFrom(x.signature().stringValue()))
                    .orElseGet(() -> Signature.of(fm.fieldTypeSymbol()));
            if ((f & TRANSIENT) != 0 && explicit(a) == null) {
                model.field(name);
                continue;
            }
            boolean scalaField = scala && accessors.contains(name);
            boolean visible = (f & PUBLIC) != 0 || scalaField || model.fieldsVisible(f);
            model.add(name, name, visible, a, names(s));
            model.field(name);
        }
        boolean iface = (flags & INTERFACE) != 0;
        for (MethodModel m : cm.methods()) {
            int f = m.flags().flagsMask();
            if ((f & (STATIC | SYNTHETIC | BRIDGE)) != 0) continue;
            String name = m.methodName().stringValue();
            if (name.startsWith("<") || name.contains("$")) continue;
            MethodTypeDesc desc = m.methodTypeSymbol();
            List<Annotation> a = annotations(m);
            boolean isPublic = iface || (f & PUBLIC) != 0;
            MethodSignature sig = m.findAttribute(Attributes.signature())
                    .map(s -> MethodSignature.parseFrom(s.signature().stringValue()))
                    .orElse(null);
            String ret = desc.returnType().descriptorString();
            if (desc.parameterCount() == 0 && !ret.equals("V")) {
                if (ret.equals("Lgroovy/lang/MetaClass;")) continue;
                List<String> types = names(sig != null ? sig.result() : Signature.of(desc.returnType()));
                if (name.length() > 3 && name.startsWith("get") && !name.equals("getClass")) {
                    model.add(mangle(name.substring(3)), name, isPublic, a, types);
                } else if (name.length() > 2 && name.startsWith("is") && ret.equals("Z")) {
                    // kotlinc names the getter of `val isActive` isActive(), and jackson-module-kotlin keeps the name.
                    String internal = kotlin && model.hasField(name) ? name : mangle(name.substring(2));
                    model.add(internal, name, isPublic, a, types);
                } else if (record.isPresent() && model.has(name)) {
                    model.add(name, name, isPublic, a, types);
                } else if (scala && model.hasField(name)) {
                    model.add(name, name, isPublic, a, types);
                } else if (explicit(a) != null) {
                    model.add(name, name, true, a, types);
                }
            } else if (desc.parameterCount() == 1
                    && name.length() > 3
                    && name.startsWith("set")
                    && !desc.parameterType(0).descriptorString().equals("Lgroovy/lang/MetaClass;")) {
                Signature p = sig != null && sig.arguments().size() == 1
                        ? sig.arguments().getFirst()
                        : Signature.of(desc.parameterType(0));
                model.add(mangle(name.substring(3)), name, true, a, names(p));
            }
        }
        renamedParams.forEach(model::renameFromParameter);
        List<String> supers = new ArrayList<>();
        cm.superclass().ifPresent(s -> {
            String n = s.asInternalName();
            if (!n.equals("java/lang/Object") && !n.equals("java/lang/Record")) supers.add(n);
        });
        if (iface) for (var i : cm.interfaces()) supers.add(i.asInternalName());
        return new Dto(model.properties(), List.copyOf(supers));
    }

    /** {@code @JsonProperty("x")} on a constructor parameter, by the parameter's name, where the class file names it. */
    private static Map<String, String> constructorNames(ClassModel cm) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodModel m : cm.methods()) {
            if (!m.methodName().equalsString("<init>") || (m.flags().flagsMask() & SYNTHETIC) != 0) continue;
            List<List<Annotation>> byParam = parameterAnnotations(m);
            if (byParam.stream().allMatch(List::isEmpty)) continue;
            List<String> names = parameterNames(m);
            int offset = names.size() - byParam.size();
            for (int i = 0; i < byParam.size(); i++) {
                String explicit = explicit(byParam.get(i));
                int at = i + Math.max(0, offset);
                if (explicit != null
                        && !explicit.isEmpty()
                        && at < names.size()
                        && !names.get(at).isEmpty()) out.putIfAbsent(names.get(at), explicit);
            }
        }
        return out;
    }

    /** A method's parameter names: its {@code MethodParameters}, else its local variable table; empty where neither names one. */
    private static List<String> parameterNames(MethodModel m) {
        int count = m.methodTypeSymbol().parameterCount();
        Optional<MethodParametersAttribute> mp = m.findAttribute(Attributes.methodParameters());
        if (mp.isPresent() && mp.get().parameters().size() == count) {
            List<String> out = new ArrayList<>();
            for (MethodParameterInfo p : mp.get().parameters())
                out.add(p.name().map(n -> n.stringValue()).orElse(""));
            return out;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add("");
        m.code().flatMap(c -> c.findAttribute(Attributes.localVariableTable())).ifPresent(t -> {
            int slot = (m.flags().flagsMask() & STATIC) != 0 ? 0 : 1;
            List<ClassDesc> params = m.methodTypeSymbol().parameterList();
            int[] slots = new int[params.size()];
            for (int i = 0; i < params.size(); i++) {
                slots[i] = slot;
                String d = params.get(i).descriptorString();
                slot += d.equals("J") || d.equals("D") ? 2 : 1;
            }
            for (var v : t.localVariables()) {
                if (v.startPc() != 0) continue;
                for (int i = 0; i < slots.length; i++)
                    if (slots[i] == v.slot()) out.set(i, v.name().stringValue());
            }
        });
        return out;
    }

    /** The members of one class by the property they belong to (their internal name). */
    private static final class Model {
        final Map<String, List<Member>> byName = new LinkedHashMap<>();
        final Set<String> fields = new LinkedHashSet<>();
        final Set<String> ignored = new LinkedHashSet<>();
        final @Nullable String naming;
        final String fieldVisibility;

        record Member(
                String member, boolean visible, @Nullable String explicit, boolean ignore, List<String> types) {}

        Model(List<Annotation> classAnnotations) {
            String strategy = null;
            String visibility = "";
            for (Annotation a : classAnnotations) {
                String d = a.className().stringValue();
                if (d.endsWith("/JsonNaming;")) {
                    for (String v : values(a, "value")) strategy = v;
                } else if (d.endsWith("/JsonIgnoreProperties;")) {
                    ignored.addAll(values(a, "value"));
                } else if (d.endsWith("/JsonAutoDetect;")) {
                    for (String v : values(a, "fieldVisibility")) visibility = v;
                }
            }
            naming = strategy;
            fieldVisibility = visibility;
        }

        boolean fieldsVisible(int flags) {
            return switch (fieldVisibility) {
                case "ANY" -> true;
                case "NON_PRIVATE", "PROTECTED_AND_PUBLIC" -> (flags & PRIVATE) == 0;
                default -> false;
            };
        }

        void field(String name) {
            fields.add(name);
        }

        boolean hasField(String name) {
            return fields.contains(name);
        }

        boolean has(String name) {
            return byName.containsKey(name);
        }

        void add(String name, String member, boolean visible, List<Annotation> annotations, List<String> types) {
            String explicit = explicit(annotations);
            boolean ignore = false;
            for (Annotation a : annotations) {
                if (a.className().stringValue().endsWith("/JsonIgnore;"))
                    ignore = !values(a, "value").contains("false");
            }
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Member(member, visible || explicit != null, explicit, ignore, types));
        }

        void renameFromParameter(String name, String explicit) {
            List<Member> ms = byName.get(name);
            if (ms != null) ms.add(new Member(name, false, explicit, false, List.of()));
        }

        List<Property> properties() {
            List<Property> out = new ArrayList<>();
            for (Map.Entry<String, List<Member>> e : byName.entrySet()) {
                boolean visible = false;
                boolean ignore = false;
                String explicit = null;
                String member = null;
                List<String> types = new ArrayList<>();
                for (Member m : e.getValue()) {
                    visible |= m.visible();
                    ignore |= m.ignore();
                    if (m.explicit() != null && !m.explicit().isEmpty()) explicit = m.explicit();
                    for (String t : m.types()) if (!types.contains(t)) types.add(t);
                }
                if (!visible || ignore) continue;
                // The field behind a property is its declaration; a getter or setter stands in where there is none.
                boolean field = fields.contains(e.getKey())
                        || e.getValue().getFirst().member().equals(e.getKey());
                member = fields.contains(e.getKey())
                        ? e.getKey()
                        : e.getValue().getFirst().member();
                String name = explicit != null ? explicit : translate(e.getKey(), naming);
                if (ignored.contains(name)) continue;
                out.add(new Property(name, member, field, List.copyOf(types)));
            }
            return out;
        }
    }

    /** The name a {@code @JsonProperty} among {@code annotations} gives: empty for one with none, {@code null} for no such annotation. */
    private static @Nullable String explicit(List<Annotation> annotations) {
        for (Annotation a : annotations) {
            if (!a.className().stringValue().endsWith("/JsonProperty;")) continue;
            List<String> v = values(a, "value");
            return v.isEmpty() ? "" : v.getFirst();
        }
        return null;
    }

    /** An annotation element's values as strings: a string's text, an enum constant's name, a class's internal name, a boolean. */
    static List<String> values(Annotation a, String element) {
        List<String> out = new ArrayList<>();
        for (AnnotationElement e : a.elements()) if (e.name().equalsString(element)) value(e.value(), out);
        return out;
    }

    private static void value(AnnotationValue v, List<String> out) {
        switch (v) {
            case AnnotationValue.OfString s -> out.add(s.stringValue());
            case AnnotationValue.OfEnum e -> out.add(e.constantName().stringValue());
            case AnnotationValue.OfClass c -> {
                String d = c.className().stringValue();
                out.add(d.startsWith("L") && d.endsWith(";") ? d.substring(1, d.length() - 1) : d);
            }
            case AnnotationValue.OfBoolean b -> out.add(String.valueOf(b.booleanValue()));
            case AnnotationValue.OfArray arr -> {
                for (AnnotationValue x : arr.values()) value(x, out);
            }
            default -> {}
        }
    }

    static boolean has(List<Annotation> annotations, String desc) {
        for (Annotation a : annotations) if (a.className().equalsString(desc)) return true;
        return false;
    }

    /** Every annotation a class carries, retained at run time or not. */
    static List<Annotation> annotations(ClassModel cm) {
        List<Annotation> out = new ArrayList<>();
        cm.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        cm.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        return out;
    }

    private static List<Annotation> annotations(MethodModel m) {
        List<Annotation> out = new ArrayList<>();
        m.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        m.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        return out;
    }

    private static List<Annotation> annotations(FieldModel f) {
        List<Annotation> out = new ArrayList<>();
        f.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        f.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> out.addAll(a.annotations()));
        return out;
    }

    private static List<List<Annotation>> parameterAnnotations(MethodModel m) {
        List<List<Annotation>> out = new ArrayList<>();
        m.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .ifPresent(a -> a.parameterAnnotations().forEach(p -> out.add(new ArrayList<>(p))));
        m.findAttribute(Attributes.runtimeInvisibleParameterAnnotations()).ifPresent(a -> {
            List<List<Annotation>> inv = a.parameterAnnotations();
            for (int i = 0; i < inv.size(); i++) {
                if (i < out.size()) out.get(i).addAll(inv.get(i));
                else out.add(new ArrayList<>(inv.get(i)));
            }
        });
        return out;
    }

    /** The internal names of every class {@code sig} names, type arguments and array components included. */
    static List<String> names(Signature sig) {
        Set<String> out = new LinkedHashSet<>();
        collect(sig, out);
        return List.copyOf(out);
    }

    private static void collect(Signature sig, Set<String> out) {
        switch (sig) {
            case Signature.ClassTypeSig c -> {
                out.add(internalName(c));
                for (Signature.TypeArg t : c.typeArgs()) {
                    if (t instanceof Signature.TypeArg.Bounded b) collect(b.boundType(), out);
                }
            }
            case Signature.ArrayTypeSig a -> collect(a.componentSignature(), out);
            default -> {}
        }
    }

    /** {@code com/acme/Outer$Inner} for a class type, its outer types' names joined as the class file names it. */
    private static String internalName(Signature.ClassTypeSig c) {
        return c.outerType().map(o -> internalName(o) + "$" + c.className()).orElse(c.className());
    }

    /** Jackson's default bean name: {@code BirthDate} as {@code birthDate}, {@code URL} as {@code url}. */
    static String mangle(String suffix) {
        StringBuilder s = new StringBuilder(suffix);
        for (int i = 0; i < s.length() && Character.isUpperCase(s.charAt(i)); i++) {
            s.setCharAt(i, Character.toLowerCase(s.charAt(i)));
        }
        return s.toString();
    }

    /** {@code name} as {@code @JsonNaming(strategy)} writes it; unchanged for a strategy not listed. */
    static String translate(String name, @Nullable String strategy) {
        if (strategy == null) return name;
        String s = strategy.substring(
                Math.max(strategy.lastIndexOf('.'), Math.max(strategy.lastIndexOf('$'), strategy.lastIndexOf('/')))
                        + 1);
        return switch (s) {
            case "SnakeCaseStrategy", "SNAKE_CASE" -> separated(name, '_', false);
            case "UpperSnakeCaseStrategy", "UPPER_SNAKE_CASE" -> separated(name, '_', true);
            case "KebabCaseStrategy", "KEBAB_CASE" -> separated(name, '-', false);
            case "LowerDotCaseStrategy", "LOWER_DOT_CASE" -> separated(name, '.', false);
            case "LowerCaseStrategy", "LOWER_CASE" -> name.toLowerCase(Locale.ROOT);
            case "UpperCamelCaseStrategy", "UPPER_CAMEL_CASE" ->
                name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            default -> name;
        };
    }

    private static String separated(String name, char sep, boolean upper) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && out.charAt(out.length() - 1) != sep) out.append(sep);
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        String s = out.toString();
        return upper ? s.toUpperCase(Locale.ROOT) : s;
    }
}
