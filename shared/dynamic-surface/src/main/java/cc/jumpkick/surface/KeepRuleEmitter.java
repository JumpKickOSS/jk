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
        for (DynamicSurface.Entry entry : surface.entries()) {
            String rule = rule(entry);
            if (rule == null) continue;
            if (!entry.origin().isBlank()) {
                out.append("# ").append(entry.origin()).append('\n');
            }
            out.append(rule).append('\n');
        }
        return out.toString();
    }

    private static String rule(DynamicSurface.Entry entry) {
        return switch (entry.kind()) {
            // A resource is not a class; R8 passes non-class entries through untouched.
            case RESOURCE -> null;
            case REFLECTIVE_MEMBER -> {
                if (entry.members().isEmpty()) yield "-keep class " + entry.name() + " { *; }";
                // A member spec needs a type: `*** name;` is any field of that name, and
                // `*** name(...);` any method. The model records a name without saying which,
                // so emit both rather than guess and drop the one that mattered.
                StringJoiner members = new StringJoiner(" ");
                for (String member : entry.members()) {
                    members.add("*** " + member + ";");
                    members.add("*** " + member + "(...);");
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
