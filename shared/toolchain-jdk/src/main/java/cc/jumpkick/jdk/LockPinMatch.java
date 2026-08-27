// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.ToolchainSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Matching for lock {@code [jdk]} / {@code [graal]} pins, on the two axes the pin states.
 *
 * <p>A {@code required-*} field filters: nothing that misses it is a candidate, so an unsatisfied
 * requirement means install, never settle. A {@code suggested-*} field only ranks — its version is
 * a floor on the <em>major</em>, because the suggestion records what built the lock rather than
 * what a later build owes it. Among candidates, exact vendor+version wins, then the same vendor,
 * then the newest.
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

        return rank(ok, vendor, version);
    }

    /** Exact vendor+version first, then the same vendor, then the newest of what is left. */
    private static Optional<JdkHit> rank(List<JdkHit> ok, String vendor, String version) {
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
     * True when {@code hitVersion} is at least the major of {@code floor}. The floor is the major
     * and only the major: a lock built on 25.0.3 is met by 25.0.1 and by 26.0.2, but not by 21.
     * Holding a later build to the exact patch that happened to be current is what {@code =} in
     * the manifest — and {@code required-version} in the lock — is for.
     */
    public static boolean meetsFloor(String hitVersion, String floor) {
        Integer hitMajor = JdkKeywords.leadingMajor(hitVersion);
        Integer floorMajor = JdkKeywords.leadingMajor(floor);
        if (hitMajor == null || floorMajor == null) return false;
        return hitMajor >= floorMajor;
    }

    /** Best installed hit satisfying {@code pin}, or empty when nothing does and jk must install. */
    public static Optional<JdkHit> choose(List<JdkHit> installed, Lockfile.ToolchainPin pin) {
        if (installed == null || pin == null || pin.isEmpty()) return Optional.empty();
        List<JdkHit> ok = new ArrayList<>();
        for (JdkHit h : installed) {
            if (h != null && satisfies(h, pin)) ok.add(h);
        }
        return rank(ok, pin.vendor(), pin.version());
    }

    /** {@link #choose(List, Lockfile.ToolchainPin)} restricted to GraalVM hits. */
    public static Optional<JdkHit> chooseGraal(List<JdkHit> installed, Lockfile.ToolchainPin pin) {
        if (installed == null) return Optional.empty();
        List<JdkHit> graals = new ArrayList<>();
        for (JdkHit h : installed) {
            if (DefaultGraalPolicy.isGraal(h)) graals.add(h);
        }
        return choose(graals, pin);
    }

    /** True when {@code hit} meets every requirement the pin states, and clears its suggested floor. */
    public static boolean satisfies(JdkHit hit, Lockfile.ToolchainPin pin) {
        if (hit == null) return false;
        if (!pin.requiredVendor().isEmpty() && !vendorMatches(hit.vendor(), pin.requiredVendor())) {
            return false;
        }
        if (!pin.requiredVersion().isEmpty() && !versionEquals(hit.version(), pin.requiredVersion())) {
            return false;
        }
        return pin.suggestedVersion().isEmpty() || meetsFloor(hit.version(), pin.suggestedVersion());
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

    /** The {@code [jdk]} table for {@code spec}, with blanks filled from the JDK that resolved. */
    public static Lockfile.JdkPin jdkPin(ToolchainSpec spec, JdkHit hit, Lockfile.ToolchainPin previous) {
        String[] f = fields(spec, hit, previous);
        return new Lockfile.JdkPin(f[0], f[1], f[2], f[3]);
    }

    /** The {@code [graal]} table for {@code spec}, with blanks filled from the GraalVM that resolved. */
    public static Lockfile.GraalPin graalPin(ToolchainSpec spec, JdkHit hit, Lockfile.ToolchainPin previous) {
        String[] f = fields(spec, hit, previous);
        return new Lockfile.GraalPin(f[0], f[1], f[2], f[3]);
    }

    /**
     * The four lock fields for one toolchain, in order of authority.
     *
     * <p>What the manifest declared wins — it is the contract, and a later build owes that rather
     * than whatever patch happened to be current here. Failing that, {@code previous} holds: a
     * plain {@code jk lock} must not rewrite the record of what built the lock just because this
     * machine has a different JDK. Callers pass it only on a conservative re-lock, so
     * {@code jk update} — where floating to the latest is the point — refreshes from the toolchain
     * that resolved. Only with neither does the resolved toolchain fill in, which is what makes a
     * first lock record anything at all.
     *
     * <p>A required field leaves its suggested counterpart empty: writing both would state a floor
     * the requirement has already overruled.
     */
    private static String[] fields(ToolchainSpec spec, JdkHit hit, Lockfile.ToolchainPin previous) {
        ToolchainSpec s = spec == null ? ToolchainSpec.NONE : spec;
        String rv = hit == null || hit.vendor() == null ? "" : vendorId(hit.vendor());
        String rver = hit == null || hit.version() == null ? "" : hit.version();
        String pv = previous == null ? "" : previous.suggestedVendor();
        String pver = previous == null ? "" : previous.suggestedVersion();
        String vendor = s.requiredVendor().isEmpty() ? pick(s.suggestedVendor(), pick(pv, rv)) : "";
        String version = s.requiredVersion().isEmpty() ? pick(s.suggestedVersion(), pick(pver, rver)) : "";
        return new String[] {vendor, version, s.requiredVendor(), s.requiredVersion()};
    }

    private static String pick(String declared, String resolved) {
        return declared.isEmpty() ? resolved : declared;
    }

    /** Catalog/install spec for an unsatisfied pin. */
    public static String installSpec(Lockfile.ToolchainPin pin) {
        if (!pin.requiredVersion().isEmpty()) {
            String v = pin.requiredVersion();
            return pin.vendor().isEmpty() ? v : pin.vendor() + "-" + v;
        }
        return installSpec(pin.vendor(), pin.version());
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
