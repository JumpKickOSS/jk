// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

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
            case RESOURCE, RESOURCE_PATTERN -> null;
            case REFLECTIVE_MEMBER, JNI_MEMBER -> {
                if (entry.members().isEmpty()) yield "-keep class " + entry.name() + " { *; }";
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
                yield "-keep class " + entry.name() + " { " + members + " }";
            }
            case PROXY_INTERFACE -> "-keep interface " + entry.name() + " { *; }";
            case SERIALIZATION_TYPE ->
                "-keepclassmembers class " + entry.name()
                        + " { java.lang.Object writeReplace(); java.lang.Object readResolve(); <init>(...); }";
            // Everything else needs the class itself retained, members included.
            case REFLECTIVE_TYPE, GENERIC_REFLECTION, SERVICE_IMPLEMENTATION, JNI_TYPE ->
                "-keep class " + entry.name() + " { *; }";
        };
    }
}
