// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.RepositoryToml;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where Node and its packages come from, and what a request there authenticates with.
 *
 * <p>Each origin is, first to answer: its environment variable ({@value #DIST_MIRROR_ENV}, {@value
 * #REGISTRY_ENV}), {@code [node] dist-mirror} / {@code registry} in {@code ~/.jk/config.toml}, a
 * Maven {@code settings.xml} {@code <mirror>} whose {@code mirrorOf} names {@code nodejs} / {@code
 * npm}, then {@value #NODEJS_DIST} / {@value #NPM_REGISTRY}. {@code [node.scopes]} maps an
 * {@code @scope} to its own registry. Config values may reference {@code ${VAR}}.
 *
 * <p>An origin's credential comes from the repository credential chain under the id {@code
 * host[:port]} ({@code JK_REPO_<HOST>_TOKEN}, {@code jk repo login <host>}, a settings.xml {@code
 * <server>} of that id), or the mirror's id for a settings.xml mirror.
 */
public final class NodeSources {

    public static final String NODEJS_DIST = "https://nodejs.org/dist/";
    public static final String NPM_REGISTRY = "https://registry.npmjs.org/";
    public static final String DIST_MIRROR_ENV = "JK_NODE_DIST_MIRROR";
    public static final String REGISTRY_ENV = "JK_NODE_REGISTRY";

    /** A settings.xml {@code mirrorOf} token that names the Node distribution. */
    static final String MIRROR_OF_DIST = "nodejs";

    /** A settings.xml {@code mirrorOf} token that names the npm registry. */
    static final String MIRROR_OF_REGISTRY = "npm";

    private NodeSources() {}

    /** One origin: its URL, always ending in {@code /}, and the id its credential is looked up under. */
    public record Origin(URI url, String credentialId) {}

    /** What the origins are read from; {@link #current} is the machine's. */
    record Inputs(Function<String, @Nullable String> env, GlobalConfig.NodeSources config, MavenSettings settings) {

        static Inputs current() {
            return new Inputs(JkDirs::env, GlobalConfig.nodeSources(), MavenSettings.current());
        }
    }

    public static URI distBase() {
        return dist().url();
    }

    public static URI registry() {
        return npmRegistry().url();
    }

    public static Origin dist() {
        return dist(Inputs.current());
    }

    public static Origin npmRegistry() {
        return npmRegistry(Inputs.current());
    }

    /** {@code [node.scopes]}: each {@code @scope} and its registry. */
    public static Map<String, Origin> scopes() {
        return scopes(Inputs.current());
    }

    static Origin dist(Inputs in) {
        return origin(in, DIST_MIRROR_ENV, in.config().distMirror(), "[node] dist-mirror", MIRROR_OF_DIST, NODEJS_DIST);
    }

    static Origin npmRegistry(Inputs in) {
        return origin(in, REGISTRY_ENV, in.config().registry(), "[node] registry", MIRROR_OF_REGISTRY, NPM_REGISTRY);
    }

    static Map<String, Origin> scopes(Inputs in) {
        Map<String, Origin> out = new LinkedHashMap<>();
        in.config().scopes().forEach((scope, url) -> {
            URI uri = directory(expand(in, url, "[node.scopes] \"" + scope + "\""));
            out.put(scope, new Origin(uri, hostId(uri)));
        });
        return out;
    }

    private static Origin origin(
            Inputs in, String envName, @Nullable String configured, String key, String mirrorOf, String fallback) {
        String fromEnv = in.env().apply(envName);
        if (fromEnv != null && !fromEnv.isBlank()) return hostOrigin(directory(fromEnv.trim()));
        if (configured != null) return hostOrigin(directory(expand(in, configured, key)));
        for (MavenSettings.Mirror mirror : in.settings().mirrors()) {
            if (names(mirror.mirrorOf(), mirrorOf))
                return new Origin(directory(mirror.url().toString()), mirror.id());
        }
        return hostOrigin(directory(fallback));
    }

    /** Whether a {@code mirrorOf} lists {@code token} by name; a wildcard is a Maven mirror, never npm's. */
    static boolean names(String mirrorOf, String token) {
        for (String t : mirrorOf.split(",")) {
            if (t.strip().equals(token)) return true;
        }
        return false;
    }

    private static Origin hostOrigin(URI url) {
        return new Origin(url, hostId(url));
    }

    /** {@code host[:port]}: the id a host-addressed origin's credential is stored under. */
    static String hostId(URI url) {
        return url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
    }

    private static String expand(Inputs in, String raw, String key) {
        return Objects.requireNonNull(RepositoryToml.interpolate(raw, var -> {
            String value = in.env().apply(var);
            if (value == null) {
                throw new IllegalStateException(
                        "~/.jk/config.toml " + key + " references unset environment variable ${" + var + "}");
            }
            return value;
        }));
    }

    /** {@code url} with a trailing slash, so relative paths resolve beneath it. */
    static URI directory(String url) {
        return URI.create(url.endsWith("/") ? url : url + "/");
    }

    /** Every origin a node download or install may reach: the distribution, the registry and each scope's. */
    public static List<Origin> origins() {
        Inputs in = Inputs.current();
        List<Origin> out = new ArrayList<>();
        out.add(dist(in));
        out.add(npmRegistry(in));
        out.addAll(scopes(in).values());
        return out;
    }

    /** A client for node downloads: each request to a node origin carries that origin's credential. */
    public static Http http() {
        return new Http().withAuthorization(authorization(origins()));
    }

    /** The credential {@code origin} authenticates with; anonymous when the chain holds none for it. */
    public static RepoCredential credential(Origin origin) {
        return credential(origin, RepoCredentialResolver.withEnv(JkDirs::env));
    }

    static RepoCredential credential(Origin origin, RepoCredentialResolver resolver) {
        return resolver.resolve(origin.credentialId(), origin.url(), Optional.empty());
    }

    /**
     * The {@code Authorization} a request for a URI under one of {@code origins} carries, from that
     * origin's credential; nothing for any other URI.
     */
    public static Function<URI, Optional<String>> authorization(List<Origin> origins) {
        return authorization(origins, RepoCredentialResolver.withEnv(JkDirs::env));
    }

    static Function<URI, Optional<String>> authorization(List<Origin> origins, RepoCredentialResolver resolver) {
        Map<String, Optional<String>> byOrigin = new LinkedHashMap<>();
        return uri -> {
            for (Origin origin : origins) {
                if (!RepositorySpec.sameOrigin(origin.url(), uri)
                        || !uri.getPath().startsWith(origin.url().getPath())) continue;
                return byOrigin.computeIfAbsent(origin.url().toString(), k -> header(credential(origin, resolver)));
            }
            return Optional.empty();
        };
    }

    /** The {@code Authorization} value of {@code credential}, or empty when it is anonymous. */
    public static Optional<String> header(RepoCredential credential) {
        return switch (credential) {
            case RepoCredential.Bearer b -> Optional.of("Bearer " + b.token());
            case RepoCredential.Basic b ->
                Optional.of("Basic "
                        + Base64.getEncoder()
                                .encodeToString((b.username() + ":" + b.password()).getBytes(StandardCharsets.UTF_8)));
            default -> Optional.empty();
        };
    }
}
