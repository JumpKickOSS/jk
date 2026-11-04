// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text the compiled classes do not record that still names a renamed method: comment lines of the
 * files the rename edited, and lines of docs and config that name one of its types beside it.
 * Listed, never edited: whether a comment means this method is the agent's call, and saying there is
 * none saves it the search.
 */
final class RenameMentions {

    /** Docs and config whose lines a rename lists when they name the method beside its type. */
    private static final Pattern PROSE = Pattern.compile("\\.(?:md|adoc|txt|html|xml|properties|ya?ml|toml)$");

    /** Changelogs and release notes: they record what a name was, so a rename never sends an agent there. */
    private static final Pattern HISTORY =
            Pattern.compile("(?i)(?:^|/)(?:changes|changelog|history|news|release[-_]?notes)[^/]*$");

    /** A quoted line's characters at most. */
    private static final int WIDTH = 160;

    /** Mentions listed before the rest are counted. */
    private static final int SHOWN = 12;

    private RenameMentions() {}

    static boolean prose(String rel) {
        return !rel.isEmpty() && PROSE.matcher(rel).find();
    }

    /** Each mention as {@code rel:line| text}, edited files first. */
    static List<String> find(
            Path root, String old, Set<String> types, Map<String, String> edited, Set<String> textFiles) {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(old) + "\\b");
        // Named as code ({@code m}, m(), .m, #m, `m`), not the English word a method name often is.
        String q = Pattern.quote(old);
        Pattern code =
                Pattern.compile("[.#]" + q + "\\b|\\b" + q + "\\s*\\(|\\{@(?:code|link)\\s+" + q + "\\b|`" + q + "`");
        // Outside the type's own files a bare indexOf is some other type's: only Type.m or #m there.
        Pattern qualified = Pattern.compile("(?:\\b(?:"
                + String.join("|", types.stream().map(Pattern::quote).toList()) + ")[.#]|#)" + Pattern.quote(old)
                + "\\b");
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : edited.entrySet()) {
            Pattern wanted = home(e.getKey(), types) ? code : qualified;
            String[] lines = e.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].strip();
                boolean comment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
                if (comment && wanted.matcher(t).find()) out.add(e.getKey() + ":" + (i + 1) + "| " + clip(t, word));
            }
        }
        if (types.isEmpty()) return out;
        Pattern type = Pattern.compile(
                "\\b(?:" + String.join("|", types.stream().map(Pattern::quote).toList()) + ")\\b");
        for (String rel : textFiles) {
            if (HISTORY.matcher(rel).find()) continue;
            String text;
            try {
                text = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!text.contains(old)) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (code.matcher(lines[i]).find() && type.matcher(lines[i]).find()) {
                    out.add(rel + ":" + (i + 1) + "| " + clip(lines[i].strip(), word));
                }
            }
        }
        return out;
    }

    /** A source named after one of the method's types, or its test: where a bare name means the method. */
    static boolean home(String rel, Set<String> types) {
        String file = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String stem = dot < 0 ? file : file.substring(0, dot);
        for (String suffix : List.of("Tests", "Test")) {
            if (stem.endsWith(suffix) && stem.length() > suffix.length()) {
                stem = stem.substring(0, stem.length() - suffix.length());
                break;
            }
        }
        return types.contains(stem);
    }

    /** {@code line} cut to {@link #WIDTH} characters around the first match: one long line must not fill the budget. */
    static String clip(String line, Pattern word) {
        if (line.length() <= WIDTH) return line;
        Matcher m = word.matcher(line);
        int at = m.find() ? m.start() : 0;
        int from = Math.max(0, Math.min(at - WIDTH / 3, line.length() - WIDTH));
        int to = Math.min(line.length(), from + WIDTH);
        return (from > 0 ? "…" : "") + line.substring(from, to) + (to < line.length() ? "…" : "");
    }

    /** The answer's lines for {@code mentions}: the list, or that there is none. */
    static List<String> lines(List<String> mentions, String old, String target) {
        if (mentions.isEmpty()) {
            return List.of(
                    "No comment in the edited files, and no doc or config line naming its type, mentions " + old + ".");
        }
        List<String> out = new ArrayList<>();
        out.add("Not renamed, prose that names " + old + " (comments, docs): edit the lines that mean " + target + ".");
        mentions.stream().limit(SHOWN).forEach(m -> out.add("  " + m));
        if (mentions.size() > SHOWN) out.add("  +" + (mentions.size() - SHOWN) + " more");
        return out;
    }
}
