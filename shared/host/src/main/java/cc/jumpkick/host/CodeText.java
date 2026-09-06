// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Source text as a lexer sees it, for tools that match patterns against code without parsing it:
 * the formatter's FQCN shortener, the file-size and FQCN ratchets, and every text-substrate guard.
 *
 * <p>One owner. Before this class the same character-at-a-time lexer existed three times (the
 * self-hosted gate script, buildSrc, the formatter) and each copy had learned a different edge
 * case. Every projection here is <b>length-preserving</b>: a blanked copy has the same length and
 * the same newline positions as the original, so an offset found in the projection is the offset
 * in the source.
 *
 * <p>Lexeme set: {@code //} and {@code /* *}{@code /} comments, {@code "…"} strings with escapes,
 * {@code '…'} char literals, {@code """…"""} text blocks (escapes honoured — a text block may
 * escape a quote to keep a {@code """} from closing it), and, for JavaScript, backtick template
 * literals — because a {@code /*} inside a template must not eat the rest of the file. That covers
 * Java, Kotlin, Groovy-as-Java-subset, JS and the build scripts; it is not a parser for any of them.
 */
public final class CodeText {

    private CodeText() {}

    /** Which lexeme classes a projection blanks to spaces. */
    public enum Blank {
        /** Blank comments; keep code and literals. The default view for a ban on code. */
        COMMENTS,
        /** Blank comments and literal bodies; keep code only. The view a count of code takes. */
        COMMENTS_AND_STRINGS,
        /** Blank nothing. The view for a rule about anything a reader can see. */
        NONE,
        /** Blank code and literals; keep comments. The view for a rule about comments. */
        CODE
    }

    /**
     * Two or more lowercase package segments followed by an UpperCamel type — the shape a
     * fully-qualified class name takes in source. The shortener rewrites it; the FQCN ratchet counts
     * it.
     */
    public static final Pattern FQCN = Pattern.compile("(?<![\\w.$])(?:[a-z][a-z0-9_]*\\.){2,}[A-Z][A-Za-z0-9_]*");

    private static final byte CODE = 0;
    private static final byte COMMENT = 1;
    private static final byte LITERAL = 2;

    /** {@link #blank(String, Blank, boolean)} without JS template literals. */
    public static String blank(String src, Blank mode) {
        return blank(src, mode, false);
    }

    /**
     * {@code src} with the lexeme classes {@code mode} names replaced by spaces. Newlines are always
     * kept, so line numbers and offsets in the result are the source's.
     *
     * @param templateLiterals treat backtick strings as literals (JavaScript)
     */
    public static String blank(String src, Blank mode, boolean templateLiterals) {
        if (mode == Blank.NONE) return src;
        byte[] kinds = classify(src, templateLiterals);
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\n') {
                out.append(c);
                continue;
            }
            boolean keep =
                    switch (mode) {
                        case COMMENTS -> kinds[i] != COMMENT;
                        case COMMENTS_AND_STRINGS -> kinds[i] == CODE;
                        case CODE -> kinds[i] == COMMENT;
                        case NONE -> true;
                    };
            out.append(keep ? c : ' ');
        }
        return out.toString();
    }

    /**
     * One lexeme class per character. Character at a time, never a substring: the guard lane lexes
     * the whole tree per run, and a two- and three-character substring per position was most of the
     * wall clock.
     */
    private static byte[] classify(String src, boolean templateLiterals) {
        int n = src.length();
        byte[] k = new byte[n];
        int i = 0;
        boolean line = false, block = false, text = false, str = false, chr = false, tick = false;
        while (i < n) {
            char c = src.charAt(i);
            char c1 = i + 1 < n ? src.charAt(i + 1) : ' ';
            char c2 = i + 2 < n ? src.charAt(i + 2) : ' ';
            if (line) {
                if (c == '\n') line = false;
                else k[i] = COMMENT;
                i++;
            } else if (block) {
                if (c == '*' && c1 == '/') {
                    block = false;
                    k[i] = COMMENT;
                    k[i + 1] = COMMENT;
                    i += 2;
                } else {
                    if (c != '\n') k[i] = COMMENT;
                    i++;
                }
            } else if (text) {
                if (c == '\\') {
                    i = literalEscape(k, i, n);
                } else if (c == '"' && c1 == '"' && c2 == '"') {
                    text = false;
                    k[i] = LITERAL;
                    k[i + 1] = LITERAL;
                    k[i + 2] = LITERAL;
                    i += 3;
                } else {
                    if (c != '\n') k[i] = LITERAL;
                    i++;
                }
            } else if (str || chr || tick) {
                char close = str ? '"' : chr ? '\'' : '`';
                if (c == '\\') {
                    i = literalEscape(k, i, n);
                } else {
                    if (c == close) {
                        str = false;
                        chr = false;
                        tick = false;
                    }
                    if (c != '\n') k[i] = LITERAL;
                    i++;
                }
            } else if (c == '/' && c1 == '/') {
                line = true;
                k[i] = COMMENT;
                k[i + 1] = COMMENT;
                i += 2;
            } else if (c == '/' && c1 == '*') {
                block = true;
                k[i] = COMMENT;
                k[i + 1] = COMMENT;
                i += 2;
            } else if (c == '"' && c1 == '"' && c2 == '"') {
                text = true;
                k[i] = LITERAL;
                k[i + 1] = LITERAL;
                k[i + 2] = LITERAL;
                i += 3;
            } else if (c == '"') {
                str = true;
                k[i] = LITERAL;
                i++;
            } else if (c == '\'') {
                chr = true;
                k[i] = LITERAL;
                i++;
            } else if (templateLiterals && c == '`') {
                tick = true;
                k[i] = LITERAL;
                i++;
            } else {
                i++;
            }
        }
        return k;
    }

    private static int literalEscape(byte[] k, int i, int n) {
        k[i] = LITERAL;
        if (i + 1 < n && k.length > i + 1) k[i + 1] = LITERAL;
        return i + 2;
    }

    /**
     * Drop whitespace between tokens, keeping every string / char / text-block literal verbatim, so
     * a call wrapped across two lines cannot evade a pattern written on one.
     */
    public static String squashBetweenLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                String close = c == '"' && src.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c);
                out.append(close);
                i += close.length();
                while (i < n) {
                    if (src.charAt(i) == '\\') {
                        out.append(src, i, Math.min(i + 2, n));
                        i += 2;
                    } else if (src.startsWith(close, i)) {
                        out.append(close);
                        i += close.length();
                        break;
                    } else {
                        out.append(src.charAt(i));
                        i++;
                    }
                }
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Drop {@code import} / {@code package} lines from already-comment-blanked source, then squash. */
    public static String squashImportsOut(String commentsBlanked) {
        StringBuilder kept = new StringBuilder(commentsBlanked.length());
        for (String l : commentsBlanked.split("\n", -1)) {
            String s = l.stripLeading();
            if (s.startsWith("import ") || s.startsWith("package ")) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(l);
        }
        return squashBetweenLiterals(kept.toString());
    }

    /** The text a code-ban pattern is matched against: comments and imports gone, whitespace squashed. */
    public static String guardText(String src) {
        return squashImportsOut(blank(src, Blank.COMMENTS));
    }

    /** One string literal: its span in the source (quotes included) and its body as written. */
    public record Literal(int start, int end, String body) {}

    /**
     * Every string literal in a Java-shaped source, comments and char literals skipped, with its
     * position. Escapes are kept as written. A hand lexer, not a regex: a regex version overflowed
     * the stack on a long literal.
     */
    public static List<Literal> literals(String src) {
        List<Literal> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            if (src.startsWith("//", i)) {
                int nl = src.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (src.startsWith("/*", i)) {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) j += src.charAt(j) == '\\' ? 2 : 1;
                int end = Math.min(j, n);
                out.add(new Literal(i, Math.min(end + 3, n), src.substring(i + 3, end)));
                i = end + 3;
            } else {
                char c = src.charAt(i);
                if (c == '"' || c == '\'') {
                    StringBuilder body = new StringBuilder();
                    int j = i + 1;
                    while (j < n && src.charAt(j) != c) {
                        if (src.charAt(j) == '\\') {
                            body.append(src.charAt(j));
                            j++;
                        }
                        if (j < n) {
                            body.append(src.charAt(j));
                            j++;
                        }
                    }
                    if (c == '"') out.add(new Literal(i, Math.min(j + 1, n), body.toString()));
                    i = j + 1;
                } else {
                    i++;
                }
            }
        }
        return out;
    }

    /** The bodies of {@link #literals(String)}. */
    public static List<String> stringLiterals(String src) {
        List<String> out = new ArrayList<>();
        for (Literal l : literals(src)) out.add(l.body());
        return out;
    }

    /**
     * Code lines: comments, blank lines and {@code package} / {@code import} lines do not count. A
     * statement with a trailing comment still counts. String and text-block contents count — a
     * fixture is data. {@code extension} selects JS template-literal lexing for {@code js}/{@code mjs}.
     */
    public static int codeLines(String src, String extension) {
        if (src.isEmpty()) return 0;
        String ext = extension.toLowerCase(Locale.ROOT);
        boolean js = ext.equals("js") || ext.equals("mjs");
        String[] visible = blank(src, Blank.COMMENTS, js).split("\n", -1);
        String[] code = blank(src, Blank.COMMENTS_AND_STRINGS, js).split("\n", -1);
        int lines = 0;
        for (int i = 0; i < Math.max(visible.length, code.length); i++) {
            String seen = i < visible.length ? visible[i].strip() : "";
            if (seen.isEmpty()) continue;
            String body = i < code.length ? code[i].strip() : "";
            if (body.isEmpty() || !(body.startsWith("package ") || body.startsWith("import "))) lines++;
        }
        return lines;
    }

    /** 1-based line number of {@code offset} in {@code text}. */
    public static int lineAt(CharSequence text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
