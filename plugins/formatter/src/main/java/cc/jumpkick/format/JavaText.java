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

    static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;?\\s*$");

    static final Pattern IMPORT =
            Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.]+)(?:\\s+as\\s+\\w+)?\\s*;?\\s*$");

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
