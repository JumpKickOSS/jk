// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
        return h != null && (h.vendor() == JdkVendor.ORACLE_GRAALVM || h.vendor() == JdkVendor.GRAALVM_CE);
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
