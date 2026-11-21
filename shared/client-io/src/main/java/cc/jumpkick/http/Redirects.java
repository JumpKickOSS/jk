// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * jk's redirect policy, which {@link Http} follows itself rather than leaving to the client: up to
 * {@link #MAX_HOPS} hops, never from https to http, and a hop that leaves the request's origin
 * (scheme, host, port) is re-issued without {@code Authorization} or {@code Cookie}. A private
 * repository that hands a download to a CDN must not hand the CDN the repository token with it.
 */
final class Redirects {

    /** Most redirect hops followed for one request — the JDK client's own default. */
    static final int MAX_HOPS = 5;

    /** Headers that authenticate the caller to one origin and must not travel to another. */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie");

    private Redirects() {}

    /** True for the statuses followed: 301, 302, 303, 307 and 308. */
    static boolean followed(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * {@code Location} resolved against the request, or null when the response carries none usable:
     * absent, malformed, or naming a scheme other than http and https, which no request can be sent to.
     */
    static @Nullable URI target(URI from, HttpResponse<?> response) {
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty() || location.get().isBlank()) return null;
        URI target;
        try {
            target = from.resolve(location.get().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = target.getScheme();
        boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        return web && target.getHost() != null ? target : null;
    }

    /** A redirect may upgrade to https or stay put; it may never drop from https to http. */
    static boolean followable(URI from, URI to) {
        return !("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme()));
    }

    /**
     * Why the {@code status} redirect from {@code from} to {@code target}, after {@code hops} hops,
     * is not followed, as one line; null when it is.
     */
    static @Nullable String refusal(URI from, @Nullable URI target, int status, int hops) {
        if (target == null) return status + " redirect without a usable Location from " + SafeUri.forMessage(from);
        if (hops >= MAX_HOPS) return "too many redirects (" + MAX_HOPS + ") fetching " + SafeUri.forMessage(from);
        if (followable(from, target)) return null;
        return "refusing a redirect from " + SafeUri.forMessage(from) + " to " + SafeUri.forMessage(target)
                + ": https to http";
    }

    /**
     * {@code request} re-aimed at {@code target}: every header when the origin is unchanged,
     * everything but the credential headers when it is not, and never the proxy's credential, which
     * the caller decides afresh for the target. Method and body are the caller's to decide.
     */
    static HttpRequest.Builder reissue(HttpRequest request, URI target) {
        boolean sameOrigin = RepositorySpec.sameOrigin(request.uri(), target);
        return HttpRequest.newBuilder(request, (name, value) -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("proxy-authorization")) return false;
                    return sameOrigin || !CREDENTIAL_HEADERS.contains(lower);
                })
                .uri(target);
    }

    /**
     * The next hop's method: a 303, or a 301/302 answering a POST, turns into a GET without the body
     * — what browsers do and what the servers sending those statuses expect; 307 and 308 keep the
     * method and body.
     */
    static HttpRequest.Builder hop(HttpRequest.Builder next, HttpRequest request, int status) {
        if (status == 303 || ((status == 301 || status == 302) && "POST".equals(request.method()))) {
            next.GET();
        }
        return next;
    }

    /**
     * The caller's {@code headers} for a request {@code asked} of one origin and sent to {@code
     * routed}: all of them on the same origin, none of its credentials on another (the Central
     * mirror), as a redirect to another origin carries none.
     */
    static Map<String, String> forOrigin(URI asked, URI routed, Map<String, String> headers) {
        if (RepositorySpec.sameOrigin(asked, routed)) return headers;
        Map<String, String> kept = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) kept.put(name, value);
        });
        return kept;
    }
}
