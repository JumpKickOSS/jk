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
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : edited.entrySet()) {
            String[] lines = e.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].strip();
                boolean comment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
                if (comment && word.matcher(t).find()) out.add(e.getKey() + ":" + (i + 1) + "| " + t);
            }
        }
        if (types.isEmpty()) return out;
        Pattern type = Pattern.compile(
                "\\b(?:" + String.join("|", types.stream().map(Pattern::quote).toList()) + ")\\b");
        for (String rel : textFiles) {
            String text;
            try {
                text = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!text.contains(old)) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (word.matcher(lines[i]).find() && type.matcher(lines[i]).find()) {
                    out.add(rel + ":" + (i + 1) + "| " + lines[i].strip());
                }
            }
        }
        return out;
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
