// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maven's resource filtering over a module's {@code [resources] delimiters}, by default {@code
 * ${*}} and {@code @}: {@code ${name}} and {@code @name@} become the value of {@code name}. A
 * delimiter is written as Maven writes it, {@code <begin>*<end>}, or one token that both begins and
 * ends the name. A backslash before a reference keeps it as written, the backslash dropped. A name
 * with no value is left as written; an unresolved reference is reported when its delimiter's ends
 * differ ({@code ${name}}), not when they are the same token ({@code @name@}), since such pairs are
 * common in plain text. Images and files that are not UTF-8 are copied as they are.
 */
public final class ResourceFilter {

    /** Maven's default {@code nonFilteredFileExtensions}. */
    private static final Set<String> BINARY = Set.of("jpg", "jpeg", "gif", "bmp", "png");

    /** A name between two equal tokens: the characters a property name is made of. */
    private static final String SYMMETRIC_NAME = "[A-Za-z0-9_.-]+";

    /** One delimiter's two ends. */
    private record Delimiter(String begin, String end) {

        boolean reported() {
            return !begin.equals(end);
        }
    }

    private final List<Delimiter> delimiters;
    private final Pattern token;

    private ResourceFilter(List<Delimiter> delimiters) {
        this.delimiters = List.copyOf(delimiters);
        StringBuilder regex = new StringBuilder();
        for (Delimiter d : delimiters) {
            if (!regex.isEmpty()) regex.append('|');
            String name = d.reported() ? "(?:(?!" + Pattern.quote(d.end()) + ")\\S)+" : SYMMETRIC_NAME;
            regex.append("(\\\\)?")
                    .append(Pattern.quote(d.begin()))
                    .append('(')
                    .append(name)
                    .append(')')
                    .append(Pattern.quote(d.end()));
        }
        this.token = Pattern.compile(regex.isEmpty() ? "(?!)" : regex.toString());
    }

    /** The filter for {@code delimiters}, each checked by {@link #parse}. */
    public static ResourceFilter of(List<String> delimiters) {
        List<Delimiter> parsed = new ArrayList<>(delimiters.size());
        for (String d : delimiters) parsed.add(parse(d));
        return new ResourceFilter(parsed);
    }

    /** The filter a module's {@code [resources] delimiters} name. */
    public static ResourceFilter of(JkBuild build) {
        return of(build.build().resources().delimiters());
    }

    /**
     * Why {@code delimiter} is not one, or null when it is: one {@code *} between a non-empty begin
     * and end, or no {@code *} and no whitespace at all.
     */
    public static @Nullable String problem(String delimiter) {
        if (delimiter.isBlank() || delimiter.chars().anyMatch(Character::isWhitespace)) {
            return "a delimiter is <begin>*<end> or one token, with no spaces";
        }
        int star = delimiter.indexOf('*');
        if (star < 0) return null;
        if (star != delimiter.lastIndexOf('*') || star == 0 || star == delimiter.length() - 1) {
            return "`" + delimiter + "` must put one * between a begin and an end, such as ${*}";
        }
        return null;
    }

    private static Delimiter parse(String delimiter) {
        String problem = problem(delimiter);
        if (problem != null) throw new IllegalArgumentException(problem);
        int star = delimiter.indexOf('*');
        return star < 0
                ? new Delimiter(delimiter, delimiter)
                : new Delimiter(delimiter.substring(0, star), delimiter.substring(star + 1));
    }

    /** The expanded text and the references that had no value, by name. */
    public record Result(String text, Set<String> unresolved) {}

    /** {@code text} with every resolvable reference replaced. */
    public Result expand(String text, Map<String, String> values) {
        Matcher m = token.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        Set<String> unresolved = new TreeSet<>();
        while (m.find()) {
            int i = matched(m);
            Delimiter d = delimiters.get(i);
            String name = m.group(2 * i + 2);
            String replacement;
            if (m.group(2 * i + 1) != null) {
                replacement = d.begin() + name + d.end();
            } else if (values.containsKey(name)) {
                replacement = values.get(name);
            } else {
                if (d.reported()) unresolved.add(name);
                replacement = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Result(out.toString(), unresolved);
    }

    /** Every name {@code text} references, escaped ones excluded. */
    public Set<String> references(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = token.matcher(text);
        while (m.find()) {
            int i = matched(m);
            if (m.group(2 * i + 1) == null) names.add(m.group(2 * i + 2));
        }
        return names;
    }

    /** Which delimiter {@code m} matched, by its index. */
    private int matched(Matcher m) {
        for (int i = 0; i < delimiters.size(); i++) {
            if (m.group(2 * i + 2) != null) return i;
        }
        throw new IllegalStateException("a match names no delimiter");
    }

    /** Whether {@code fileName} is filtered at all, or copied as it is. */
    public static boolean filters(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || !BINARY.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The bytes {@code fileName} is copied as: expanded when it is UTF-8 text, as they are
     * otherwise. {@code unresolved} collects the reported references left as written.
     */
    public byte[] filter(String fileName, byte[] bytes, Map<String, String> values, Set<String> unresolved) {
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
