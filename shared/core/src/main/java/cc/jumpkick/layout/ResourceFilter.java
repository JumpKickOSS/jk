// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maven's default resource filtering: {@code ${name}} and {@code @name@} become the value of
 * {@code name}. {@code \${name}} stays {@code ${name}}, the backslash dropped. A name with no value
 * is left as written; an unresolved {@code ${name}} is reported, an unresolved {@code @name@} is
 * not, since {@code @} pairs are common in plain text. Images and files that are not UTF-8 are
 * copied as they are.
 */
public final class ResourceFilter {

    /** Maven's default {@code nonFilteredFileExtensions}. */
    private static final Set<String> BINARY = Set.of("jpg", "jpeg", "gif", "bmp", "png");

    private static final Pattern TOKEN = Pattern.compile("(\\\\)?\\$\\{([^}\\s]+)}|@([A-Za-z0-9_.-]+)@");

    private ResourceFilter() {}

    /** The expanded text and the {@code ${name}} references that had no value. */
    public record Result(String text, Set<String> unresolved) {}

    /** {@code text} with every resolvable reference replaced. */
    public static Result expand(String text, Map<String, String> values) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        Set<String> unresolved = new TreeSet<>();
        while (m.find()) {
            String replacement;
            if (m.group(2) != null) {
                String name = m.group(2);
                if (m.group(1) != null) {
                    replacement = "${" + name + "}";
                } else if (values.containsKey(name)) {
                    replacement = values.get(name);
                } else {
                    unresolved.add(name);
                    replacement = m.group();
                }
            } else {
                String name = m.group(3);
                replacement = values.containsKey(name) ? values.get(name) : m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Result(out.toString(), unresolved);
    }

    /** Every name {@code text} references, {@code ${name}} and {@code @name@} alike, escaped ones excluded. */
    public static Set<String> references(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (m.group(2) != null && m.group(1) == null) names.add(m.group(2));
            if (m.group(3) != null) names.add(m.group(3));
        }
        return names;
    }

    /** Whether {@code fileName} is filtered at all, or copied as it is. */
    public static boolean filters(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || !BINARY.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The bytes {@code fileName} is copied as: expanded when it is UTF-8 text, as they are
     * otherwise. {@code unresolved} collects the {@code ${name}} references left as written.
     */
    public static byte[] filter(String fileName, byte[] bytes, Map<String, String> values, Set<String> unresolved) {
        if (!filters(fileName)) return bytes;
        String text = utf8(bytes);
        if (text == null) return bytes;
        Result r = expand(text, values);
        unresolved.addAll(r.unresolved());
        return r.text().equals(text) ? bytes : r.text().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What a module's filtered resources expand with: the values jk knows ({@code project.groupId},
     * {@code project.artifactId}, {@code project.version}) under {@code [resources.properties]},
     * which wins.
     */
    public static Map<String, String> values(JkBuild build) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("project.groupId", build.project().group());
        values.put("project.artifactId", build.project().name());
        values.put("project.version", build.project().version());
        values.putAll(build.build().resources().properties());
        return values;
    }

    /** {@code bytes} as UTF-8 text, or {@code null} when they are not. */
    public static @Nullable String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
