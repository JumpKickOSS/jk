// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Locale;

/**
 * Host OS predicates, read from {@code os.name}. Lives in the {@code :host} leaf so the native
 * client, the engine and every plugin worker answer "which OS is this?" the same way — a worker
 * cannot reach {@code cc.jumpkick.jdk.HostPlatform}, which is what made the copies multiply.
 */
public final class Os {
    private Os() {}

    /** {@code os.name} lowercased contains {@code "windows"} — not {@code "win"}, which matches Darwin. */
    public static boolean isWindows() {
        return name().contains("windows");
    }

    /** {@code os.name} starts with {@code Mac} or {@code Darwin}. */
    public static boolean isDarwin() {
        String n = System.getProperty("os.name", "");
        return n.startsWith("Mac") || n.startsWith("Darwin");
    }

    /** {@code os.name} starts with {@code Linux}. */
    public static boolean isLinux() {
        return System.getProperty("os.name", "").startsWith("Linux");
    }

    private static String name() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}
