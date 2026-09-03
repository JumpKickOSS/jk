// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Derives a Maven/SemVer version from a git ref. Tag → coerced SemVer; branch →
 * {@code <branch>-SNAPSHOT}; untagged → {@code <nearest-tag>-<yyyyMMdd.HHmmss>-<shortsha>}
 * (no tag → {@code 0.0.0-…}). Timestamp form (not hex) so {@code ComparableVersion} sorts below
 * the next release; SHA is recorded in the lockfile, not only the version string.
 */
public final class GitVersion {

    /** {@code yyyyMMdd.HHmmss} in UTC — Maven's resolved-snapshot timestamp shape. */
    private static final DateTimeFormatter SNAPSHOT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC);

    private GitVersion() {}

    /** Coerce a tag to a clean version; returns the raw tag if it isn't version-like. */
    public static String fromTag(String tag) {
        String coerced = coerce(tag);
        return coerced != null ? coerced : tag.strip();
    }

    /** {@code <branch>-SNAPSHOT}, with version-unsafe characters folded to '-'. */
    public static String forBranch(String branch) {
        return sanitize(branch) + "-SNAPSHOT";
    }

    /** Untagged commit: {@code <base>-<yyyyMMdd.HHmmss>-<shortSha>} (base from nearest tag or {@code 0.0.0}). */
    public static String pseudo(Optional<String> nearestTag, Instant commitTime, String shortSha) {
        String base = nearestTag.map(GitVersion::coerce).filter(c -> c != null).orElse("0.0.0");
        return base + "-" + SNAPSHOT_TS.format(commitTime) + "-" + shortSha;
    }

    /** Coerce a tag to {@code major.minor.patch[-prerelease][+build]}, or null if not version-like. */
    static @Nullable String coerce(@Nullable String tag) {
        if (tag == null) return null;
        String s = tag.strip();
        int firstDigit = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                firstDigit = i;
                break;
            }
        }
        if (firstDigit < 0) return null;
        s = s.substring(firstDigit);

        // Split off -prerelease / +build; keep the rest verbatim.
        int dash = s.indexOf('-');
        int plus = s.indexOf('+');
        int cut = minPositive(dash, plus);
        String core = cut < 0 ? s : s.substring(0, cut);
        String suffix = cut < 0 ? "" : s.substring(cut);

        String[] parts = core.split("\\.", -1);
        StringBuilder normalizedCore = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].chars().allMatch(Character::isDigit) || parts[i].isEmpty()) {
                return null; // non-numeric core → not coercible
            }
            if (i > 0) normalizedCore.append('.');
            // strip leading zeros but keep a single 0
            normalizedCore.append(Long.parseLong(parts[i]));
        }
        // Pad to at least major.minor.patch.
        for (int n = parts.length; n < 3; n++) normalizedCore.append(".0");
        return normalizedCore + suffix;
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.strip().toCharArray()) {
            out.append((Character.isLetterOrDigit(c) || c == '.' || c == '-') ? c : '-');
        }
        String r = out.toString().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? "branch" : r;
    }
}
