// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A {@link DynamicSurface} as GraalVM's unified {@code reachability-metadata.json}.
 *
 * <p>The document is assembled here rather than through a tree writer because Graal's schema is a
 * handful of arrays of flat objects and the on-disk layout is stable; every object in it is built
 * by {@link JsonFields}, the one JSON object writer.
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
                case SERIALIZATION_TYPE -> serialization.add(serializationObject(entry));
                // GraalVM's unified schema: resources are a flat glob array, and a proxy is a
                // reflection entry with a map-shaped type.
                case RESOURCE ->
                    resources.add(
                            JsonFields.object().string("glob", entry.name()).finish());
                case PROXY_INTERFACE -> reflection.add(proxyObject(entry.name()));
                // The unified schema has no regex resource form and no excludes; both ride in the
                // split-format resource-config.json from emitResourceConfig.
                case RESOURCE_PATTERN, RESOURCE_EXCLUDE_PATTERN -> {}
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

    /**
     * {@link DynamicSurface.Kind#RESOURCE_PATTERN} and
     * {@link DynamicSurface.Kind#RESOURCE_EXCLUDE_PATTERN} entries as a legacy split-format
     * {@code resource-config.json}. The unified schema only has include globs: a regex emitted
     * as a glob matches nothing, and excludes have no unified shape at all;
     * native-image still honors the split form, so both ship in this sidecar. Empty string when
     * the surface has neither.
     */
    public static String emitResourceConfig(DynamicSurface surface) {
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (DynamicSurface.Entry entry : surface.entries()) {
            switch (entry.kind()) {
                case RESOURCE_PATTERN -> includes.add(pattern(entry.name()));
                case RESOURCE_EXCLUDE_PATTERN -> excludes.add(pattern(entry.name()));
                default -> {}
            }
        }
        if (includes.isEmpty() && excludes.isEmpty()) return "";
        JsonFields resources = JsonFields.object();
        if (!includes.isEmpty()) resources.token("includes", "[\n  " + String.join(",\n  ", includes) + "\n]");
        if (!excludes.isEmpty()) resources.token("excludes", "[\n  " + String.join(",\n  ", excludes) + "\n]");
        return JsonFields.object().token("resources", resources.finish()).finish() + "\n";
    }

    private static String pattern(String regex) {
        return JsonFields.object().string("pattern", regex).finish();
    }

    /**
     * {@code name} is the ordered, comma-joined interface list of one proxy declaration —
     * emitted whole, because Graal matches proxy registrations by exact ordered list.
     */
    private static String proxyObject(String name) {
        String proxy =
                JsonFields.object().array("proxy", List.of(name.split(","))).finish();
        return JsonFields.object().token("type", proxy).finish();
    }

    /**
     * A serialization registration, with its declared deserialization constructor when the entry
     * carries a {@code c:}-tagged member. Merging can in principle union two different
     * declarations; the first in sorted order wins — the schema has room for one.
     */
    private static String serializationObject(DynamicSurface.Entry entry) {
        for (String member : entry.members()) {
            if (member.startsWith("c:")) {
                return JsonFields.object()
                        .string("type", entry.name())
                        .string("customTargetConstructorClass", member.substring(2))
                        .finish();
            }
        }
        return typeObject(entry.name(), false);
    }

    private static String typeObject(String name, boolean allDeclared) {
        JsonFields type = JsonFields.object().string("type", name);
        if (allDeclared) {
            type.bool("allDeclaredFields", true)
                    .bool("allDeclaredMethods", true)
                    .bool("allDeclaredConstructors", true);
        }
        return type.finish();
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
        JsonFields type = JsonFields.object().string("type", entry.name());
        appendMembers(type, "fields", fields);
        appendMembers(type, "methods", methods);
        return type.finish();
    }

    private static void appendMembers(JsonFields type, String key, Set<String> names) {
        if (names.isEmpty()) return;
        StringJoiner members = new StringJoiner(",", "[", "]");
        for (String name : names)
            members.add(JsonFields.object().string("name", name).finish());
        type.token(key, members.toString());
    }

    private static void addArray(List<String> sections, String key, List<String> values) {
        if (values.isEmpty()) return;
        sections.add("  " + Jsonl.quote(key) + ": [\n    " + String.join(",\n    ", values) + "\n  ]");
    }
}
