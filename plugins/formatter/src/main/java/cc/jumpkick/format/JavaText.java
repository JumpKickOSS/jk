// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.util.regex.Pattern;

/**
 * Length-preserving comment/string blanking and the FQCN matcher shared by the shortener and the
 * type index. Positions in the blanked copy match the original source.
 */
final class JavaText {

    private JavaText() {}

    /**
     * Two or more lowercase package segments followed by an UpperCamel type. The same shape
     * {@code checkNoFqcn} counts.
     */
    static final Pattern FQCN = Pattern.compile("(?<![\\w.$])(?:[a-z][a-z0-9_]*\\.){2,}[A-Z][A-Za-z0-9_]*");

    /**
     * The trailing run is {@code [ \\t]*}, deliberately not {@code \\s*}: these patterns are matched
     * against the comment-blanked copy, where a javadoc block is a rectangle of spaces. A trailing
     * {@code \\s*$} then slides the match end past the blank lines AND the blanked comment to the next
     * line-end, so an import inserted at that offset landed after the class javadoc — which
     * palantir-java-format rejects outright as "Imports not contiguous". Ending the match at its own
     * line keeps the insertion point immediately after the last real import.
     */
    static final Pattern PACKAGE = Pattern.compile("(?m)^package[ \\t]+([\\w.]+)[ \\t]*;?[ \\t]*$");

    static final Pattern IMPORT =
            Pattern.compile("(?m)^import[ \\t]+(static[ \\t]+)?([\\w.]+)(?:[ \\t]+as[ \\t]+\\w+)?[ \\t]*;?[ \\t]*$");

    /** An upper-case-initial identifier: the shape a type name takes in all four languages. */
    static final Pattern TYPE_NAME = Pattern.compile("(?<![\\w.$])[A-Z][A-Za-z0-9_]*");

    /**
     * An import whose supplied simple names this pass cannot enumerate: on-demand ({@code x.*},
     * Scala {@code x._}), a Scala/Kotlin brace list, or an {@code as} alias. Any of these can already
     * be binding the name a new single-type import would claim.
     */
    static final Pattern OPAQUE_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?[\\w.]*(?:\\*|_\\s*$|\\{)|^\\s*import\\s+[\\w.]+\\s+as\\s+\\w+");

    static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:class|interface|enum|record|object|trait)\\s+([A-Z][\\w]*)|@interface\\s+([A-Z][\\w]*)");

    /**
     * Blank comments and string/char/text-block literals, preserving length and newlines so a
     * regex over the result still maps onto the original.
     */
    static String blankNonCode(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        boolean line = false, block = false, text = false, str = false, chr = false;
        while (i < src.length()) {
            char c = src.charAt(i);
            String two = i + 2 <= src.length() ? src.substring(i, i + 2) : "";
            String three = i + 3 <= src.length() ? src.substring(i, i + 3) : "";
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
            } else if (block) {
                if ("*/".equals(two)) {
                    block = false;
                    out.append("  ");
                    i += 2;
                    continue;
                }
                out.append(c == '\n' ? '\n' : ' ');
            } else if (text) {
                // A text block may escape a quote to keep a `"""` sequence from closing it. Without
                // consuming the escape, the blanker closed the block early and treated the remaining
                // string body as code — where the FQCN matcher would rewrite the literal's contents.
                // That output still compiles; only the string's value changes, and the unused-import
                // step then removes the evidence. Silent data loss, so it is handled here rather than
                // left to a later guard.
                if (c == '\\') {
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if ("\"\"\"".equals(three)) {
                    text = false;
                    out.append("   ");
                    i += 3;
                    continue;
                }
                out.append(c == '\n' ? '\n' : ' ');
            } else if (str) {
                if (c == '\\') {
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if (c == '"') str = false;
                out.append(' ');
            } else if (chr) {
                if (c == '\\') {
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if (c == '\'') chr = false;
                out.append(' ');
            } else if ("//".equals(two)) {
                line = true;
                out.append("  ");
                i += 2;
                continue;
            } else if ("/*".equals(two)) {
                block = true;
                out.append("  ");
                i += 2;
                continue;
            } else if ("\"\"\"".equals(three)) {
                text = true;
                out.append("   ");
                i += 3;
                continue;
            } else if (c == '"') {
                str = true;
                out.append(' ');
            } else if (c == '\'') {
                chr = true;
                out.append(' ');
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    static String packageName(String source) {
        var m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    static boolean javaLang(String fqcn) {
        return fqcn.startsWith("java.lang.") && fqcn.indexOf('.', "java.lang.".length()) < 0;
    }
}
