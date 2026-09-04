// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Declared Nerd Font intent — what the user asked for, before detection runs. The resolved
 * capability is {@link NerdFontCaps}; {@link #AUTO} is the only mode whose caps are not fixed.
 *
 * <p>Spelled in {@code ~/.jk/config.toml} as root-level {@code nerd-font} and in the
 * environment as {@code JK_NERD_FONT}.
 */
public enum NerdFontMode {

    /** {@code false} — no PUA glyphs. */
    OFF,

    /** {@code true} — every PUA glyph. */
    ON,

    /** {@code "auto"} — probe the terminal. The default. */
    AUTO,

    /** {@code "wedge"} — powerline caps on the wedge, plain padded chips for pills. */
    WEDGE,

    /** {@code "pill"} — half-circle pill caps, no custom wedge cap. */
    PILL;

    /**
     * Parse a config or env value. Accepts the jk-wide boolean truth set via {@link
     * EnvValues#parseBool} (so {@code on}/{@code off} come along with {@code true}/{@code false})
     * plus the three mode words. Anything unrecognised — including blank — yields empty so the
     * caller falls through to the next precedence layer, exactly as a bad boolean does today.
     */
    public static Optional<NerdFontMode> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim().toLowerCase(Locale.ROOT);
        // Strip TOML quoting so a hand-edited `nerd-font = "wedge"` scans the same as a bare word.
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return switch (v) {
            case "auto" -> Optional.of(AUTO);
            case "wedge" -> Optional.of(WEDGE);
            case "pill" -> Optional.of(PILL);
            default -> EnvValues.parseBool(v).map(b -> b ? ON : OFF);
        };
    }

    /**
     * Parse a value that may only be a boolean — the host-wide {@code NERD_FONT} contract. A mode
     * word there is ignored rather than honoured, since {@code NERD_FONT} is a cross-tool variable
     * with no notion of jk's axes.
     */
    public static Optional<NerdFontMode> parseBooleanOnly(@Nullable String raw) {
        return EnvValues.parseBool(raw).map(b -> b ? ON : OFF);
    }

    /** The value as written back to {@code config.toml} — quoted for the word forms. */
    public String toToml() {
        return switch (this) {
            case OFF -> "false";
            case ON -> "true";
            case AUTO -> "\"auto\"";
            case WEDGE -> "\"wedge\"";
            case PILL -> "\"pill\"";
        };
    }

    /** Fixed caps for every mode but {@link #AUTO}, which must be detected. */
    public NerdFontCaps fixedCaps() {
        return switch (this) {
            case OFF -> NerdFontCaps.NONE;
            case ON -> NerdFontCaps.ALL;
            case WEDGE -> NerdFontCaps.WEDGE_ONLY;
            case PILL -> NerdFontCaps.PILL_ONLY;
            case AUTO -> throw new IllegalStateException("AUTO has no fixed caps — run detection");
        };
    }
}
