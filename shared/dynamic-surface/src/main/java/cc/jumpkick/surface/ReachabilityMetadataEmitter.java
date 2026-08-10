// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link DynamicSurface} as GraalVM's unified {@code reachability-metadata.json}.
 *
 * <p>Written by hand rather than through a JSON library: this module is dependency-free so the
 * shrink worker and the native-image driver can both link it, and the schema is a handful of
 * arrays of flat objects.
 *
 * <p>{@link DynamicSurface.Kind#GENERIC_REFLECTION} produces nothing. It exists because R8 drops
 * a class's generic signature unless the class is kept; Graal retains signatures without being
 * asked, so there is nothing to declare.
 */
public final class ReachabilityMetadataEmitter {

    private ReachabilityMetadataEmitter() {}

    public static String emit(DynamicSurface surface) {
        List<String> reflection = new ArrayList<>();
        List<String> resources = new ArrayList<>();
        List<String> serialization = new ArrayList<>();
        List<String> jni = new ArrayList<>();

        for (DynamicSurface.Entry entry : surface.entries()) {
            switch (entry.kind()) {
                case REFLECTIVE_TYPE, SERVICE_IMPLEMENTATION -> reflection.add(typeObject(entry.name(), true));
                case REFLECTIVE_MEMBER -> reflection.add(memberObject(entry));
                case JNI_TYPE -> jni.add(typeObject(entry.name(), true));
                case JNI_MEMBER -> jni.add(memberObject(entry));
                case SERIALIZATION_TYPE -> serialization.add(typeObject(entry.name(), false));
                // GraalVM's unified schema: resources are a flat glob array, and a proxy is a
                // reflection entry with a map-shaped type (JK-1752).
                case RESOURCE -> resources.add("{\"glob\":" + quote(entry.name()) + "}");
                case PROXY_INTERFACE -> reflection.add("{\"type\":{\"proxy\":[" + quote(entry.name()) + "]}}");
                // Graal keeps generic signatures without being told.
                case GENERIC_REFLECTION -> {}
            }
        }

        List<String> sections = new ArrayList<>();
        addArray(sections, "reflection", reflection);
        addArray(sections, "jni", jni);
        addArray(sections, "serialization", serialization);
        addArray(sections, "resources", resources);
        return "{\n" + String.join(",\n", sections) + (sections.isEmpty() ? "" : "\n") + "}\n";
    }

    private static String typeObject(String name, boolean allDeclared) {
        if (!allDeclared) return "{\"type\":" + quote(name) + "}";
        return "{\"type\":" + quote(name) + ",\"allDeclaredFields\":true,\"allDeclaredMethods\":true,"
                + "\"allDeclaredConstructors\":true}";
    }

    /**
     * Members tagged {@code f:}/{@code m:} emit under {@code fields} or {@code methods}; an
     * untagged name (older surface JSON) is of unknown kind and emits under both, except
     * initializers, which can only be methods.
     */
    private static String memberObject(DynamicSurface.Entry entry) {
        if (entry.members().isEmpty()) return typeObject(entry.name(), true);
        Set<String> fields = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        for (String member : entry.members()) {
            if (member.startsWith("f:")) {
                fields.add(member.substring(2));
            } else if (member.startsWith("m:")) {
                methods.add(member.substring(2));
            } else if (member.equals("<init>") || member.equals("<clinit>")) {
                methods.add(member);
            } else {
                fields.add(member);
                methods.add(member);
            }
        }
        StringBuilder sb = new StringBuilder("{\"type\":").append(quote(entry.name()));
        appendMembers(sb, "fields", fields);
        appendMembers(sb, "methods", methods);
        return sb.append('}').toString();
    }

    private static void appendMembers(StringBuilder sb, String key, Set<String> names) {
        if (names.isEmpty()) return;
        sb.append(",\"").append(key).append("\":[");
        boolean first = true;
        for (String name : names) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(quote(name)).append('}');
        }
        sb.append(']');
    }

    private static void addArray(List<String> sections, String key, List<String> values) {
        if (values.isEmpty()) return;
        sections.add("  " + quote(key) + ": [\n    " + String.join(",\n    ", values) + "\n  ]");
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
