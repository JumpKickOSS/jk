// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * How platform BOM managed pins constrain PubGrub edges (JK-1206). Fills for GAs the BOM does
 * NOT manage are governed separately by {@link UnmappedPolicy} (JK-1241).
 *
 * <ul>
 *   <li>{@link #ENFORCED} (default) — BOM map pin is exact (Maven depMgmt contract).
 *   <li>{@link #FLOOR} (opt-in) — BOM map pin is a lower bound ({@code atLeast}); highest-wins may
 *       lift above the pin.
 * </ul>
 */
public enum PlatformPolicy {
    ENFORCED,
    FLOOR;

    /** Parse {@code enforced} / {@code floor} (case-insensitive). Default enforced. */
    public static PlatformPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return ENFORCED;
        return switch (raw.trim().toLowerCase()) {
            case "enforced", "exact", "hard" -> ENFORCED;
            case "floor", "soft", "lift" -> FLOOR;
            default -> throw new IllegalArgumentException(
                    "unknown platform policy `" + raw + "` (want enforced or floor)");
        };
    }

    public String wireName() {
        return name().toLowerCase();
    }
}
