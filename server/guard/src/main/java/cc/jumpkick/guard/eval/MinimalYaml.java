// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one YAML question the {@code yaml-keys} extractor asks: the keys of the block mapping at a dot
 * path. Indentation-scoped {@code key: value} lines only — no flow style, anchors or multi-document
 * streams, which a workflow or a config file does not use for its top-level shape. Comments and
 * blank lines are skipped; a scalar value is kept as text, a nested mapping or sequence as {@code ""}.
 */
final class MinimalYaml {

    private MinimalYaml() {}

    /** Keys → scalar text of the mapping at {@code path} ({@code ""} = the document), or {@code null} when absent. */
    static @Nullable Map<String, String> keysAt(String text, String path) {
        List<String> lines = text.lines().toList();
        int start = 0;
        int indent = -1;
        if (!path.isEmpty()) {
            for (String seg : path.split("\\.")) {
                int found = -1;
                for (int i = start; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (skip(l)) continue;
                    int ind = indentOf(l);
                    if (indent >= 0 && ind <= indent) break;
                    if (indent >= 0 && ind != firstChildIndent(lines, start, indent)) continue;
                    if (indent < 0 && ind != 0) continue;
                    String key = keyOf(l);
                    if (seg.equals(key)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) return null;
                start = found + 1;
                indent = indentOf(lines.get(found));
            }
        }
        int childIndent = firstChildIndent(lines, start, indent);
        if (childIndent < 0) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i);
            if (skip(l)) continue;
            int ind = indentOf(l);
            if (ind <= indent) break;
            if (ind != childIndent) continue;
            String key = keyOf(l);
            if (key == null) continue;
            String s = l.strip();
            if (s.startsWith("- ")) s = s.substring(2).strip();
            String rest = s.substring(s.indexOf(':') + 1).strip();
            out.put(key, rest.startsWith("#") ? "" : rest);
        }
        return out;
    }

    private static int firstChildIndent(List<String> lines, int from, int parentIndent) {
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (skip(l)) continue;
            int ind = indentOf(l);
            if (ind <= parentIndent) return -1;
            return ind;
        }
        return -1;
    }

    private static boolean skip(String l) {
        String s = l.strip();
        return s.isEmpty() || s.startsWith("#") || s.equals("---");
    }

    private static int indentOf(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') i++;
        return i;
    }

    /** The key of a {@code key: …} line ({@code - key: …} for a sequence item), else {@code null}. */
    static @Nullable String keyOf(String l) {
        String s = l.strip();
        if (s.startsWith("- ")) s = s.substring(2).strip();
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        if (colon + 1 < s.length() && s.charAt(colon + 1) != ' ') return null;
        String key = s.substring(0, colon).strip();
        if (key.startsWith("\"") && key.endsWith("\"") && key.length() > 1) key = key.substring(1, key.length() - 1);
        return key.isEmpty() ? null : key;
    }
}
