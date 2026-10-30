// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The node-id scheme. An id is the parts joined with {@code _}, case-folded and
 * NFKC-normalized to a fixpoint, with every run of non-word characters collapsed to one {@code _}
 * and the ends trimmed. A source or Markdown file's ids start from its repo-relative path minus the
 * extension: {@code src/main/java/a/Foo.java} is {@code src_main_java_a_foo} and its class {@code
 * Foo} is {@code src_main_java_a_foo_foo}; so does JSON.
 * Every other file keeps its extension,
 * so an Angular component's {@code app.ts}, {@code app.html} and {@code app.css} stay three nodes.
 */
public final class GraphIds {

    private GraphIds() {}

    /** The canonical form of {@code s}; idempotent. */
    public static String normalize(String s) {
        String cur = s;
        for (int i = 0; i < 6; i++) {
            String next = Normalizer.normalize(GraphText.casefold(cur), Normalizer.Form.NFKC);
            if (next.equals(cur)) break;
            cur = next;
        }
        StringBuilder out = new StringBuilder(cur.length());
        boolean gap = false;
        for (int i = 0; i < cur.length(); ) {
            int cp = cur.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != '_' && GraphText.isWord(cp)) {
                if (gap && !out.isEmpty()) out.append('_');
                gap = false;
                out.appendCodePoint(cp);
            } else {
                gap = true;
            }
        }
        return out.toString();
    }

    /** The id of {@code parts} joined; empty parts are skipped, {@code _}/{@code .} trimmed off each. */
    public static String make(String... parts) {
        StringJoiner joined = new StringJoiner("_");
        for (String part : parts) {
            String trimmed = trim(part);
            if (!trimmed.isEmpty()) joined.add(trimmed);
        }
        return normalize(joined.toString());
    }

    /** {@code relPath} without its last extension; a dotfile keeps its name. */
    public static String stem(String relPath) {
        int slash = relPath.lastIndexOf('/');
        int dot = relPath.lastIndexOf('.');
        return dot > slash + 1 && dot < relPath.length() - 1 ? relPath.substring(0, dot) : relPath;
    }

    /** Extensions whose files take extension-less ids. */
    private static final Set<String> STEMMED = Set.of(
            "java",
            "kt",
            "kts",
            "groovy",
            "gradle",
            "scala",
            "js",
            "jsx",
            "mjs",
            "cjs",
            "ts",
            "tsx",
            "mts",
            "cts",
            "vue",
            "md",
            "markdown",
            "mdx",
            "py");

    /** The path a file's ids start from: {@link #stem} for source and Markdown, the whole path otherwise. */
    public static String base(String relPath) {
        String stem = stem(relPath);
        if (stem.length() == relPath.length()) return relPath;
        String ext = relPath.substring(stem.length() + 1).toLowerCase(Locale.ROOT);
        return STEMMED.contains(ext) ? stem : relPath;
    }

    /** The id of the file at {@code relPath}. */
    public static String file(String relPath) {
        return make(base(relPath));
    }

    /** The id of a top-level declaration {@code name} in the file at {@code relPath}. */
    public static String declared(String relPath, String name) {
        return make(base(relPath), name);
    }

    /** The id of member {@code name} of the node {@code ownerId}. */
    public static String member(String ownerId, String name) {
        return make(ownerId, name);
    }

    private static String trim(String part) {
        int start = 0;
        int end = part.length();
        while (start < end && isTrim(part.charAt(start))) start++;
        while (end > start && isTrim(part.charAt(end - 1))) end--;
        return part.substring(start, end);
    }

    private static boolean isTrim(char c) {
        return c == '_' || c == '.';
    }
}
