// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.util.Locale;

/**
 * The names jk gives keychain items: {@code repo/<id>} for {@code jk repo login}, {@code
 * forge/<host>} for {@code jk auth login}, every character from a set no backend's command line has
 * to quote.
 */
public final class SecretItems {

    /** The service every jk item is filed under, in the keychains that have one. */
    public static final String SERVICE = "jk";

    private SecretItems() {}

    /** {@code <kind>/<name>}, with {@code name} folded to lower case and to the safe set. */
    public static String of(String kind, String name) {
        return requireValid(kind + "/" + safe(name));
    }

    /**
     * {@code item} when every character is a lower-case letter, a digit, or one of {@code . - _ /},
     * which no backend's command line or script has to quote.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static String requireValid(String item) {
        if (item.isEmpty() || item.length() > 200) throw new IllegalArgumentException("bad keychain item: " + item);
        for (char c : item.toCharArray()) {
            boolean ok =
                    (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '/';
            if (!ok) throw new IllegalArgumentException("bad keychain item: " + item);
        }
        return item;
    }

    private static String safe(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
