// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.RepositoryToml;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.ForgeKind;
import cc.jumpkick.forge.ResolvedToken;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;

/**
 * Resolves {@link RepoCredential}: inline → env → {@code jk repo login} store → Maven settings →
 * forge-token bridge last. Collaborators injected for tests.
 */
@RequiredArgsConstructor
public final class RepoCredentialResolver {

    private final Function<String, String> env;
    private final MavenSettings settings;
    private final RepoCredentialStore store;
    private final ForgeAuth forgeAuth;
    private final ForgeIdentity identity;

    public RepoCredentialResolver() {
        this(System::getenv, MavenSettings.load(), new RepoCredentialStore(), new ForgeAuth(), ForgeIdentity.real());
    }

    /** The default resolver, but reading environment variables through {@code env}. */
    public static RepoCredentialResolver withEnv(Function<String, String> env) {
        return new RepoCredentialResolver(
                env, MavenSettings.load(), new RepoCredentialStore(), new ForgeAuth(), ForgeIdentity.real());
    }

    /**
     * Expand {@code ${VAR}} in an inline credential.
     *
     * <p>Strict on purpose: an unset variable is an error rather than an empty string, so a typo
     * fails loudly instead of silently authenticating anonymously against a private repository. The
     * error now surfaces when the repository is <em>used</em> rather than when the manifest is
     * parsed — which also means a manifest may reference a private mirror whose credentials this
     * machine does not have, as long as nothing asks for it.
     */
    private RepoCredential expand(String repoId, RepoCredential credential) {
        return switch (credential) {
            case RepoCredential.Basic b ->
                new RepoCredential.Basic(interp(repoId, b.username()), interp(repoId, b.password()));
            case RepoCredential.Bearer t -> new RepoCredential.Bearer(interp(repoId, t.token()));
            default -> credential; // anonymous / anything without embedded text
        };
    }

    private String interp(String repoId, String raw) {
        return RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new IllegalStateException("repository "
                        + (repoId == null || repoId.isBlank() ? "credential" : repoId)
                        + " references unset environment variable ${" + var + "}");
            }
            return value;
        });
    }

    /**
     * Resolve credentials for the repository named {@code repoId} at {@code url}. {@code inline} is
     * the credential declared inline in {@code jk.toml} (empty when none / not yet parsed). Never
     * returns null; falls back to {@link RepoCredential#ANONYMOUS}.
     *
     * <p>Whatever it returns is also filed with {@link ResolvedSecrets}. This is the one funnel all
     * five sources pass through and the only place in jk that holds the value before anything can
     * print it, so it is where the redactor gets told. Nothing here inspects a name to decide
     * secrecy — the value is secret because this method just resolved it as a credential.
     */
    public RepoCredential resolve(String repoId, URI url, Optional<RepoCredential> inline) {
        RepoCredential credential = select(repoId, url, inline);
        ResolvedSecrets.record(secretOf(credential));
        return credential;
    }

    /**
     * The half of a credential that is a secret: a bearer token, or Basic's password — which is
     * where a token lands when the username is a login (the forge bridge does exactly that). The
     * username is not filed: it is a person's or a repository's name, and masking it would blank
     * ordinary build output.
     */
    private static String secretOf(RepoCredential credential) {
        return switch (credential) {
            case RepoCredential.Bearer b -> b.token();
            case RepoCredential.Basic b -> b.password();
            default -> null; // anonymous — nothing to mask
        };
    }

    private RepoCredential select(String repoId, URI url, Optional<RepoCredential> inline) {
        // 1. inline jk.toml — expanding ${VAR} here rather than at parse time, because this
        // is where the request's environment is in scope. Doing it during the parse made the parse
        // environment-dependent, so a memoized result served the first caller's values to everyone,
        // and inside the engine it read the daemon's environment instead of the caller's.
        Optional<RepoCredential> fromInline = inline.map(c -> expand(repoId, c)).filter(c -> !c.isAnonymous());
        if (fromInline.isPresent()) return fromInline.get();

        // Sources 2–4 are keyed by repo id; skip them when there's no declared
        // name (e.g. `jk publish --repo-url <url>` with no matching repo).
        boolean named = repoId != null && !repoId.isBlank();
        if (named) {
            // 2. environment variables
            Optional<RepoCredential> fromEnv = fromEnv(repoId);
            if (fromEnv.isPresent()) return fromEnv.get();

            // 3. jk repo credential store
            Optional<RepoCredential> fromStore = store.read(repoId);
            if (fromStore.isPresent()) return fromStore.get();

            // 4. ~/.m2/settings.xml
            Optional<RepoCredential> fromSettings = settings.server(repoId)
                    .map(s -> new RepoCredential.Basic(
                            s.username() == null ? "" : s.username(), s.password() == null ? "" : s.password()));
            if (fromSettings.isPresent()) return fromSettings.get();
        }

        // 5. forge-token bridge (package registries) — keyed by host, always tried
        Optional<RepoCredential> fromForge = forgeBridge(url);
        if (fromForge.isPresent()) return fromForge.get();

        return RepoCredential.ANONYMOUS;
    }

    /**
     * The environment-variable prefix this resolver reads for {@code repoId}, so a diagnostic can
     * spell {@code JK_REPO_<ID>_TOKEN} the way the lookup actually spells it rather than guessing
     * at the sanitization.
     */
    public static String envVarPrefix(String repoId) {
        return "JK_REPO_" + sanitizeEnv(repoId) + "_";
    }

    private Optional<RepoCredential> fromEnv(String repoId) {
        String prefix = envVarPrefix(repoId);
        String token = nonBlank(env.apply(prefix + "TOKEN"));
        if (token != null) return Optional.of(new RepoCredential.Bearer(token));
        String username = nonBlank(env.apply(prefix + "USERNAME"));
        if (username != null) {
            String password = env.apply(prefix + "PASSWORD");
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    /**
     * Reuse a {@code jk auth login} token for forge package hosts (GitHub Packages → Basic preferred;
     * GitLab/Gitea → Bearer).
     */
    private Optional<RepoCredential> forgeBridge(URI url) {
        if (url == null || url.getHost() == null) return Optional.empty();
        String host = url.getHost().toLowerCase(Locale.ROOT);

        ForgeKind kind;
        String forgeHost;
        if (host.equals("maven.pkg.github.com")) {
            kind = ForgeKind.GITHUB;
            forgeHost = "github.com";
        } else {
            Optional<ForgeKind> inferred = ForgeKind.inferFromHost(host);
            if (inferred.isEmpty()) return Optional.empty();
            kind = inferred.get();
            forgeHost = host;
        }

        Optional<ResolvedToken> token = forgeAuth.resolveSilently(kind, forgeHost);
        if (token.isEmpty()) return Optional.empty();
        String tok = token.get().value();

        if (kind == ForgeKind.GITHUB) {
            URI userEndpoint = URI.create(kind.apiBase(forgeHost).toString() + "/user");
            return Optional.of(identity.login(userEndpoint, "login", tok)
                    .map(login -> (RepoCredential) new RepoCredential.Basic(login, tok))
                    .orElseGet(() -> new RepoCredential.Bearer(tok)));
        }
        return Optional.of(new RepoCredential.Bearer(tok));
    }

    private static String sanitizeEnv(String repoId) {
        StringBuilder sb = new StringBuilder(repoId.length());
        for (char c : repoId.toUpperCase(Locale.ROOT).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
