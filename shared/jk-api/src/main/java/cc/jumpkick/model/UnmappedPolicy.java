// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;

/**
 * How bare EffectivePom fills for GAs the platform BOM does NOT manage are constrained when a
 * platform is declared.
 *
 * <ul>
 * <li>{@link #MEDIATE} (default) — unmapped fills stay highest-wins floors, Maven/Gradle
 * mediation parity: an everyday diamond (two transitives filling different versions of an
 * unmanaged GA) resolves instead of hard-conflicting. The named-locks class of hazard is
 * covered separately by family-align mapping the risky GAs INTO the BOM constraints.
 * <li>{@link #STRICT} (opt-in, {@code [resolve] unmapped = "strict"}) — unmapped fills are
 * exact: maximum reproducibility, every diamond on an unmanaged GA is a hard error.
 * </ul>
 */
public enum UnmappedPolicy {
    MEDIATE,
    STRICT;

    /** Parse {@code mediate} / {@code strict} (case-insensitive). Default mediate. */
    public static UnmappedPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return MEDIATE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "mediate", "highest-wins" -> MEDIATE;
            case "strict", "exact" -> STRICT;
            default ->
                throw new IllegalArgumentException("unknown unmapped policy `" + raw + "` (want mediate or strict)");
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
