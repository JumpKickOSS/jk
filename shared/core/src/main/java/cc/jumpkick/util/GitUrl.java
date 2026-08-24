// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Hashing;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Git dependency URL expansion and canonicalization. Shorthands ({@code gh:}, {@code gl:},
 * {@code bb:}, {@code sr:}); canonicalize lowercases scheme/host, strips default ports and
 * trailing {@code .git}/slashes; {@code git@host:…} becomes {@code ssh://git@host/…}.
 */
public final class GitUrl {

    private static final Map<String, String> SHORTHANDS = Map.of(
            "gh", "https://github.com/",
            "gl", "https://gitlab.com/",
            "bb", "https://bitbucket.org/",
            "sr", "https://git.sr.ht/");

    private GitUrl() {}

    /**
     * Expand shorthand and return the user-facing URL (preserves scheme + host capitalisation). Use
     * this when interacting with the user; use {@link #canonicalize(String)} for cache keys.
     */
    public static String expand(String input) {
        String s = input.trim();
        int colon = s.indexOf(':');
        if (colon > 0 && colon == 2) {
            String prefix = s.substring(0, colon).toLowerCase(Locale.ROOT);
            String expansion = SHORTHANDS.get(prefix);
            if (expansion != null) {
                return expansion + s.substring(colon + 1);
            }
        }
        return s;
    }

    /**
     * Return the canonical form of {@code input} — host-shorthands expanded, ssh scp-form normalised
     * to {@code ssh://}, lowercased scheme + host, default ports dropped, trailing {@code .git}
     * stripped, trailing slashes off path.
     */
    public static String canonicalize(String input) {
        String expanded = expand(input);
        String normalized = normalizeScpForm(expanded);
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (defaultPort(scheme) == port) port = -1;

        String userInfo = uri.getUserInfo();
        String path = uri.getPath() == null ? "" : uri.getPath();
        // Strip trailing slashes first so .git/ also becomes .git.
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);

        StringBuilder sb = new StringBuilder(scheme).append("://");
        if (userInfo != null) sb.append(userInfo).append('@');
        sb.append(host);
        if (port > 0) sb.append(':').append(port);
        sb.append(path);
        return sb.toString();
    }

    /** SHA-256 of the canonical URL — the cache key for {@code $JK_CACHE_DIR/git/db/<hash>/}. */
    public static String canonicalHash(String input) {
        return Hashing.sha256Hex(canonicalize(input).getBytes(StandardCharsets.UTF_8));
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            case "ssh", "git" -> 22;
            default -> -1;
        };
    }

    /**
     * Convert SCP-style {@code user@host:path} to {@code ssh://user@host/path} so the rest of the
     * canonicalisation pipeline can use a real {@link URI}.
     */
    private static String normalizeScpForm(String input) {
        // SCP form has no `://` and the FIRST `:` is the host/path separator.
        if (input.contains("://")) return input;
        int at = input.indexOf('@');
        int colon = input.indexOf(':');
        if (at > 0 && colon > at) {
            return "ssh://" + input.substring(0, colon) + "/" + input.substring(colon + 1);
        }
        // No scheme and no user-host: assume https.
        return "https://" + input;
    }
}
