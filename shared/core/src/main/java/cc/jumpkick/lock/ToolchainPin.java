// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import org.jspecify.annotations.Nullable;

/**
 * What a lock says about one toolchain, on two independent axes.
 *
 * <p>{@code suggested-*} is a record of what created the lock. It does not bind a later build:
 * its major is a floor, so a newer JDK is fine and an older one is not. {@code required-*} is a
 * pin the project asked for with {@code =} in its manifest — the vendor, the version, or both
 * must match exactly, and jk installs that toolchain rather than settling for what is here.
 *
 * <p>The axes are per-field: a lock may require a vendor while only suggesting a version. A
 * field is written on exactly one axis, never both — a required vendor makes the suggested one
 * meaningless. Missing values are {@code ""}, never null.
 */
public sealed interface ToolchainPin permits JdkPin, GraalPin {
    String suggestedVendor();

    String suggestedVersion();

    String requiredVendor();

    String requiredVersion();

    /** The vendor to honour, required or merely suggested; {@code ""} when the lock names none. */
    default String vendor() {
        return requiredVendor().isEmpty() ? suggestedVendor() : requiredVendor();
    }

    /** The version to honour, required or merely suggested; {@code ""} when the lock names none. */
    default String version() {
        return requiredVersion().isEmpty() ? suggestedVersion() : requiredVersion();
    }

    default boolean hasRequirement() {
        return !requiredVendor().isEmpty() || !requiredVersion().isEmpty();
    }

    default boolean isEmpty() {
        return vendor().isEmpty() && version().isEmpty();
    }

    /**
     * All four fields, for cache keys. A fingerprint that folded the axes together would
     * collide across two locks that ask for very different things.
     */
    default String fingerprint() {
        return String.join("|", suggestedVendor(), suggestedVersion(), requiredVendor(), requiredVersion());
    }

    /** Trimmed text, or {@code ""} for a missing or blank value. Toolchain pins never hold null. */
    static String blankToEmpty(@Nullable String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }
}
