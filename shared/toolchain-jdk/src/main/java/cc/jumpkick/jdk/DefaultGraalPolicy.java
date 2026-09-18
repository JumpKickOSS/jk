// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * De-facto default GraalVM when no explicit {@code jk jdk graal} / lock {@code [graal]} is set:
 * preferred newest among Oracle GraalVM and GraalVM CE. Pure/offline — not persisted ({@link
 * JdkInventory} wins when present).
 */
public final class DefaultGraalPolicy {

    private DefaultGraalPolicy() {}

    /**
     * Choose the de-facto Graal among {@code installed} (non-Graal hits ignored). Empty when none
     * are installed.
     */
    public static Optional<JdkHit> choose(List<JdkHit> installed) {
        if (installed == null || installed.isEmpty()) return Optional.empty();
        return installed.stream().filter(DefaultGraalPolicy::isGraal).min(byPreference());
    }

    /** True for Oracle GraalVM or GraalVM CE. */
    public static boolean isGraal(JdkHit h) {
        return h != null && isGraal(h.vendor());
    }

    /**
     * True for the two GraalVM flavours. Takes the vendor alone, for a caller holding a catalog
     * entry rather than an installed hit — {@code JdkInstaller} asks this to decide whether an
     * install has to carry a {@code native-image} launcher to count as finished.
     */
    public static boolean isGraal(@Nullable JdkVendor vendor) {
        return vendor == JdkVendor.ORACLE_GRAALVM || vendor == JdkVendor.GRAALVM_CE;
    }

    /**
     * The Graal among {@code installed} that can build for {@code javaRelease}: the LOWEST major at
     * or above it, Oracle before CE, newest within a flavour. Empty when none reaches the floor,
     * and the whole set when {@code javaRelease} is 0 (nothing declared, so nothing to clear).
     *
     * <p>A floor and not an equality. A module targeting Java 21 is served perfectly well by a
     * GraalVM 25 — {@code --release 21} is what the compiler is for — and downloading a second
     * GraalVM to match the number exactly would be a few hundred megabytes spent to no effect. The
     * lowest qualifying major wins rather than the newest, so a workspace does not drift onto a
     * toolchain no module asked for.
     */
    public static Optional<JdkHit> choose(List<JdkHit> installed, int javaRelease) {
        if (installed == null || installed.isEmpty()) return Optional.empty();
        return installed.stream()
                .filter(DefaultGraalPolicy::isGraal)
                .filter(h -> clearsFloor(h, javaRelease))
                .min(Comparator.comparingInt((JdkHit h) -> {
                            Integer major = JdkKeywords.leadingMajor(h.version());
                            return major == null ? Integer.MAX_VALUE : major;
                        })
                        .thenComparing(byPreference()));
    }

    private static boolean clearsFloor(JdkHit hit, int javaRelease) {
        if (javaRelease <= 0) return true;
        Integer major = JdkKeywords.leadingMajor(hit.version());
        return major != null && major >= javaRelease;
    }

    /** Oracle GraalVM before GraalVM CE; newer version first within a flavour. */
    public static Comparator<JdkHit> byPreference() {
        return Comparator.comparingInt((JdkHit h) -> {
                    int i = JdkVendor.GRAAL_PREFERENCE.indexOf(h.vendor());
                    return i >= 0 ? i : Integer.MAX_VALUE;
                })
                .thenComparing(
                        h -> h.version() == null ? "" : JdkSelector.versionKey(h.version()), Comparator.reverseOrder());
    }
}
