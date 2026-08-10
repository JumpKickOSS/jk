// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.util.Set;
import java.util.StringJoiner;

/**
 * A {@link DynamicSurface} as ProGuard/R8 keep rules.
 *
 * <p>Rules are conservative and readable on purpose: the file ships next to the artifact and is
 * the first thing anyone reads when R8 kept something surprising, so a rule that is slightly
 * broader but obvious beats a narrow one nobody can follow.
 */
public final class KeepRuleEmitter {

    private KeepRuleEmitter() {}

    public static String emit(DynamicSurface surface) {
        StringBuilder out = new StringBuilder();
        String currentOrigin = null;
        for (DynamicSurface.Entry entry : surface.entries()) {
            String rule = rule(entry);
            if (rule == null) continue;
            // One comment per run of entries sharing an origin: a few hundred rules from the same
            // source should not carry a few hundred identical comments.
            if (!entry.origin().isBlank() && !entry.origin().equals(currentOrigin)) {
                out.append("# ").append(entry.origin()).append('\n');
                currentOrigin = entry.origin();
            }
            out.append(rule).append('\n');
        }
        return out.toString();
    }

    private static String rule(DynamicSurface.Entry entry) {
        return switch (entry.kind()) {
            // A resource is not a class; R8 passes non-class entries through untouched.
            case RESOURCE, RESOURCE_PATTERN, RESOURCE_EXCLUDE_PATTERN -> null;
            case REFLECTIVE_MEMBER, JNI_MEMBER -> {
                String cls = className(entry.name());
                if (cls == null) yield null;
                if (entry.members().isEmpty()) yield "-keep class " + cls + " { *; }";
                // A member spec needs a type: `*** name;` is any field of that name, and
                // `*** name(...);` any method. Tagged members (`f:`/`m:`) emit the one form they
                // name; an untagged name (older surface JSON) does not say which, so emit both
                // rather than guess and drop the one that mattered.
                //
                // Initializers are the exception — they have no return type, and Graal's
                // reflect-config lists constructors under `methods` as `<init>`.
                StringJoiner members = new StringJoiner(" ");
                for (String member : entry.members()) {
                    if (member.startsWith("f:")) {
                        members.add("*** " + member.substring(2) + ";");
                        continue;
                    }
                    boolean method = member.startsWith("m:");
                    String name = method ? member.substring(2) : member;
                    if (name.equals("<init>") || name.equals("<clinit>")) {
                        members.add(name + "(...);");
                        continue;
                    }
                    if (!method) members.add("*** " + name + ";");
                    members.add("*** " + name + "(...);");
                }
                yield "-keep class " + cls + " { " + members + " }";
            }
            case PROXY_INTERFACE -> {
                // One proxy declaration carries its whole ordered interface list, comma-joined
                // (JK-1799); R8 has no proxy concept, so each interface gets its own keep.
                StringBuilder rules = new StringBuilder();
                for (String iface : entry.name().split(",")) {
                    String cls = className(iface);
                    if (cls == null) continue;
                    if (rules.length() > 0) rules.append('\n');
                    rules.append("-keep interface ").append(cls).append(" { *; }");
                }
                yield rules.isEmpty() ? null : rules.toString();
            }
            case SERIALIZATION_TYPE -> {
                String cls = className(entry.name());
                if (cls == null) yield null;
                StringBuilder rules = new StringBuilder("-keepclassmembers class ")
                        .append(cls)
                        .append(" { java.lang.Object writeReplace(); java.lang.Object readResolve();")
                        .append(" <init>(...); }");
                // The declared deserialization constructor lives on another class ("c:" member,
                // JK-1801); its constructors are invoked reflectively, so they need keeping too.
                for (String member : entry.members()) {
                    String ctor = member.startsWith("c:") ? className(member.substring(2)) : null;
                    if (ctor != null) {
                        rules.append("\n-keepclassmembers class ").append(ctor).append(" { <init>(...); }");
                    }
                }
                yield rules.toString();
            }
            // Everything else needs the class itself retained, members included.
            case REFLECTIVE_TYPE, GENERIC_REFLECTION, SERVICE_IMPLEMENTATION, JNI_TYPE -> {
                String cls = className(entry.name());
                yield cls == null ? null : "-keep class " + cls + " { *; }";
            }
        };
    }

    private static final Set<String> PRIMITIVES =
            Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double", "void");

    /**
     * The class a keep rule can name for a metadata entry, or null when there is none. Library
     * metadata routinely registers arrays ({@code {"name":"byte[]"}}, {@code [Ljava.lang.String;})
     * and primitives; interpolated verbatim they produce {@code -keep class byte[]}, which is not
     * ProGuard syntax and aborts R8 (JK-1754). Primitives and primitive arrays need no keeping;
     * a reference array keeps its element class. The reachability emitter is untouched — Graal
     * accepts the original names, so they pass through verbatim there.
     */
    static String className(String name) {
        String n = name;
        int dims = 0;
        while (n.startsWith("[")) {
            n = n.substring(1);
            dims++;
        }
        if (dims > 0) {
            // Descriptor form: [Ljava.lang.String; (dotted or slashed element) or a primitive
            // letter, which has no class to keep.
            if (n.length() > 2 && n.charAt(0) == 'L' && n.endsWith(";")) {
                n = n.substring(1, n.length() - 1).replace('/', '.');
            } else {
                return null;
            }
        }
        while (n.endsWith("[]")) n = n.substring(0, n.length() - 2);
        if (n.isBlank() || PRIMITIVES.contains(n)) return null;
        return n;
    }
}
