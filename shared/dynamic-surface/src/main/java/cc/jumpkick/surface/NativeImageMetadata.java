// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static cc.jumpkick.surface.DynamicSurface.Kind.JNI_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.PROXY_INTERFACE;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_MEMBER;
import static cc.jumpkick.surface.DynamicSurface.Kind.REFLECTIVE_TYPE;
import static cc.jumpkick.surface.DynamicSurface.Kind.RESOURCE;
import static cc.jumpkick.surface.DynamicSurface.Kind.SERIALIZATION_TYPE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Library-published GraalVM metadata, read into a {@link DynamicSurface}.
 *
 * <p>Libraries ship `META-INF/native-image/<group>/<artifact>/` describing their own reflective
 * surface. `native-image` finds it on the classpath by itself; R8 has no equivalent and ignores
 * it, so the same facts have to reach it as keep rules. Composing this is free — the data is
 * already in the jars, and no application run is involved.
 *
 * <p>Both schema generations are read: the split files (`reflect-config.json`,
 * `resource-config.json`, `proxy-config.json`, `serialization-config.json`, `jni-config.json`)
 * and the unified `reachability-metadata.json`.
 */
public final class NativeImageMetadata {

    /** File names under {@code META-INF/native-image/**} this reads. */
    public static final Set<String> CONFIG_FILES = Set.of(
            "reflect-config.json",
            "jni-config.json",
            "proxy-config.json",
            "resource-config.json",
            "serialization-config.json",
            "reachability-metadata.json");

    private NativeImageMetadata() {}

    /** True when {@code entryName} is a metadata file this reads. */
    public static boolean isMetadataFile(String entryName) {
        if (!entryName.startsWith("META-INF/native-image/") || entryName.endsWith("/")) return false;
        int slash = entryName.lastIndexOf('/');
        return CONFIG_FILES.contains(entryName.substring(slash + 1));
    }

    /**
     * Parse one metadata file. {@code entryName} selects the schema; {@code origin} is recorded on
     * every entry so a rule can be traced back to the library that asked for it.
     *
     * <p>A malformed file yields an empty surface rather than failing the build: a third party's
     * broken metadata should not stop a user packaging their application.
     */
    public static DynamicSurface parse(String entryName, String body, String origin) {
        Object root;
        try {
            root = Json.parse(body);
        } catch (RuntimeException e) {
            return DynamicSurface.empty();
        }
        String file = entryName.substring(entryName.lastIndexOf('/') + 1);
        List<DynamicSurface.Entry> out = new ArrayList<>();
        switch (file) {
            case "reflect-config.json" -> reflection(root, origin, REFLECTIVE_TYPE, out);
            case "jni-config.json" -> reflection(root, origin, JNI_TYPE, out);
            case "serialization-config.json" -> reflection(root, origin, SERIALIZATION_TYPE, out);
            case "proxy-config.json" -> proxies(root, origin, out);
            case "resource-config.json" -> resources(root, origin, out);
            case "reachability-metadata.json" -> {
                reflection(Json.list(root, "reflection"), origin, REFLECTIVE_TYPE, out);
                reflection(Json.list(root, "jni"), origin, JNI_TYPE, out);
                reflection(Json.list(root, "serialization"), origin, SERIALIZATION_TYPE, out);
                proxies(Json.list(root, "reflection-proxies"), origin, out);
                resources(Json.map(root, "resources"), origin, out);
            }
            default -> {}
        }
        return DynamicSurface.empty().merge(new DynamicSurface(out));
    }

    /**
     * {@code [{"name": …, "fields": [{"name": …}], "methods": [{"name": …}]}]}. A type asking only
     * for named members becomes a member entry; anything broader keeps the whole type.
     */
    private static void reflection(
            Object root, String origin, DynamicSurface.Kind kind, List<DynamicSurface.Entry> out) {
        if (!(root instanceof List<?> items)) return;
        for (Object item : items) {
            String name = Json.str(item, "name");
            if (name == null) name = Json.str(item, "type");
            if (name == null || name.isBlank()) continue;

            Set<String> members = new TreeSet<>();
            for (Object field : Json.list(item, "fields")) addName(field, members);
            for (Object method : Json.list(item, "methods")) addName(method, members);

            boolean wholeType = members.isEmpty()
                    || isTrue(item, "allDeclaredFields")
                    || isTrue(item, "allDeclaredMethods")
                    || isTrue(item, "allDeclaredConstructors")
                    || isTrue(item, "allPublicMethods")
                    || isTrue(item, "allPublicFields")
                    || isTrue(item, "unsafeAllocated");

            out.add(
                    wholeType
                            ? DynamicSurface.Entry.type(kind, name, origin)
                            : new DynamicSurface.Entry(REFLECTIVE_MEMBER, name, members, origin));
        }
    }

    /** {@code [{"interfaces": ["a.B", "c.D"]}]} — each interface is its own entry. */
    private static void proxies(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (!(root instanceof List<?> items)) return;
        for (Object item : items) {
            for (Object iface : Json.list(item, "interfaces")) {
                if (iface instanceof String s && !s.isBlank()) {
                    out.add(DynamicSurface.Entry.type(PROXY_INTERFACE, s, origin));
                }
            }
        }
    }

    /**
     * {@code {"resources": {"includes": [{"pattern": …}]}}} in the split schema, and
     * {@code {"includes": [{"glob": …}]}} in the unified one.
     */
    private static void resources(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (root == null) return;
        Object holder = Json.map(root, "resources");
        Object includes = holder != null ? holder : root;
        for (Object include : Json.list(includes, "includes")) {
            String pattern = Json.str(include, "pattern");
            if (pattern == null) pattern = Json.str(include, "glob");
            if (pattern != null && !pattern.isBlank()) {
                out.add(DynamicSurface.Entry.type(RESOURCE, pattern, origin));
            }
        }
    }

    private static void addName(Object member, Set<String> sink) {
        String name = Json.str(member, "name");
        if (name != null && !name.isBlank()) sink.add(name);
    }

    private static boolean isTrue(Object holder, String key) {
        return holder instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(key));
    }
}
