// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import static cc.jumpkick.surface.DynamicSurface.Kind.JNI_MEMBER;
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
            case "reflect-config.json" -> reflection(root, origin, REFLECTIVE_TYPE, REFLECTIVE_MEMBER, out);
            case "jni-config.json" -> reflection(root, origin, JNI_TYPE, JNI_MEMBER, out);
            case "serialization-config.json" -> reflection(root, origin, SERIALIZATION_TYPE, null, out);
            case "proxy-config.json" -> proxies(root, origin, out);
            case "resource-config.json" -> resources(root, origin, out);
            case "reachability-metadata.json" -> {
                reflection(Json.list(root, "reflection"), origin, REFLECTIVE_TYPE, REFLECTIVE_MEMBER, out);
                reflection(Json.list(root, "jni"), origin, JNI_TYPE, JNI_MEMBER, out);
                reflection(Json.list(root, "serialization"), origin, SERIALIZATION_TYPE, null, out);
                // Not part of GraalVM's unified schema (proxies live inside "reflection"), but
                // files jk emitted before JK-1752 used this section — keep reading them.
                proxies(Json.list(root, "reflection-proxies"), origin, out);
                resources(Json.get(root, "resources"), origin, out);
            }
            default -> {}
        }
        return DynamicSurface.empty().merge(new DynamicSurface(out));
    }

    /**
     * {@code [{"name": …, "fields": [{"name": …}], "methods": [{"name": …}]}]}. A type asking only
     * for named members becomes a member entry of {@code memberKind} — which carries the
     * originating section, so a jni-config member round-trips into {@code jni}, not
     * {@code reflection} (JK-1779); anything broader keeps the whole type. A null
     * {@code memberKind} never demotes: serialization registration is per-type in GraalVM's
     * schema, so named members still register the type itself.
     */
    private static void reflection(
            Object root,
            String origin,
            DynamicSurface.Kind typeKind,
            DynamicSurface.Kind memberKind,
            List<DynamicSurface.Entry> out) {
        if (!(root instanceof List<?> items)) return;
        for (Object item : items) {
            String name = Json.str(item, "name");
            if (name == null) name = Json.str(item, "type");
            if (name == null || name.isBlank()) {
                // Unified-schema proxies are reflection entries with a map-shaped type:
                // {"type": {"proxy": ["a.B", "c.D"]}} (JK-1752).
                for (Object iface : Json.list(Json.map(item, "type"), "proxy")) {
                    if (iface instanceof String s && !s.isBlank()) {
                        out.add(DynamicSurface.Entry.type(PROXY_INTERFACE, s, origin));
                    }
                }
                continue;
            }

            Set<String> members = new TreeSet<>();
            for (Object field : Json.list(item, "fields")) addName(field, DynamicSurface::fieldMember, members);
            for (Object method : Json.list(item, "methods")) addName(method, DynamicSurface::methodMember, members);

            boolean wholeType = memberKind == null
                    || members.isEmpty()
                    || isTrue(item, "allDeclaredFields")
                    || isTrue(item, "allDeclaredMethods")
                    || isTrue(item, "allDeclaredConstructors")
                    || isTrue(item, "allPublicMethods")
                    || isTrue(item, "allPublicFields")
                    || isTrue(item, "unsafeAllocated");

            out.add(
                    wholeType
                            ? DynamicSurface.Entry.type(typeKind, name, origin)
                            : new DynamicSurface.Entry(memberKind, name, members, origin));
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
     * Three shapes in the wild: {@code {"resources": {"includes": [{"pattern": …}]}}} in the split
     * schema, the same nested under the unified document, and a bare {@code [{"glob": …}]} list —
     * which is what the tracing agent writes.
     */
    private static void resources(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (root == null) return;
        Object includes = root;
        if (!(root instanceof List<?>)) {
            Object holder = Json.map(root, "resources");
            includes = Json.list(holder != null ? holder : root, "includes");
        }
        if (!(includes instanceof List<?> items)) return;
        for (Object include : items) {
            String pattern = Json.str(include, "pattern");
            if (pattern == null) pattern = Json.str(include, "glob");
            if (pattern != null && !pattern.isBlank()) {
                out.add(DynamicSurface.Entry.type(RESOURCE, pattern, origin));
            }
        }
    }

    private static void addName(Object member, java.util.function.UnaryOperator<String> tag, Set<String> sink) {
        String name = Json.str(member, "name");
        if (name != null && !name.isBlank()) sink.add(tag.apply(name));
    }

    private static boolean isTrue(Object holder, String key) {
        return holder instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(key));
    }
}
