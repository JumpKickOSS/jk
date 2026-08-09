// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.util.ArrayList;
import java.util.List;

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
        List<String> proxies = new ArrayList<>();

        for (DynamicSurface.Entry entry : surface.entries()) {
            switch (entry.kind()) {
                case REFLECTIVE_TYPE, SERVICE_IMPLEMENTATION -> reflection.add(typeObject(entry.name(), true));
                case REFLECTIVE_MEMBER -> reflection.add(memberObject(entry));
                case JNI_TYPE -> jni.add(typeObject(entry.name(), true));
                case SERIALIZATION_TYPE -> serialization.add(typeObject(entry.name(), false));
                case RESOURCE -> resources.add("{\"glob\":" + quote(entry.name()) + "}");
                case PROXY_INTERFACE -> proxies.add("{\"interfaces\":[" + quote(entry.name()) + "]}");
                // Graal keeps generic signatures without being told.
                case GENERIC_REFLECTION -> {}
            }
        }

        List<String> sections = new ArrayList<>();
        addArray(sections, "reflection", reflection);
        addArray(sections, "jni", jni);
        addArray(sections, "serialization", serialization);
        addArray(sections, "reflection-proxies", proxies);
        if (!resources.isEmpty()) {
            sections.add("  " + quote("resources") + ": {\"includes\": [" + String.join(", ", resources) + "]}");
        }
        return "{\n" + String.join(",\n", sections) + (sections.isEmpty() ? "" : "\n") + "}\n";
    }

    private static String typeObject(String name, boolean allDeclared) {
        if (!allDeclared) return "{\"type\":" + quote(name) + "}";
        return "{\"type\":" + quote(name) + ",\"allDeclaredFields\":true,\"allDeclaredMethods\":true,"
                + "\"allDeclaredConstructors\":true}";
    }

    private static String memberObject(DynamicSurface.Entry entry) {
        if (entry.members().isEmpty()) return typeObject(entry.name(), true);
        StringBuilder methods = new StringBuilder();
        for (String member : entry.members()) {
            if (methods.length() > 0) methods.append(',');
            methods.append("{\"name\":").append(quote(member)).append('}');
        }
        return "{\"type\":" + quote(entry.name()) + ",\"methods\":[" + methods + "]}";
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
