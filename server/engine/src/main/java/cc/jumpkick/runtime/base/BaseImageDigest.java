// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.repo.AuthHeaders;
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
import org.jspecify.annotations.Nullable;

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
 * <p>A 401 is answered with the same credential the Jib worker gets for its pull leg
 * ({@link ImageCredentials#resolve}): a Basic credential authenticates the bearer-realm token
 * request (the Docker Hub / GHCR / registry:2 shape) or, when the challenge is {@code Basic} or
 * absent, the manifest request itself (the Artifactory / Nexus shape); a Bearer credential goes
 * straight onto the manifest retry. The credential is sent only to the challenge's realm or to the
 * registry host — never anywhere else. Anonymous keeps the plain bearer dance a public registry
 * hands out. A credential the registry rejects leaves the reference unpinned, the caller declines
 * to use the packaging cache rather than key on a tag, and the build proceeds to Jib's own error.
 */
public final class BaseImageDigest {

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

    /** True when {@code reference} already names its digest, so {@link #pin} needs no registry. */
    public static boolean pinned(@Nullable String reference) {
        return reference != null && reference.contains("@sha256:");
    }

    /** As {@link #pin(String, Http, RepoCredential)} for a registry needing no credential. */
    public static Optional<String> pin(String reference, Http http) {
        return pin(reference, http, RepoCredential.ANONYMOUS);
    }

    /**
     * {@code reference} with its tag replaced by the digest the registry serves for it, or empty
     * when the registry cannot be asked. An already-pinned reference is returned unchanged.
     */
    public static Optional<String> pin(@Nullable String reference, Http http, RepoCredential cred) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        if (pinned(reference)) return Optional.of(reference);
        Ref ref = parse(reference);
        return resolve(http, ref, cred).map(digest -> untagged(reference) + "@" + digest);
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

    private static Optional<String> resolve(Http http, Ref ref, RepoCredential cred) {
        URI manifest = URI.create(base(ref.registry()) + "/v2/" + ref.repository() + "/manifests/" + ref.tag());
        try {
            HttpResponse<byte[]> response = http.get(manifest, Map.of("Accept", ACCEPT));
            if (response.statusCode() == 401) {
                Map<String, String> auth = retryAuth(
                        http, response.headers().firstValue("www-authenticate").orElse(""), ref, cred);
                if (auth.isEmpty()) return Optional.empty();
                Map<String, String> headers = new HashMap<>(auth);
                headers.put("Accept", ACCEPT);
                response = http.get(manifest, headers);
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
     * The {@code Authorization} header for the manifest retry after a 401, or empty when the
     * challenge cannot be answered with {@code cred}. The credential reaches exactly two places:
     * the challenge's own realm (Basic on the token request) and the registry host (the returned
     * header) — a challenge cannot redirect it anywhere else.
     */
    private static Map<String, String> retryAuth(Http http, String challenge, Ref ref, RepoCredential cred)
            throws InterruptedException {
        // A bearer credential is already the pull token — the realm has nothing to add.
        if (cred instanceof RepoCredential.Bearer) return AuthHeaders.of(cred);
        if (challenge.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = bearerToken(http, challenge, ref, AuthHeaders.of(cred));
            return token == null ? Map.of() : Map.of("Authorization", "Bearer " + token);
        }
        // Basic challenge, or none: the manifest endpoint itself takes Basic (Artifactory/Nexus).
        return cred instanceof RepoCredential.Basic ? AuthHeaders.of(cred) : Map.of();
    }

    /**
     * A pull token for the {@code Bearer} challenge the registry answered with. Docker Hub issues
     * one to anyone who asks, which is how an anonymous pull of a public image works at all; a
     * private repository issues one only to a realm request carrying {@code realmAuth}.
     */
    private static @Nullable String bearerToken(Http http, String challenge, Ref ref, Map<String, String> realmAuth)
            throws InterruptedException {
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
            HttpResponse<byte[]> response = http.get(URI.create(url.toString()), realmAuth);
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
