// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which JDK majors jk surfaces: floor {@link #MIN_MAJOR} (17), every LTS at or above it, plus the
 * single newest major in the filtered set (bleeding edge between LTS cuts).
 */
public final class SupportedJdk {

    /** Lowest major jk will accept anywhere. */
    public static final int MIN_MAJOR = 17;

    private SupportedJdk() {}

    /**
     * Cheap "is this major fundamentally acceptable" check — strictly the {@link #MIN_MAJOR} floor.
     * Use this for jk.toml parsing where we don't have catalog context yet; downstream resolution
     * will reject non-LTS / non-latest values when the catalog filter strips them.
     */
    public static boolean isSupported(int major) {
        return major >= MIN_MAJOR;
    }

    /**
     * Full predicate: an LTS at or above 17, or the single latest major in the surrounding set.
     * {@code latestAvailable} is the max major the caller wants to admit as "current"; pass it 0 to
     * mean "no latest pass" (LTS-only).
     */
    public static boolean isFirstClass(int major, int latestAvailable) {
        if (major < MIN_MAJOR) return false;
        if (JdkLts.isLtsMajor(major)) return true;
        return latestAvailable > 0 && major == latestAvailable;
    }

    /**
     * Subset of {@code available} that {@link #isFirstClass} accepts, with {@code latestAvailable}
     * inferred from {@code available} itself — caller doesn't have to pre-compute the max.
     */
    public static Set<Integer> firstClassMajors(Collection<Integer> available) {
        if (available == null || available.isEmpty()) return Set.of();
        int latest = available.stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<Integer> out = new TreeSet<>();
        for (int m : available) {
            if (isFirstClass(m, latest)) out.add(m);
        }
        return out;
    }

    /**
     * A GraalVM / native-image-capable catalog entry. Identified by {@code suggested_sdk_name}
     * starting with {@code graalvm} ({@code graalvm-jdk-<major>} = Oracle GraalVM,
     * {@code graalvm-ce-<major>} = GraalVM Community) — the robust signal, since the feed's
     * {@code (vendor, product)} strings for CE don't match {@link JdkVendor}'s constants.
     */
    public static boolean isNativeImageCapable(JdkCatalog.Entry e) {
        return e.suggestedSdkName() != null && e.suggestedSdkName().startsWith("graalvm");
    }

    /**
     * Language-level majors for {@code jk new} (newest-first from the live catalog). {@code
     * nativeOnly} restricts to GraalVM; empty catalog → empty list for offline fallbacks.
     */
    public static List<Integer> offerableMajors(JdkCatalog catalog, boolean nativeOnly, String os, String arch) {
        if (catalog == null) return List.of();
        TreeSet<Integer> avail = new TreeSet<>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.os().equalsIgnoreCase(os)
                    && e.arch().equalsIgnoreCase(arch)
                    && !e.preview()
                    && e.majorVersion() >= MIN_MAJOR
                    && (!nativeOnly || isNativeImageCapable(e))) {
                avail.add(e.majorVersion());
            }
        }
        if (avail.isEmpty()) return List.of();
        int max = avail.last();
        int maxLts = JdkLts.latestLtsIn(avail).orElse(MIN_MAJOR);
        List<Integer> out = new ArrayList<>();
        for (int v = maxLts; v >= MIN_MAJOR; v--) {
            if (JdkLts.isLtsMajor(v) && avail.contains(v)) out.add(v);
        }
        if (max > maxLts) out.add(max); // the newest non-LTS release, offered last
        return out;
    }
}
