// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.Locale;

/**
 * One place for the backslash-to-slash rewrites behind journal/metrics/bind keys and display
 * paths. The rewrite applies when the string is actually a Windows path — running on Windows
 * (every {@code \} is a separator) or shaped like one ({@code C:\…} / UNC {@code \\host\…}, so
 * rows written on Windows stay one key family when read anywhere). On POSIX a backslash is a
 * legal filename character: a file literally named {@code a\b} must neither display as nor key
 * equal to a real {@code a/b} path.
 */
public final class DirKeys {

    private DirKeys() {}

    /** {@code os.name} read live so a test can spoof it; this module cannot see {@code HostPlatform}. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean looksWindowsAbsolute(String s) {
        if (s.startsWith("\\\\")) return true; // UNC
        return s.length() >= 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':'
                && (s.charAt(2) == '/' || s.charAt(2) == '\\');
    }

    /** Forward-slash form for display and key building; POSIX backslash names pass through verbatim. */
    public static String slashes(String s) {
        if (s == null) return null;
        return isWindows() || looksWindowsAbsolute(s) ? s.replace('\\', '/') : s;
    }

    /**
     * Canonical machine key for a directory: {@link #slashes}, plus an uppercased leading drive
     * letter — NTFS is case-insensitive and launchers disagree about drive case ({@code cd c:\ws}),
     * so {@code C:/ws} and {@code c:/ws} must be one journal/metrics/bind key, not two.
     */
    public static String key(String s) {
        String v = slashes(s);
        if (v != null
                && v.length() >= 3
                && Character.isLowerCase(v.charAt(0))
                && v.charAt(1) == ':'
                && v.charAt(2) == '/') {
            v = Character.toUpperCase(v.charAt(0)) + v.substring(1);
        }
        return v;
    }
}
