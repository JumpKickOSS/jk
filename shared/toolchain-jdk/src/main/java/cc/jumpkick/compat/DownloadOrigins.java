// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.RepositoryToml;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How a configured download origin is chosen and authenticated. An origin is, first to answer: its
 * environment variable, its {@code ~/.jk/config.toml} value ({@code ${VAR}} references expanded), a
 * Maven {@code settings.xml} {@code <mirror>} whose {@code mirrorOf} names it, then the public default.
 * Its credential comes from the repository credential chain under {@link DownloadOrigin#credentialId}.
 */
public final class DownloadOrigins {

    private DownloadOrigins() {}

    /** The origin for one source; {@code key} names the config value in an error about an unset variable. */
    public static DownloadOrigin resolve(
            Function<String, @Nullable String> env,
            MavenSettings settings,
            String envName,
            @Nullable String configured,
            String key,
            List<String> mirrorOf,
            String fallback) {
        DownloadOrigin mirror = configured(env, settings, envName, configured, key, mirrorOf);
        return mirror != null ? mirror : DownloadOrigin.ofHost(DownloadOrigin.directory(fallback));
    }

    /** As {@link #resolve} without the public default: null when nothing points the source elsewhere. */
    public static @Nullable DownloadOrigin configured(
            Function<String, @Nullable String> env,
            MavenSettings settings,
            String envName,
            @Nullable String configured,
            String key,
            List<String> mirrorOf) {
        String fromEnv = env.apply(envName);
        if (fromEnv != null && !fromEnv.isBlank())
            return DownloadOrigin.ofHost(DownloadOrigin.directory(fromEnv.trim()));
        if (configured != null) return DownloadOrigin.ofHost(DownloadOrigin.directory(expand(env, configured, key)));
        for (MavenSettings.Mirror mirror : settings.mirrors()) {
            for (String token : mirrorOf) {
                if (names(mirror.mirrorOf(), token)) {
                    return new DownloadOrigin(
                            DownloadOrigin.directory(mirror.url().toString()), mirror.id());
                }
            }
        }
        return null;
    }

    /** Whether a {@code mirrorOf} lists {@code token} by name. */
    public static boolean names(String mirrorOf, String token) {
        for (String t : mirrorOf.split(",")) {
            if (t.strip().equals(token)) return true;
        }
        return false;
    }

    /** {@code raw} with {@code ${VAR}} expanded from {@code env}; an unset variable names {@code key}. */
    public static String expand(Function<String, @Nullable String> env, String raw, String key) {
        return Objects.requireNonNull(RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new IllegalStateException(
                        "~/.jk/config.toml " + key + " references unset environment variable ${" + var + "}");
            }
            return value;
        }));
    }

    /** The credential {@code origin} authenticates with; anonymous when the chain holds none for it. */
    public static RepoCredential credential(DownloadOrigin origin) {
        return credential(origin, RepoCredentialResolver.withEnv(JkDirs::env));
    }

    public static RepoCredential credential(DownloadOrigin origin, RepoCredentialResolver resolver) {
        return resolver.resolve(origin.credentialId(), origin.url(), Optional.empty());
    }

    /**
     * The {@code Authorization} a request for a URI under one of {@code origins} carries, from that
     * origin's credential; nothing for any other URI.
     */
    public static Function<URI, Optional<String>> authorization(List<DownloadOrigin> origins) {
        return authorization(origins, RepoCredentialResolver.withEnv(JkDirs::env));
    }

    public static Function<URI, Optional<String>> authorization(
            List<DownloadOrigin> origins, RepoCredentialResolver resolver) {
        Map<String, Optional<String>> byOrigin = new LinkedHashMap<>();
        return uri -> {
            for (DownloadOrigin origin : origins) {
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
