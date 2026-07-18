// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Maven-canonical version ordering via {@link ComparableVersion} (numeric segments, GA-equivalent
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
        return new ComparableVersion(a).compareTo(new ComparableVersion(b));
    }

    /** Pre-release tokens including those Maven ranks above release ({@code pre}/{@code ea}/…). */
    private static final java.util.regex.Pattern PRE_RELEASE = java.util.regex.Pattern.compile(
            "(?i)(?:^|[-_.+])(alpha|beta|milestone|m\\d|rc|cr|pre|preview|snapshot|dev|ea|canary|nightly)"
                    + "(?:[-_.+\\d]|$)");

    /** Maven's resolved-snapshot form, e.g. {@code 1.0-20260520.123456-7} — always a pre-release. */
    private static final java.util.regex.Pattern SNAPSHOT_TIMESTAMP =
            java.util.regex.Pattern.compile("-\\d{8}\\.\\d{6}-\\d+$");

    /**
     * True for a stable release: sorts ≥ its numeric core under {@link ComparableVersion}, has no
     * pre-release qualifier, and is not a timestamped snapshot. No numeric core → unstable.
     */
    public static boolean isStable(String version) {
        String core = numericCore(version);
        if (core.isEmpty()) return false;
        if (SNAPSHOT_TIMESTAMP.matcher(version).find()) return false;
        if (new ComparableVersion(version).compareTo(new ComparableVersion(core)) < 0) return false;
        return !PRE_RELEASE.matcher(version).find();
    }

    /**
     * The leading run of digits and dots (e.g. {@code 2.4.0} of {@code 2.4.0-RC2}), no trailing dot.
     */
    private static String numericCore(String version) {
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
