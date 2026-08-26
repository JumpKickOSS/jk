// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Major-or-better matching for lock {@code [jdk]} / {@code [graal]} pins. Exact vendor+version
 * wins, then the same vendor at a newer point release, then any vendor that still meets the floor.
 * The floor is {@code major(hit) > major(lock)} or the same major with {@code version >=} locked.
 */
public final class LockPinMatch {

    private LockPinMatch() {}

    /** Best installed hit for {@code vendor} + {@code version}, or empty when the floor is unmet. */
    public static Optional<JdkHit> choose(List<JdkHit> installed, String vendor, String version) {
        if (installed == null || version == null || version.isBlank()) return Optional.empty();
        List<JdkHit> ok = new ArrayList<>();
        for (JdkHit h : installed) {
            if (h != null && meetsFloor(h.version(), version)) ok.add(h);
        }
        if (ok.isEmpty()) return Optional.empty();

        String wantVendor = vendor == null ? "" : vendor.strip();
        if (!wantVendor.isEmpty()) {
            Optional<JdkHit> exact = best(ok.stream()
                    .filter(h -> vendorMatches(h.vendor(), wantVendor) && versionEquals(h.version(), version))
                    .toList());
            if (exact.isPresent()) return exact;
            Optional<JdkHit> sameVendor = best(ok.stream()
                    .filter(h -> vendorMatches(h.vendor(), wantVendor))
                    .toList());
            if (sameVendor.isPresent()) return sameVendor;
        }
        return best(ok);
    }

    /** {@link #choose} restricted to GraalVM hits. */
    public static Optional<JdkHit> chooseGraal(List<JdkHit> installed, String vendor, String version) {
        if (installed == null) return Optional.empty();
        List<JdkHit> graals = new ArrayList<>();
        for (JdkHit h : installed) {
            if (DefaultGraalPolicy.isGraal(h)) graals.add(h);
        }
        return choose(graals, vendor, version);
    }

    /**
     * True when {@code hitVersion} is the same major with a newer-or-equal point release, or a
     * higher major.
     */
    public static boolean meetsFloor(String hitVersion, String lockedVersion) {
        Integer hitMajor = JdkKeywords.leadingMajor(hitVersion);
        Integer lockMajor = JdkKeywords.leadingMajor(lockedVersion);
        if (hitMajor == null || lockMajor == null) return false;
        if (hitMajor > lockMajor) return true;
        if (hitMajor < lockMajor) return false;
        return JdkSelector.versionKey(hitVersion).compareTo(JdkSelector.versionKey(lockedVersion)) >= 0;
    }

    /** Short vendor id recorded in the lock ({@code temurin}, {@code graalvm-ce}, …). */
    public static String vendorId(JdkVendor vendor) {
        if (vendor == null) return "unknown";
        return vendor.jbPrefix().orElse(vendor.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public static boolean vendorMatches(JdkVendor vendor, String locked) {
        if (vendor == null || locked == null || locked.isBlank()) return false;
        String want = locked.strip().toLowerCase(Locale.ROOT);
        if (vendor.jbPrefix().isPresent() && vendor.jbPrefix().get().equalsIgnoreCase(want)) return true;
        if (vendor.foojayDistro().isPresent() && vendor.foojayDistro().get().equalsIgnoreCase(want)) {
            return true;
        }
        if (vendor.sdkmanSuffix().isPresent() && vendor.sdkmanSuffix().get().equalsIgnoreCase(want)) {
            return true;
        }
        return vendor.name().replace('_', '-').equalsIgnoreCase(want);
    }

    public static Lockfile.JdkPin jdkPin(JdkHit hit) {
        return new Lockfile.JdkPin(vendorId(hit.vendor()), hit.version() == null ? "" : hit.version());
    }

    public static Lockfile.GraalPin graalPin(JdkHit hit) {
        return new Lockfile.GraalPin(vendorId(hit.vendor()), hit.version() == null ? "" : hit.version());
    }

    /** Catalog/install spec for an unsatisfied lock pin ({@code temurin-25}, or {@code 25}). */
    public static String installSpec(String vendor, String version) {
        Integer m = JdkKeywords.leadingMajor(version);
        String major = m == null ? version : String.valueOf(m);
        if (vendor == null || vendor.isBlank()) return major;
        return vendor.strip() + "-" + major;
    }

    public static boolean sameHome(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    public static Optional<JdkHit> hitFor(Path home, List<JdkHit> installed) {
        if (home == null || installed == null) return Optional.empty();
        for (JdkHit h : installed) {
            if (sameHome(h.home(), home)) return Optional.of(h);
        }
        return Optional.empty();
    }

    private static boolean versionEquals(String hit, String locked) {
        return hit != null && hit.equals(locked);
    }

    private static Optional<JdkHit> best(List<JdkHit> pool) {
        if (pool.isEmpty()) return Optional.empty();
        List<JdkHit> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingInt((JdkHit h) -> {
                    Integer m = JdkKeywords.leadingMajor(h.version());
                    return m == null ? Integer.MIN_VALUE : m;
                })
                .reversed()
                .thenComparing(
                        h -> h.version() == null ? "" : JdkSelector.versionKey(h.version()), Comparator.reverseOrder())
                .thenComparingInt(
                        h -> h.vendor() == null ? Integer.MAX_VALUE : h.vendor().preferenceRank()));
        return Optional.of(sorted.getFirst());
    }
}
