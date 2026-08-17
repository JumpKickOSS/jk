// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import java.util.regex.Pattern;

/**
 * Maven-canonical version ordering via {@link MavenVersion} (numeric segments, GA-equivalent
 * normalization, qualifier order, snapshot timestamps).
 */
public final class Versions {

    private Versions() {}

    /**
     * Return {@code <0}, {@code 0}, or {@code >0} per {@link Comparable}, comparing {@code a} and
     * {@code b} as Maven version strings.
     */
    public static int compare(String a, String b) {
        if (a.equals(b)) return 0;
        return new MavenVersion(a).compareTo(new MavenVersion(b));
    }

    /**
     * Pre-release tokens including those Maven ranks above release ({@code pre}/{@code ea}/…).
     * Qualifiers are anchored on a separator or string start so bare {@code m} digits mid-token do
     * not false-positive; {@code m}/{@code M} milestones require a following digit ({@code M2},
     * {@code m1}).
     */
    private static final Pattern PRE_RELEASE = Pattern.compile("(?i)(?:^|[-_.+])("
            + "alpha|beta|milestone|rc|cr|pre|preview|snapshot|dev|ea|canary|nightly"
            + "|m\\d+"
            + ")(?:$|[-_.+\\d])");

    /** Maven's resolved-snapshot form, e.g. {@code 1.0-20260520.123456-7} — always a pre-release. */
    private static final Pattern SNAPSHOT_TIMESTAMP = Pattern.compile("-\\d{8}\\.\\d{6}-\\d+$");

    /**
     * True for a stable release: sorts ≥ its numeric core under {@link MavenVersion}, has no
     * pre-release qualifier, and is not a timestamped snapshot. No numeric core → unstable.
     * Release synonyms ({@code Final}, {@code RELEASE}, {@code GA}) remain stable.
     */
    public static boolean isStable(String version) {
        String core = numericCore(version);
        if (core.isEmpty()) return false;
        if (SNAPSHOT_TIMESTAMP.matcher(version).find()) return false;
        if (new MavenVersion(version).compareTo(new MavenVersion(core)) < 0) return false;
        return !PRE_RELEASE.matcher(version).find();
    }

    /**
     * The leading run of digits and dots (e.g. {@code 2.4.0} of {@code 2.4.0-RC2}), no trailing
     * dot. Used by caret/tilde bound math so pre-release anchors do not poison segment parsing.
     */
    public static String numericCore(String version) {
        int i = 0;
        while (i < version.length()) {
            char c = version.charAt(i);
            if (Character.isDigit(c) || c == '.') i++;
            else break;
        }
        String core = version.substring(0, i);
        while (core.endsWith(".")) core = core.substring(0, core.length() - 1);
        return core;
    }
}
