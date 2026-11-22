// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML's {@code prose} view: the text a reader reads, with every tag and its attributes, comments,
 * declarations, code elements ({@code pre}, {@code code}, {@code kbd}, {@code samp}), scripts,
 * styles, drawings ({@code svg}), anything marked {@code aria-hidden="true"} and bare URLs blanked
 * to spaces. A page draws its terminal and dashboard mock-ups in markup and hides them from screen
 * readers; they are pictures, not sentences. Newlines and offsets are kept, so a hit reports the
 * line it was written on. An element that never closes runs to the end of the file.
 */
final class HtmlProse {

    /** A start or end tag; group 1 is the slash, 2 the name, 3 the attributes. Quoted values may hold {@code >}. */
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w:-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>");

    private static final Pattern HIDDEN =
            Pattern.compile("\\baria-hidden\\s*=\\s*[\"']?true\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    /** Elements whose content is code, raw text or a picture: blanked with their tags. */
    private static final Set<String> CODE = Set.of("pre", "code", "kbd", "samp", "svg", "script", "style");

    /** Elements read as raw text: a {@code <} inside them opens no tag. */
    private static final Set<String> RAW = Set.of("script", "style");

    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    private HtmlProse() {}

    static String blank(String text) {
        char[] out = text.toCharArray();
        Matcher tag = TAG.matcher(text);
        int i = 0;
        while ((i = text.indexOf('<', i)) >= 0) {
            if (text.startsWith("<!--", i)) {
                int end = commentEnd(text, i);
                blank(out, i, end);
                i = end;
                continue;
            }
            if (text.startsWith("<!", i) || text.startsWith("<?", i)) {
                int gt = text.indexOf('>', i);
                int end = gt < 0 ? text.length() : gt + 1;
                blank(out, i, end);
                i = end;
                continue;
            }
            if (!tag.region(i, text.length()).lookingAt()) {
                i++;
                continue;
            }
            int end = tag.end();
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            String attributes = tag.group(3);
            boolean opens = tag.group(1).isEmpty() && !attributes.endsWith("/") && !VOID.contains(name);
            if (opens && (CODE.contains(name) || HIDDEN.matcher(attributes).find())) end = closing(text, name, end);
            blank(out, i, end);
            i = end;
        }
        Matcher url = URL.matcher(new String(out));
        while (url.find()) blank(out, url.start(), url.end());
        return new String(out);
    }

    /**
     * The end of the element {@code name} whose start tag ends at {@code from}: past its matching end
     * tag, or the end of the text.
     */
    private static int closing(String text, String name, int from) {
        if (RAW.contains(name)) {
            int close = text.toLowerCase(Locale.ROOT).indexOf("</" + name, from);
            if (close < 0) return text.length();
            int gt = text.indexOf('>', close);
            return gt < 0 ? text.length() : gt + 1;
        }
        Matcher tag = TAG.matcher(text);
        int depth = 1;
        int i = from;
        while ((i = text.indexOf('<', i)) >= 0) {
            if (text.startsWith("<!--", i)) {
                i = commentEnd(text, i);
                continue;
            }
            if (!tag.region(i, text.length()).lookingAt()) {
                i++;
                continue;
            }
            i = tag.end();
            if (!tag.group(2).equalsIgnoreCase(name)) continue;
            if (!tag.group(1).isEmpty()) depth--;
            else if (!tag.group(3).endsWith("/")) depth++;
            if (depth == 0) return i;
        }
        return text.length();
    }

    private static int commentEnd(String text, int at) {
        int close = text.indexOf("-->", at + 4);
        return close < 0 ? text.length() : close + 3;
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to; k++) if (out[k] != '\n') out[k] = ' ';
    }
}
