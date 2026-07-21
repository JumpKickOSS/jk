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
}
