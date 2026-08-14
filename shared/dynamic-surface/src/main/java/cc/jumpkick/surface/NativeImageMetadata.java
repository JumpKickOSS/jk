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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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
            case "serialization-config.json" -> serialization(root, origin, out);
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
                addProxy(Json.list(Json.map(item, "type"), "proxy"), origin, out);
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

            if (memberKind == null) {
                // Serialization mode: the declared deserialization constructor rides along as a
                // tagged member so it survives the round trip (JK-1801).
                Set<String> extras = new TreeSet<>();
                String ctor = Json.str(item, "customTargetConstructorClass");
                if (ctor != null && !ctor.isBlank()) extras.add(DynamicSurface.customConstructorMember(ctor));
                out.add(new DynamicSurface.Entry(typeKind, name, extras, origin));
                continue;
            }
            out.add(
                    wholeType
                            ? DynamicSurface.Entry.type(typeKind, name, origin)
                            : new DynamicSurface.Entry(memberKind, name, members, origin));
        }
    }

    /**
     * Two serialization-config generations: the legacy flat array of {@code {"name": …}}, and the
     * newer agent wrapper {@code {"types": […], "lambdaCapturingTypes": […], "proxies": […]}}
     * (JK-1778). Wrapper proxies are arrays of interface names and register proxy classes for
     * serialization; they are read as proxy entries so both emitters cover them.
     */
    private static void serialization(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (root instanceof Map<?, ?>) {
            reflection(Json.list(root, "types"), origin, SERIALIZATION_TYPE, null, out);
            reflection(Json.list(root, "lambdaCapturingTypes"), origin, SERIALIZATION_TYPE, null, out);
            proxies(Json.list(root, "proxies"), origin, out);
            return;
        }
        reflection(root, origin, SERIALIZATION_TYPE, null, out);
    }

    /**
     * {@code [{"interfaces": ["a.B", "c.D"]}]} — each proxy declaration is one entry carrying its
     * whole ordered interface list (JK-1799). The serialization-config wrapper writes each proxy
     * as a bare interface-name array instead; both shapes are read.
     */
    private static void proxies(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (!(root instanceof List<?> items)) return;
        for (Object item : items) {
            addProxy(item instanceof List<?> bare ? bare : Json.list(item, "interfaces"), origin, out);
        }
    }

    /** One PROXY_INTERFACE entry named by the ordered, comma-joined interface list. */
    private static void addProxy(List<?> interfaces, String origin, List<DynamicSurface.Entry> out) {
        List<String> names = new ArrayList<>();
        for (Object iface : interfaces) {
            if (iface instanceof String s && !s.isBlank()) names.add(s);
        }
        if (!names.isEmpty()) {
            out.add(DynamicSurface.Entry.type(PROXY_INTERFACE, String.join(",", names), origin));
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
            Object source = holder != null ? holder : root;
            includes = Json.list(source, "includes");
            excludes(Json.list(source, "excludes"), origin, out);
        }
        if (!(includes instanceof List<?> items)) return;
        for (Object include : items) {
            String glob = Json.str(include, "glob");
            if (glob != null && !glob.isBlank()) {
                out.add(DynamicSurface.Entry.type(RESOURCE, glob, origin));
                continue;
            }
            // Split-schema "pattern" entries are Java regexes; a regex re-emitted as a glob
            // matches nothing. Translate the faithful cases, keep the rest as regex (JK-1777).
            String pattern = Json.str(include, "pattern");
            if (pattern == null || pattern.isBlank()) continue;
            String translated = regexToGlob(pattern);
            out.add(
                    translated != null
                            ? DynamicSurface.Entry.type(RESOURCE, translated, origin)
                            : DynamicSurface.Entry.type(DynamicSurface.Kind.RESOURCE_PATTERN, pattern, origin));
        }
    }

    /**
     * A library's declared resource exclusions (JK-1800). Kept in regex form: the split schema
     * writes excludes as {@code "pattern"} regexes, and the sidecar they are re-emitted into
     * takes regexes; a glob exclude converts exactly ({@code **} spans levels, {@code *} stays
     * within one, everything else is quoted).
     */
    private static void excludes(Object root, String origin, List<DynamicSurface.Entry> out) {
        if (!(root instanceof List<?> items)) return;
        for (Object exclude : items) {
            String pattern = Json.str(exclude, "pattern");
            if (pattern == null) {
                String glob = Json.str(exclude, "glob");
                if (glob != null && !glob.isBlank()) pattern = globToRegex(glob);
            }
            if (pattern != null && !pattern.isBlank()) {
                out.add(DynamicSurface.Entry.type(DynamicSurface.Kind.RESOURCE_EXCLUDE_PATTERN, pattern, origin));
            }
        }
    }

    /** The exact Java regex for a GraalVM glob — every glob has one, unlike the reverse. */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '\\' && i + 1 < glob.length()) {
                literal.append(glob.charAt(++i)); // glob escape: the next character is literal
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) regex.append(Pattern.quote(literal.toString()));
        return regex.toString();
    }

    /**
     * Translate a resource regex to a GraalVM glob when the translation is exact, else null.
     *
     * <p>Handled: {@code \Q…\E} quoted literals, single-character escapes of non-alphanumerics
     * ({@code \.}, {@code \-}, …), plain literal characters, and {@code .*} where it maps to a
     * whole-level {@code **} (GraalVM rejects {@code **} glued to other characters in a level).
     * Anything else — character classes, alternation, a bare {@code .}, a within-level
     * {@code .*} — is not translatable without changing what it matches.
     */
    static String regexToGlob(String regex) {
        StringBuilder glob = new StringBuilder();
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) return null;
                char esc = regex.charAt(i + 1);
                if (esc == 'Q') {
                    int end = regex.indexOf("\\E", i + 2);
                    String literal = end < 0 ? regex.substring(i + 2) : regex.substring(i + 2, end);
                    if (containsGlobSpecial(literal)) return null;
                    glob.append(literal);
                    i = end < 0 ? n : end + 2;
                    continue;
                }
                if (esc == 'E') { // stray \E is a no-op
                    i += 2;
                    continue;
                }
                // An escaped non-alphanumeric is that literal; \d, \w, … are classes.
                if (Character.isLetterOrDigit(esc) || containsGlobSpecial(String.valueOf(esc))) return null;
                glob.append(esc);
                i += 2;
                continue;
            }
            if (c == '.' && i + 1 < n && regex.charAt(i + 1) == '*') {
                // `.*` crosses `/`, so only `**` is faithful — and GraalVM requires `**` to be
                // a whole level: nothing or `/` before it, and end or `/` after it.
                boolean levelStart = glob.isEmpty() || glob.charAt(glob.length() - 1) == '/';
                boolean levelEnd = i + 2 >= n || regex.charAt(i + 2) == '/' || regex.startsWith("\\Q/", i + 2);
                if (!levelStart || !levelEnd) return null;
                glob.append("**");
                i += 2;
                continue;
            }
            if (".^$|()[]{}*+?".indexOf(c) >= 0 || containsGlobSpecial(String.valueOf(c))) return null;
            glob.append(c);
            i++;
        }
        return glob.isEmpty() ? null : glob.toString();
    }

    /** Characters that mean something to GraalVM's glob syntax — a literal one is untranslatable. */
    private static boolean containsGlobSpecial(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            if ("*?[]{}\\".indexOf(literal.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static void addName(Object member, UnaryOperator<String> tag, Set<String> sink) {
        String name = Json.str(member, "name");
        if (name != null && !name.isBlank()) sink.add(tag.apply(name));
    }

    private static boolean isTrue(Object holder, String key) {
        return holder instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(key));
    }
}
