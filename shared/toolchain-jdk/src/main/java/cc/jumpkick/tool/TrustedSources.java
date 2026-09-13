// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Client-side URL-prefix allowlist for {@code jk tool run|install} ({@code trusted-sources.toml}).
 * Matches the user-typed URL (before rewrite).
 *
 * <p>A prefix is a URL with a scheme and a host, or a {@code file:} URL naming a local path — the
 * one scheme whose authority is legitimately empty, so a local git repository or a checked-out
 * script directory can be trusted like a remote one; an empty path means {@code /}. Both sides
 * are compared in canonical form — scheme and host lowercased, a default port dropped, the path
 * as the server sees it (case-sensitive, still percent-encoded) — and a match stops at a path
 * segment boundary: trusting {@code https://github.com/acme} covers {@code
 * https://github.com/acme/…} but not {@code https://github.com/acme-evil/…}, and trusting {@code
 * https://github.com} covers no other host. A URL whose path carries a {@code .} or {@code ..}
 * segment, an empty segment, or a percent-encoded slash or dot is never trusted: what the origin
 * resolves it to is not what the prefix names. Credentials in the authority are refused for the
 * same reason — {@code https://github.com@evil.example/} reads as GitHub and is not.
 */
public final class TrustedSources {

    private static final String FILE_NAME = "trusted-sources.toml";
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern ENCODED_SLASH_OR_DOT = Pattern.compile("%2[EeFf]");

    private final Path file;
    private final List<String> prefixes;

    private TrustedSources(Path file, List<String> prefixes) {
        this.file = file;
        this.prefixes = prefixes;
    }

    public static TrustedSources load(Path stateDir) throws IOException {
        Path file = stateDir.resolve(FILE_NAME);
        List<String> prefixes = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            // Line reader (no TOML parser): jk-managed `sources = [` one quoted prefix per line `]`.
            boolean inSources = false;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!inSources) {
                    if (!line.startsWith("sources")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    line = line.substring(eq + 1).strip();
                    if (line.startsWith("[")) line = line.substring(1);
                    inSources = true;
                }
                // The array closes at a `]` outside quotes (IPv6 prefixes carry `]` inside).
                int close = line.indexOf(']', line.lastIndexOf('"') + 1);
                boolean closes = close >= 0;
                if (closes) line = line.substring(0, close);
                var m = QUOTED.matcher(line);
                while (m.find()) {
                    prefixes.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
                }
                if (closes) break;
            }
        }
        return new TrustedSources(file, prefixes);
    }

    public List<String> list() {
        return List.copyOf(prefixes);
    }

    /** True when {@code url} falls under any trusted prefix. */
    public boolean isTrusted(String url) {
        String candidate = canonical(url);
        if (candidate == null) return false;
        for (String prefix : prefixes) {
            String p = canonical(prefix);
            if (p != null && covers(p, candidate)) return true;
        }
        return false;
    }

    /**
     * Add {@code prefix} in its canonical form; returns false when it was already present.
     * Persists on change.
     *
     * @throws IllegalArgumentException when {@code prefix} is not a usable URL prefix; the
     *     message says why
     */
    public boolean add(String prefix) throws IOException {
        String p = canonicalPrefix(prefix);
        if (prefixes.stream().anyMatch(e -> p.equals(canonical(e)))) return false;
        prefixes.add(p);
        save();
        return true;
    }

    /** Remove {@code prefix}; returns false when it wasn't present. Persists on change. */
    public boolean remove(String prefix) throws IOException {
        String p = canonical(prefix);
        String raw = prefix.trim();
        boolean removed = prefixes.removeIf(e -> e.trim().equals(raw) || (p != null && p.equals(canonical(e))));
        if (removed) save();
        return removed;
    }

    /**
     * The form a prefix is stored and compared in — see the class comment.
     *
     * @throws IllegalArgumentException when {@code prefix} is not a usable URL prefix; the
     *     message says why
     */
    public static String canonicalPrefix(String prefix) {
        String u = prefix.trim();
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a URL: " + u);
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("not a URL: " + u);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("not a URL with a scheme and a host: " + u);
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        boolean local = lowerScheme.equals("file");
        // What sits between `scheme://` and the path: a lowercased host (and non-default port)
        // for a remote URL, nothing for a local one.
        String authority;
        if (local) {
            String rawAuthority = uri.getRawAuthority();
            if (rawAuthority != null && !rawAuthority.isEmpty()) {
                throw new IllegalArgumentException("a file: URL with a host names no local path: " + u);
            }
            authority = "";
        } else {
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("not a URL with a scheme and a host: " + u);
            }
            int port = uri.getPort();
            if (port == defaultPort(lowerScheme)) port = -1;
            authority = host.toLowerCase(Locale.ROOT) + (port >= 0 ? ":" + port : "");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("a URL with credentials in it is not a source: " + u);
        }
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (ENCODED_SLASH_OR_DOT.matcher(rawPath).find()) {
            throw new IllegalArgumentException("the path percent-encodes a slash or a dot: " + u);
        }
        for (String segment : rawPath.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("the path has a . or .. segment: " + u);
            }
        }
        if (rawPath.contains("//")) {
            throw new IllegalArgumentException("the path has an empty segment: " + u);
        }
        return lowerScheme + "://" + authority + rawPath;
    }

    /** {@link #canonicalPrefix}, or {@code null} for a value that is not a usable URL. */
    static @Nullable String canonical(String url) {
        try {
            return canonicalPrefix(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether canonical {@code url} falls under canonical {@code prefix}: a prefix ending in
     * {@code /} covers everything below it, any other prefix covers itself and the segments
     * below it — never a sibling that merely starts with the same characters.
     */
    static boolean covers(String prefix, String url) {
        if (prefix.endsWith("/")) return url.startsWith(prefix);
        return url.equals(prefix) || url.startsWith(prefix + "/");
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    /**
     * The prefix to suggest for {@code url} in prompts and errors: scheme, host, and the first
     * path segment — {@code https://github.com/acme/widgets/blob/…} → {@code
     * https://github.com/acme/}. A {@code file:} URL has no host to anchor on, so its suggestion
     * is the directory holding the target: {@code file:///work/tools/repo} → {@code
     * file:///work/tools/}.
     */
    public static String suggestedPrefix(String url) {
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                int lastSlash = trimmed.lastIndexOf('/');
                return "file://" + (lastSlash < 0 ? "/" : trimmed.substring(0, lastSlash + 1));
            }
            String[] segments = path.split("/");
            String first = segments.length > 1 ? segments[1] : "";
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port + "/"
                    + (first.isEmpty() ? "" : first + "/");
        } catch (RuntimeException e) {
            return url;
        }
    }

    /**
     * Parse JBang's {@code ~/.jbang/trusted-sources.json} — a JSON string array that may carry
     * {@code //} comment lines — into importable prefixes.
     */
    public static List<String> parseJBang(String json) {
        List<String> out = new ArrayList<>();
        for (String line : json.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("//")) continue;
            Matcher m = QUOTED.matcher(stripped);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# URL prefixes allowed to supply runnable code for `jk tool run|install`.\n");
        sb.append("# Managed by `jk trust add|remove|import`; hand edits are fine.\n");
        sb.append("sources = [\n");
        for (String p : prefixes) {
            sb.append("  ").append(MinimalToml.quote(p)).append(",\n");
        }
        sb.append("]\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
