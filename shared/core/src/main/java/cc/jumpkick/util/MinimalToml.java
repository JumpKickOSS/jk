// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

/**
 * TOML basic-string quoting ({@code \"} {@code \\} {@code \n}/{@code \r}/{@code \t},
 * and {@code \\uXXXX} for other controls).
 */
public final class MinimalToml {

    private MinimalToml() {}

    /** {@code value} as a TOML basic string, including the surrounding double quotes. */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Inverse of {@link #quote} for the minimal scanners: strip the surrounding quotes of a scalar
     * value and decode the escapes {@code quote} produces (backslash, quote, newline, CR, tab, and backslash-u escapes, plus
     * {@code \b \f} for TOML completeness). Literal ({@code '…'}) strings decode nothing. An
     * unquoted value keeps its old semantics: trailing same-line {@code #} comment dropped,
     * whitespace stripped. Unknown escapes pass through verbatim — a lenient scanner must not eat
     * bytes it doesn't understand.
     */
    public static String unquote(String v) {
        if (v.length() >= 2 && v.charAt(0) == '\'') {
            int end = v.indexOf('\'', 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        if (v.length() >= 2 && v.charAt(0) == '"') {
            StringBuilder sb = new StringBuilder(v.length());
            for (int i = 1; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '"') break;
                if (c == '\\' && i + 1 < v.length()) {
                    char e = v.charAt(++i);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 < v.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (NumberFormatException malformed) {
                                    sb.append('\\').append(e);
                                }
                            } else {
                                sb.append('\\').append(e);
                            }
                        }
                        default -> sb.append('\\').append(e);
                    }
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }
        int hash = v.indexOf('#');
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }
}
