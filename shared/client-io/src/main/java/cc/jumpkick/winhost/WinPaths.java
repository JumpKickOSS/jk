// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Windows path comparison on strings, so the checks read the same on any host: case-insensitive,
 * either separator, no trailing separator.
 */
public final class WinPaths {

    private WinPaths() {}

    /** {@code path} folded for comparison: lower case, forward slashes, no trailing slash. */
    public static String key(String path) {
        String s = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (s.length() > 1 && s.endsWith("/") && !s.endsWith(":/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String key(Path path) {
        return key(path.toString());
    }

    /** True when {@code child} is {@code parent} or lies under it. */
    public static boolean under(String child, @Nullable String parent) {
        if (parent == null || parent.isBlank()) return false;
        String c = key(child);
        String p = key(parent);
        if (c.equals(p)) return true;
        return c.startsWith(p.endsWith("/") ? p : p + "/");
    }

    public static boolean under(Path child, @Nullable String parent) {
        return under(child.toString(), parent);
    }

    /** {@code parent} joined with {@code name} using a backslash, the way Windows writes it. */
    public static String join(String parent, String name) {
        return parent.endsWith("\\") || parent.endsWith("/") ? parent + name : parent + "\\" + name;
    }
}
