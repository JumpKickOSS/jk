// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The digest a registry serves for a base image reference <em>right now</em>.
 *
 * <p>{@code [image] base} is normally a mutable tag, and a tag is a name, not a content identity:
 * {@code eclipse-temurin:25-jre} is republished regularly. An action key that carries the tag
 * string is therefore byte-identical across a republish, and the packaging cache serves a tarball
 * built on layers the registry no longer has. Resolving the tag to its digest first makes the base
 * an <em>input</em> — the thing the built image is actually a function of.
 *
 * <p>Only the manifest is fetched (a few KB), over jk's own {@link Http}, so registry traffic gets
 * the same retry, offline refusal and per-host cooldown as every other fetch jk makes. A reference
 * that is already digest-pinned needs no network at all, which is the reason to write one.
 *
 * <p><strong>Anonymous only.</strong> The bearer dance below asks for a pull token with no
 * credentials, which is what public registries hand out and what Jib itself does on the build leg.
 * A private base image answers 401 to both legs; resolution then fails, the caller declines to use
 * the packaging cache rather than key on a tag, and the build proceeds (and fails at the pull, for
 * the same missing-credentials reason). Credentials are a separate gap.
 */
final class BaseImageDigest {

    private BaseImageDigest() {}

    /**
     * Manifest media types, index first: a multi-arch tag must resolve to the index digest, not to
     * whichever platform manifest the registry would pick for the engine's own host.
     */
    private static final String ACCEPT = String.join(
            ",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    /** {@code key="value"} pairs of an HTTP challenge, quoted values only (all a registry sends). */
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("([a-zA-Z_]+)=\"([^\"]*)\"");

    /**
     * {@code reference} with its tag replaced by the digest the registry serves for it, or empty
     * when the registry cannot be asked. An already-pinned reference is returned unchanged.
     */
    static Optional<String> pin(String reference) {
        return pin(reference, new Http());
    }

    /** As {@link #pin(String)} with the transport supplied. */
    static Optional<String> pin(String reference, Http http) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        int at = reference.indexOf("@sha256:");
        if (at >= 0) return Optional.of(reference);
        Ref ref = parse(reference);
        return resolve(http, ref).map(digest -> untagged(reference) + "@" + digest);
    }

    /** The reference with its tag removed — everything the digest replaces. */
    private static String untagged(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(0, colon) : reference;
    }

    /** A reference split the way the registry API addresses it. */
    record Ref(String registry, String repository, String tag) {}

    /**
     * Split {@code registry/repository:tag}. A first segment is a registry only when it looks like a
     * host (a dot, a port, or {@code localhost}); otherwise it is a Docker Hub user, and a bare name
     * is a Docker Hub official image under {@code library/}. This is the same reading Jib and the
     * container runtimes apply.
     */
    static Ref parse(String reference) {
        String rest = reference;
        String registry = "docker.io";
        int slash = rest.indexOf('/');
        if (slash > 0) {
            String first = rest.substring(0, slash);
            if (first.indexOf('.') >= 0 || first.indexOf(':') >= 0 || first.equals("localhost")) {
                registry = first;
                rest = rest.substring(slash + 1);
            }
        }
        String tag = "latest";
        int colon = rest.lastIndexOf(':');
        if (colon > rest.lastIndexOf('/')) {
            tag = rest.substring(colon + 1);
            rest = rest.substring(0, colon);
        }
        if (registry.equals("docker.io") && rest.indexOf('/') < 0) rest = "library/" + rest;
        return new Ref(registry, rest, tag);
    }

    private static Optional<String> resolve(Http http, Ref ref) {
        URI manifest = URI.create(base(ref.registry()) + "/v2/" + ref.repository() + "/manifests/" + ref.tag());
        try {
            HttpResponse<byte[]> response = http.get(manifest, Map.of("Accept", ACCEPT));
            if (response.statusCode() == 401) {
                String token = bearerToken(
                        http, response.headers().firstValue("www-authenticate").orElse(""), ref);
                if (token == null) return Optional.empty();
                response = http.get(manifest, Map.of("Accept", ACCEPT, "Authorization", "Bearer " + token));
            }
            if (response.statusCode() != 200) return Optional.empty();
            // Docker-Content-Digest is the manifest digest the registry itself computed; hashing the
            // body is the same value by definition, and the fallback for a registry that omits it.
            Optional<String> served =
                    response.headers().firstValue("docker-content-digest").filter(d -> d.startsWith("sha256:"));
            return served.isPresent() ? served : Optional.of("sha256:" + Hashing.sha256Hex(response.body()));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * A pull token for the {@code Bearer} challenge the registry answered with. Docker Hub issues
     * one to anyone who asks, which is how an anonymous pull of a public image works at all.
     */
    private static String bearerToken(Http http, String challenge, Ref ref) throws InterruptedException {
        if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        Map<String, String> params = new HashMap<>();
        Matcher m = CHALLENGE_PARAM.matcher(challenge);
        while (m.find()) params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        String realm = params.get("realm");
        if (realm == null || realm.isBlank()) return null;
        StringBuilder url = new StringBuilder(realm).append(realm.indexOf('?') < 0 ? '?' : '&');
        url.append("scope=").append(encode(params.getOrDefault("scope", "repository:" + ref.repository() + ":pull")));
        if (params.containsKey("service")) {
            url.append("&service=").append(encode(params.get("service")));
        }
        try {
            HttpResponse<byte[]> response = http.get(URI.create(url.toString()));
            if (response.statusCode() != 200) return null;
            Object body = MiniJson.parse(new String(response.body(), StandardCharsets.UTF_8));
            if (!(body instanceof Map<?, ?> fields)) return null;
            Object token = fields.get("token");
            if (!(token instanceof String s) || s.isBlank()) token = fields.get("access_token");
            return token instanceof String s && !s.isBlank() ? s : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The API endpoint for a registry name. {@code docker.io} is a reference spelling, never a host
     * that serves {@code /v2/}. Loopback registries speak plain HTTP, the same default Docker,
     * podman and Jib all apply to {@code localhost}.
     */
    private static String base(String registry) {
        String host =
                registry.equals("docker.io") || registry.equals("index.docker.io") ? "registry-1.docker.io" : registry;
        int port = host.indexOf(':');
        String name = port < 0 ? host : host.substring(0, port);
        boolean plain = name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1");
        return (plain ? "http://" : "https://") + host;
    }
}
