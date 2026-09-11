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
import org.jspecify.annotations.Nullable;

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

    /**
     * Exact vendor+version first, then the same vendor on the named major, then the same vendor on
     * any major that clears the floor, then the named major from any vendor, then the newest of
     * what is left.
     *
     * <p>The named major ranks above a newer one on purpose. A suggestion is a floor, so 25 clears
     * a {@code jdk = 17} — but when a 17 is installed too, the build the manifest named is the one
     * to fork: a {@code jdk = 17} project compiling and testing on 25 with a 17 on disk is the
     * resolver choosing for the user, with no way to get 17 back short of uninstalling 25.
     */
    private static Optional<JdkHit> rank(List<JdkHit> ok, String vendor, String version) {
        if (ok.isEmpty()) return Optional.empty();
        String wantVendor = vendor == null ? "" : vendor.strip();
        Integer wantMajor = version == null ? null : JdkKeywords.leadingMajor(version);
        if (!wantVendor.isEmpty()) {
            Optional<JdkHit> exact = best(ok.stream()
                    .filter(h -> vendorMatches(h.vendor(), wantVendor) && versionEquals(h.version(), version))
                    .toList());
            if (exact.isPresent()) return exact;
            Optional<JdkHit> sameVendorMajor = best(ok.stream()
                    .filter(h -> vendorMatches(h.vendor(), wantVendor) && sameMajor(h.version(), wantMajor))
                    .toList());
            if (sameVendorMajor.isPresent()) return sameVendorMajor;
            Optional<JdkHit> sameVendor = best(ok.stream()
                    .filter(h -> vendorMatches(h.vendor(), wantVendor))
                    .toList());
            if (sameVendor.isPresent()) return sameVendor;
        }
        Optional<JdkHit> sameMajor =
                best(ok.stream().filter(h -> sameMajor(h.version(), wantMajor)).toList());
        if (sameMajor.isPresent()) return sameMajor;
        return best(ok);
    }

    private static boolean sameMajor(@Nullable String hitVersion, @Nullable Integer wantMajor) {
        if (wantMajor == null || hitVersion == null) return false;
        Integer m = JdkKeywords.leadingMajor(hitVersion);
        return m != null && m.intValue() == wantMajor.intValue();
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
    public static Lockfile.JdkPin jdkPin(
            @Nullable ToolchainSpec spec, @Nullable JdkHit hit, Lockfile.@Nullable ToolchainPin previous) {
        String[] f = fields(spec, hit, previous);
        return new Lockfile.JdkPin(f[0], f[1], f[2], f[3]);
    }

    /** The {@code [graal]} table for {@code spec}, with blanks filled from the GraalVM that resolved. */
    public static Lockfile.GraalPin graalPin(
            @Nullable ToolchainSpec spec, @Nullable JdkHit hit, Lockfile.@Nullable ToolchainPin previous) {
        String[] f = fields(spec, hit, previous);
        return new Lockfile.GraalPin(f[0], f[1], f[2], f[3]);
    }

    /**
     * The four lock fields for one toolchain, in order of authority.
     *
     * <p>What the manifest declared wins — it is the contract, and a later build owes that rather
     * than whatever patch happened to be current here. Failing that, {@code previous} holds: a
     * plain {@code jk lock} must not rewrite the record of what built the lock just because this
     * machine has a different JDK. Every re-lock passes it but {@code jk update}, so there — where
     * floating to the latest is the point — the suggestion refreshes from the toolchain that
     * resolved. Only with neither does the resolved toolchain fill in, which is what makes a
     * first lock record anything at all.
     *
     * <p>A required field leaves its suggested counterpart empty: writing both would state a floor
     * the requirement has already overruled.
     */
    private static String[] fields(
            @Nullable ToolchainSpec spec, @Nullable JdkHit hit, Lockfile.@Nullable ToolchainPin previous) {
        ToolchainSpec s = spec == null ? ToolchainSpec.NONE : spec;
        // Manifest dropped the pin: keep a previous suggestion only when it still names
        // something installable. Copying nosuchvendor-99 would make the next build try to
        // install a catalog-missing spec instead of using the JDK that just resolved.
        if (s.isEmpty() && previous != null && !previous.hasRequirement() && !suggestionIsInstallable(previous)) {
            previous = null;
        }
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

    /**
     * Whether a suggestion-only pin names a vendor the catalog can install. Unknown vendors
     * ({@code nosuchvendor-99}) are not install specs — the next build must settle on an
     * installed JDK that meets {@code java = N}, not throw {@code no JDK matches}.
     *
     * <p>{@code required-*} pins are not suggestions; this returns {@code false} for them so
     * callers do not treat a requirement as a droppable floor.
     */
    public static boolean suggestionIsInstallable(Lockfile.@Nullable ToolchainPin pin) {
        if (pin == null || pin.isEmpty() || pin.hasRequirement()) return false;
        String vendor = pin.suggestedVendor();
        return vendor.isEmpty() || knownVendorId(vendor);
    }

    /** True when {@code id} matches a vendor jk knows how to install (not {@link JdkVendor#UNKNOWN}). */
    public static boolean knownVendorId(String id) {
        if (id == null || id.isBlank()) return false;
        for (JdkVendor v : JdkVendor.values()) {
            if (v != JdkVendor.UNKNOWN && vendorMatches(v, id)) return true;
        }
        return false;
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

    public static boolean sameHome(@Nullable Path a, @Nullable Path b) {
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
