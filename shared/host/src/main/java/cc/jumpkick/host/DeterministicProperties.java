// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Map;
import java.util.TreeMap;

/**
 * The one {@code .properties} writer for build outputs: keys sorted, {@code \n} line endings, no
 * comment header, spec-correct escaping — two renders of one map are byte-identical, and every
 * entry round-trips through {@link java.util.Properties#load}.
 *
 * <p>{@code Properties.store()} is never the writer for a build output: it prepends a
 * {@code #}-dated comment line and emits keys in unspecified {@code Hashtable} order, so two
 * identical builds would produce two different artifacts. jk fixes timestamps everywhere else it
 * writes an output; this class is that contract applied to a text file.
 */
public final class DeterministicProperties {

    private DeterministicProperties() {}

    /** All entries as {@code key=value} lines in key order, each terminated with {@code \n}. */
    public static String render(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(entries).entrySet()) {
            sb.append(escape(e.getKey(), true))
                    .append('=')
                    .append(escape(e.getValue(), false))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code java.util.Properties} escaping, so what we write round-trips through {@link
     * java.util.Properties#load}. Keys additionally escape the separators that would otherwise end
     * the key early.
     */
    private static String escape(String value, boolean isKey) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                // A leading space is significant in a value and always in a key.
                case ' ' -> sb.append(isKey || i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
