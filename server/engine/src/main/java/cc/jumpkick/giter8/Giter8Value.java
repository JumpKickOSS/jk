// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.util.Locale;

/**
 * One Giter8 property. {@code .truthy} is {@code y} / {@code yes} / {@code true} (any case);
 * StringTemplate reads it via {@link #getTruthy()}.
 */
public record Giter8Value(String text, boolean truthy) {

    public Giter8Value(String text) {
        this(text == null ? "" : text, isTruthy(text));
    }

    public boolean getTruthy() {
        return truthy;
    }

    @Override
    public String toString() {
        return text;
    }

    static boolean isTruthy(String raw) {
        if (raw == null) return false;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        return s.equals("y") || s.equals("yes") || s.equals("true");
    }
}
