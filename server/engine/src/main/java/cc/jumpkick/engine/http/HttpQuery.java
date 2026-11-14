// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Percent-decode one raw query parameter, strictly or leniently. */
final class HttpQuery {

    private HttpQuery() {}

    /**
     * The decoded value of {@code name} in a RAW query string ({@code getRawQuery()}), or null.
     * Split first, then decode each value once. Decoding never maps {@code +} to space (matches
     * the SPA's {@code encodeURIComponent}).
     */
    static @Nullable String queryParam(@Nullable String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return decodeOnce(pair.substring(eq + 1));
        }
        return null;
    }

    /**
     * Every parameter of a RAW query string, decoded; a repeated name keeps its last value. Throws
     * {@link IllegalArgumentException} on malformed percent-encoding.
     */
    static Map<String, String> params(@Nullable String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decodeOnce(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decodeOnce(pair.substring(eq + 1));
            if (name != null) out.put(name, value == null ? "" : value);
        }
        return out;
    }

    /** Percent-decode without the {@code application/x-www-form-urlencoded} {@code +}→space rule. */
    private static @Nullable String decodeOnce(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * {@link #queryParam} that treats malformed percent-encoding as an absent parameter instead of
     * throwing. For token / filter lookups where the caller's answer to garbage is "no" (401 /
     * unfiltered), not a 500 from the generic handler. Handlers that owe the client a
     * message keep the throwing form and map it to 400 themselves.
     */
    static @Nullable String queryParamLenient(@Nullable String rawQuery, String name) {
        try {
            return queryParam(rawQuery, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
