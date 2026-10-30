// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Markdown and AsciiDoc: the page, its headings nested by level, links
 * to other files, and code spans naming a symbol — which the linker ties to the code they name.
 * Fenced and delimited code blocks are skipped.
 */
final class DocExtractors {

    private DocExtractors() {}

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern MD_LINK = Pattern.compile("(?<!!)\\[[^\\]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern ADOC_HEADING = Pattern.compile("^(={1,6})\\s+(.+?)\\s*$");
    private static final Pattern ADOC_LINK = Pattern.compile("(?:xref:|link:|include::)([^\\[\\s]+)\\[");
    private static final Pattern CODE_SPAN = Pattern.compile("`+([^`\\n]+?)`+|\\+([^+\\n]+?)\\+");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)*(?:\\(\\))?");

    static Fragment markdown(String relPath, String text) {
        return read(relPath, text, MD_HEADING, MD_LINK, "```", false);
    }

    static Fragment asciidoc(String relPath, String text) {
        return read(relPath, text, ADOC_HEADING, ADOC_LINK, "----", true);
    }

    private static Fragment read(
            String relPath, String text, Pattern heading, Pattern link, String fence, boolean adoc) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "page");
        @Nullable String[] parents = new String[7];
        parents[0] = b.fileId();
        String current = b.fileId();
        boolean inBlock = false;
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int n = i + 1;
            if (line.strip().startsWith(fence) || (!adoc && line.strip().startsWith("~~~"))) {
                inBlock = !inBlock;
                continue;
            }
            if (inBlock) continue;
            Matcher h = heading.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                String title = h.group(2);
                String id = b.node(
                        GraphIds.make(GraphIds.base(relPath), title),
                        title,
                        "document",
                        n,
                        Map.of("node_kind", "heading"));
                String parent = b.fileId();
                for (int l = level - 1; l >= 1; l--) {
                    if (parents[l] != null) {
                        parent = parents[l];
                        break;
                    }
                }
                b.edge(parent, id, Relations.CONTAINS, Confidence.EXTRACTED, n);
                parents[level] = id;
                for (int l = level + 1; l < parents.length; l++) parents[l] = null;
                current = id;
                continue;
            }
            Matcher m = link.matcher(line);
            while (m.find()) {
                String target = m.group(1);
                if (!target.contains("://") && !target.startsWith("mailto:") && !target.startsWith("#")) {
                    b.mention(current, Mention.Kind.FILE, target, n);
                }
            }
            Matcher span = CODE_SPAN.matcher(line);
            while (span.find()) {
                String body = span.group(1) != null ? span.group(1) : span.group(2);
                if (adoc || span.group(1) != null) symbol(b, current, body.strip(), n);
            }
        }
        return b.build();
    }

    /** A code span that looks like a class, method or qualified name becomes a symbol mention. */
    private static void symbol(FragmentBuilder b, String from, String body, int line) {
        if (body.length() < 3 || !SYMBOL.matcher(body).matches()) return;
        String name = body.endsWith("()") ? body.substring(0, body.length() - 2) : body;
        if (Lines.FQCN.matcher(name).matches()) {
            b.mention(from, Mention.Kind.CLASS, name, line);
            return;
        }
        String last = name.substring(name.lastIndexOf('.') + 1);
        boolean typeLike = Character.isUpperCase(last.charAt(0)) && last.chars().anyMatch(Character::isLowerCase);
        if (typeLike || body.endsWith("()")) b.mention(from, Mention.Kind.SYMBOL, last, line);
    }
}
