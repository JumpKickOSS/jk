// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Fetch remote images for markdown Preview (GitHub README badges / {@code user-attachments}
 * banners). Host allow-list + no private targets — this is not a general open proxy.
 */
final class PreviewImageFetch {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final Set<String> EXACT_HOSTS = Set.of(
            "img.shields.io",
            "badge.fury.io",
            "cdn.jsdelivr.net",
            "github.com",
            "www.github.com",
            "openjdk.org",
            "www.openjdk.org",
            "graalvm.org",
            "www.graalvm.org");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    sealed interface Result {
        record Ok(byte[] bytes, String contentType) implements Result {}

        record BadRequest(String error) implements Result {}

        record Forbidden(String error) implements Result {}

        record Upstream(int status, String error) implements Result {}

        record Failed(String error) implements Result {}
    }

    private PreviewImageFetch() {}

    static Result fetch(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return new Result.BadRequest("missing \"url\"");
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            return new Result.BadRequest("illegal url");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return new Result.BadRequest("url must be absolute http(s)");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return new Result.BadRequest("only http(s) urls allowed");
        }
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!isAllowedImageHost(uri.getHost())) {
                    return new Result.Forbidden("host not allowed for preview images: " + uri.getHost());
                }
                if (isBlockedAddress(uri.getHost())) {
                    return new Result.Forbidden("host resolves to a blocked address");
                }
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        // Browser-like UA: some CDNs (shields, GH assets) soft-block unknown agents.
                        .header("User-Agent", "Mozilla/5.0 (compatible; JumpKick-preview/1; +https://jumpkick.cc)")
                        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int code = resp.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc == null || loc.isBlank()) {
                        return new Result.Upstream(code, "redirect without Location");
                    }
                    URI next = uri.resolve(loc);
                    if (next.getScheme() == null || next.getHost() == null) {
                        return new Result.BadRequest("invalid redirect");
                    }
                    String ns = next.getScheme().toLowerCase(Locale.ROOT);
                    if (!ns.equals("https") && !ns.equals("http")) {
                        return new Result.BadRequest("redirect to non-http(s)");
                    }
                    uri = next;
                    continue;
                }
                if (code != 200) {
                    return new Result.Upstream(code, "upstream HTTP " + code);
                }
                byte[] body = resp.body();
                if (body == null) body = new byte[0];
                if (body.length > MAX_BYTES) {
                    return new Result.Upstream(413, "image too large");
                }
                String ct = resp.headers()
                        .firstValue("Content-Type")
                        .orElse("application/octet-stream")
                        .split(";", 2)[0]
                        .trim();
                if (ct.isEmpty()) ct = "application/octet-stream";
                // Soft check: prefer image/* but allow octet-stream (GitHub assets often omit type).
                if (!ct.startsWith("image/") && !ct.equals("application/octet-stream")) {
                    return new Result.Upstream(415, "not an image content-type: " + ct);
                }
                return new Result.Ok(body, ct.startsWith("image/") ? ct : guessImageType(body));
            }
            return new Result.Upstream(310, "too many redirects");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("interrupted");
        } catch (IOException e) {
            return new Result.Failed(e.getMessage() == null ? "fetch failed" : e.getMessage());
        } catch (RuntimeException e) {
            return new Result.Failed(e.getMessage() == null ? "fetch failed" : e.getMessage());
        }
    }

    static boolean isAllowedImageHost(@Nullable String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (EXACT_HOSTS.contains(h)) return true;
        // avatars / user-attachments / camo / raw content
        if (h.endsWith(".githubusercontent.com")) return true;
        if (h.endsWith(".githubassets.com")) return true;
        return false;
    }

    /** Reject loopback / link-local / RFC1918 when the host name resolves (SSRF floor). */
    static boolean isBlockedAddress(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            // Unresolvable host — let the HTTP client fail the fetch rather than soft-allow.
            return false;
        }
    }

    private static String guessImageType(byte[] body) {
        if (body.length >= 3 && body[0] == (byte) 0xff && body[1] == (byte) 0xd8 && body[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        if (body.length >= 8 && body[0] == (byte) 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') {
            return "image/png";
        }
        if (body.length >= 6 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F') {
            return "image/gif";
        }
        if (body.length >= 12
                && body[0] == 'R'
                && body[1] == 'I'
                && body[2] == 'F'
                && body[3] == 'F'
                && body[8] == 'W'
                && body[9] == 'E'
                && body[10] == 'B'
                && body[11] == 'P') {
            return "image/webp";
        }
        // SVG often served without sniffable header; leave generic.
        return "application/octet-stream";
    }
}
