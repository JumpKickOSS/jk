// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.Locale;

/**
 * The grammar of a durable project id: 1–128 of {@code [a-z0-9_-]} after lower-casing. The id names
 * files under jk's state directory, so anything path-like is refused.
 */
public final class ProjectIds {

    private ProjectIds() {}

    /** The id lower-cased and stripped; throws {@link IllegalArgumentException} for one that breaks the grammar. */
    public static String normalize(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 128) {
            throw new IllegalArgumentException("invalid project id length");
        }
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.contains("..")) {
            throw new IllegalArgumentException("project id must not look like a path: " + raw);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) throw new IllegalArgumentException("invalid project id char: " + raw);
        }
        return s;
    }

    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
