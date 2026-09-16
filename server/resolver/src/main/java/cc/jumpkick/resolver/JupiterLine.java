// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import org.jspecify.annotations.Nullable;

/**
 * The JUnit Platform line a declared Jupiter runs on. Jupiter 5.x.y ships with Platform 1.x.y;
 * from Jupiter 6 the two share one number. A Platform 6 launcher beside a Jupiter 5 engine drops
 * the engine without a word and the run reports no tests, so the launcher {@code jk lock} injects
 * follows the declared Jupiter's line, and a lock whose lines still disagree is refused.
 */
public final class JupiterLine {

    private JupiterLine() {}

    static final String JUPITER_GROUP = "org.junit.jupiter";
    static final String LAUNCHER = "org.junit.platform:junit-platform-launcher";
    private static final String ENGINE = JUPITER_GROUP + ":junit-jupiter-engine";
    private static final String API = JUPITER_GROUP + ":junit-jupiter-api";

    /** The Platform version Jupiter {@code version} ships with: {@code 5.9.0 -> 1.9.0}, {@code 6.1.3 -> 6.1.3}. */
    public static String platformVersion(String version) {
        return version.startsWith("5.") ? "1." + version.substring(2) : version;
    }

    /**
     * The selector the injected launcher rides: the declared Jupiter's selector moved onto the
     * Platform line ({@code 5.9.0 -> 1.9.0}, {@code ^5.9 -> ^1.9}), or {@code latest} when the
     * module declares no Jupiter, or one whose selector names no line (a range, latest).
     */
    static VersionSelector launcherSelector(JkBuild project) {
        Dependency jupiter = declaredJupiter(project);
        return jupiter == null ? latest() : onLine(jupiter.version(), true);
    }

    /**
     * The selector an injected engine of another framework rides (Vintage): Jupiter's own line,
     * which the engines share, or {@code latest} when the module declares no Jupiter.
     */
    static VersionSelector engineSelector(JkBuild project) {
        Dependency jupiter = declaredJupiter(project);
        return jupiter == null ? latest() : onLine(jupiter.version(), false);
    }

    private static VersionSelector onLine(VersionSelector declared, boolean platform) {
        return switch (declared) {
            case VersionSelector.Exact e ->
                VersionSelector.parse(platform ? platformVersion(e.version()) : e.version());
            case VersionSelector.Caret c ->
                VersionSelector.parse("^" + (platform ? platformVersion(c.version()) : c.version()));
            case VersionSelector.Tilde t ->
                VersionSelector.parse("~" + (platform ? platformVersion(t.version()) : t.version()));
            default -> latest();
        };
    }

    private static VersionSelector latest() {
        return VersionSelector.parse("latest");
    }

    /** The declared test-scope Jupiter artifact — aggregate, api, engine or params — or null. */
    static @Nullable Dependency declaredJupiter(JkBuild project) {
        for (Scope scope : new Scope[] {Scope.TEST, Scope.TEST_DEV}) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (d.module().startsWith(JUPITER_GROUP + ":junit-jupiter") && !d.isFile()) return d;
            }
        }
        return null;
    }

    /**
     * Refuse a solved test graph whose launcher and Jupiter engine sit on different Platform
     * lines: the run would discover nothing and report success.
     */
    static void checkAligned(Resolution test) {
        String launcher = versionOf(test, LAUNCHER);
        String jupiter = versionOf(test, ENGINE);
        if (jupiter == null) jupiter = versionOf(test, API);
        if (launcher == null || jupiter == null) return;
        String expected = platformVersion(jupiter);
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
