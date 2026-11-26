// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Locale;

/**
 * The host as a release asset names it, {@code <os>-<arch>}: {@code linux-x86_64}, {@code
 * macos-aarch64}, {@code windows-x86_64}. The installers derive the same names from {@code uname},
 * so jk's own native clients and the platform archives a build writes share one vocabulary.
 */
public final class ReleasePlatform {

    private ReleasePlatform() {}

    /** This host's {@code <os>-<arch>}. */
    public static String current() {
        return os(Os.name()) + "-" + arch(System.getProperty("os.arch", ""));
    }

    /** {@code linux}, {@code macos}, {@code windows}, else the OS name lower-cased ({@code freebsd}, {@code sunos}). */
    public static String os(String osName) {
        String n = osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) return "windows";
        if (n.startsWith("mac") || n.startsWith("darwin")) return "macos";
        return token(n);
    }

    /** {@code x86_64}, {@code aarch64}, else the architecture as reported. */
    public static String arch(String osArch) {
        String a = osArch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> token(a);
        };
    }

    private static String token(String s) {
        String t = s.replaceAll("[^a-z0-9_]", "_");
        return t.isEmpty() ? "unknown" : t;
    }
}
