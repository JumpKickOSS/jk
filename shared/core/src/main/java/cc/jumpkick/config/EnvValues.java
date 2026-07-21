// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lenient {@code JK_*} env coercion. Booleans: {@code 1/true/yes/on} vs {@code 0/false/no/off}
 * (case-insensitive); anything else including blank → empty. Sits above file layers, below CLI flags.
 */
public final class EnvValues {

    private EnvValues() {}

    /** A non-blank env value; {@code null}/blank → empty. */
    public static Optional<String> string(Function<String, String> env, String name) {
        return parseString(env.apply(name));
    }

    /** A boolean env value parsed per the jk-wide truth set; unrecognised → empty. */
    public static Optional<Boolean> bool(Function<String, String> env, String name) {
        return parseBool(env.apply(name));
    }

    /** An {@code int} env value; absent, non-numeric, or out of {@code int} range → empty. */
    public static Optional<Integer> intValue(Function<String, String> env, String name) {
        return longValue(env, name)
                .filter(l -> l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
                .map(Long::intValue);
    }

    /** A {@code long} env value; absent or non-numeric → empty. */
    public static Optional<Long> longValue(Function<String, String> env, String name) {
        String v = env.apply(name);
        if (v == null || v.isBlank()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A {@code double} env value; absent or non-numeric → empty. */
    public static Optional<Double> doubleValue(Function<String, String> env, String name) {
        String v = env.apply(name);
        if (v == null || v.isBlank()) return Optional.empty();
        try {
            return Optional.of(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Coerce a raw string to a non-blank value; {@code null}/blank → empty. */
    public static Optional<String> parseString(String raw) {
        return (raw != null && !raw.isBlank()) ? Optional.of(raw) : Optional.empty();
    }

    /**
     * Coerce a raw string to a boolean per the jk-wide truth set. Exposed (not just {@link #bool})
     * for callers that already hold the raw value, e.g. a value read from somewhere other than {@link
     * System#getenv}.
     */
    public static Optional<Boolean> parseBool(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> Optional.of(Boolean.TRUE);
            case "0", "false", "no", "off" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }
}
