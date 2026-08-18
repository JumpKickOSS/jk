// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

/** Error-message hygiene shared by engine verbs and client sync paths. */
public final class Errors {

    private Errors() {}

    /**
     * Human text for {@code t}: its message, or {@code t.toString()} when the message is null or
     * blank — {@code String.valueOf(e.getMessage())} turned a message-less NPE into the literal
     * {@code jk: null} with no class name (JK-2170).
     */
    public static String text(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.toString() : m;
    }
}
