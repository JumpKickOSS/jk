// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import org.jspecify.annotations.Nullable;

/** Refuses a solved test graph whose launcher and Jupiter engine sit on different Platform lines ({@link JupiterLine}). */
final class JupiterAlignment {

    private JupiterAlignment() {}

    /** Throws when the launcher and Jupiter lines disagree: the run would discover nothing and report success. */
    static void check(Resolution test) {
        String launcher = versionOf(test, JupiterLine.LAUNCHER);
        String jupiter = versionOf(test, JupiterLine.ENGINE);
        if (jupiter == null) jupiter = versionOf(test, JupiterLine.API);
        if (launcher == null || jupiter == null) return;
        String expected = JupiterLine.platformVersion(jupiter);
        if (major(expected).equals(major(launcher))) return;
        throw new IllegalArgumentException("junit-jupiter "
                + jupiter
                + " runs on JUnit Platform "
                + expected
                + ", but junit-platform-launcher resolved to "
                + launcher
                + ": a Platform "
                + major(launcher)
                + " launcher drops a Jupiter "
                + major(jupiter)
                + " engine without a word and the run reports no tests — pin junit-platform-launcher to "
                + expected
                + " (or move junit-jupiter to the launcher's line) and lock again");
    }

    private static @Nullable String versionOf(Resolution test, String module) {
        for (Resolution.ResolvedModule m : test.modules().values()) {
            if (m.module().equals(module) || m.module().startsWith(module + ":")) return m.version();
        }
        return null;
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }
}
