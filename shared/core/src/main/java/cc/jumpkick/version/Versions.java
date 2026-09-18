// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.version;

import java.util.concurrent.ConcurrentHashMap;
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
        return parsed(a).compareTo(parsed(b));
    }

    /**
     * Parsed versions, by string. A resolve compares the same few thousand version strings against
     * each other millions of times, and tokenizing a string costs far more than comparing two
     * parsed ones. {@link MavenVersion} is immutable once constructed, so instances are shared.
     * Bounded: the cache is emptied when it fills rather than evicting one entry at a time.
     */
    private static final ConcurrentHashMap<String, MavenVersion> PARSED = new ConcurrentHashMap<>();

    private static final int PARSED_LIMIT = 16_384;

    /** Drop every parsed version and return how many went; for the idle engine. */
    public static int dropParsed() {
        int dropped = PARSED.size();
        PARSED.clear();
        return dropped;
    }

    private static MavenVersion parsed(String version) {
        MavenVersion known = PARSED.get(version);
        if (known != null) return known;
        MavenVersion fresh = new MavenVersion(version);
        if (PARSED.size() >= PARSED_LIMIT) PARSED.clear();
        PARSED.put(version, fresh);
        return fresh;
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
     * True for a Maven snapshot: a {@code -SNAPSHOT} version or its resolved timestamped form. A
     * snapshot is served only by a repository whose snapshot policy is enabled, and never by
     * Maven Central.
     */
    public static boolean isSnapshot(String version) {
        return version.endsWith("-SNAPSHOT")
                || SNAPSHOT_TIMESTAMP.matcher(version).find();
    }

    /**
     * True for a stable release: sorts ≥ its numeric core under {@link MavenVersion}, has no
     * pre-release qualifier, and is not a timestamped snapshot. No numeric core → unstable.
     * Release synonyms ({@code Final}, {@code RELEASE}, {@code GA}) remain stable.
     */
    public static boolean isStable(String version) {
        String core = numericCore(version);
        if (core.isEmpty()) return false;
        if (SNAPSHOT_TIMESTAMP.matcher(version).find()) return false;
        if (parsed(version).compareTo(parsed(core)) < 0) return false;
        return !PRE_RELEASE.matcher(version).find();
    }

    /**
     * True when the bytes published at {@code version} may still move: an unstable qualifier or
     * snapshot per {@link #isStable}, or a {@code 0.x} version — under SemVer a major of zero is
     * initial development, and every release of it may change anything. A pin on such a version
     * fixes a name, not bytes.
     */
    public static boolean isPreRelease(String version) {
        if (!isStable(version)) return true;
        String core = numericCore(version);
        int dot = core.indexOf('.');
        String major = dot < 0 ? core : core.substring(0, dot);
        return major.isEmpty() || Long.parseLong(major) == 0;
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
