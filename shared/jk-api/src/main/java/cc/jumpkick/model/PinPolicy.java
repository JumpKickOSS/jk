// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;

/**
 * How a project's own exact pin meets a transitive POM's constraint on the same module.
 *
 * <ul>
 * <li>{@link #EXACT} (default) — the pin is one constraint among all of them; a transitive floor
 * above it is a conflict the lock refuses with PubGrub's explanation.
 * <li>{@link #NEAREST} ({@code [resolve] pins = "nearest"}) — the pin is the version, as a direct
 * dependency's is under Maven's nearest-wins: a transitive's range on that module is reported
 * beside the edge in the lock, not enforced. {@code jk import} writes this for a Maven POM so the
 * imported project resolves the way Maven resolved it.
 * </ul>
 */
public enum PinPolicy {
    EXACT,
    NEAREST;

    /** Parse {@code exact} / {@code nearest} (case-insensitive). Default exact. */
    public static PinPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return EXACT;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "exact", "strict" -> EXACT;
            case "nearest", "maven" -> NEAREST;
            default -> throw new IllegalArgumentException("unknown pin policy `" + raw + "` (want exact or nearest)");
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
