// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.RepositoryToml;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.ForgeKind;
import cc.jumpkick.forge.ResolvedToken;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@link RepoCredential}: inline → env → {@code jk repo login} store → Maven settings →
 * forge-token bridge last. Collaborators injected for tests.
 *
 * <p>The three middle sources are keyed by repository <em>id</em>, and the id is not a
 * destination: a project's {@code jk.toml} decides which URL {@code ossrh} points at, so a cloned
 * project could declare {@code ossrh = "https://attacker.example/"} and collect whatever this
 * machine holds under that name. A name-keyed credential therefore reaches a repository only when
 * something the project does not control binds the name to the repository's origin (scheme, host,
 * port): the origin {@code jk repo login} recorded, a {@code [repositories.<id>]} declaration in
 * the user's own {@code ~/.jk/config.toml}, a {@code JK_REPO_<ID>_HOST} variable from the caller's
 * shell, or an id that is itself the host. Anything else is refused with a warning that names the
 * repository, the origin and the source that was not sent.
 */
public final class RepoCredentialResolver {

    private final Function<String, @Nullable String> env;
    private final MavenSettings settings;
    private final RepoCredentialStore store;
    private final ForgeAuth forgeAuth;
    private final ForgeIdentity identity;

    /**
     * Where a {@code JK_REPO_<ID>_HOST} binding is read. Distinct from {@link #env}, which layers
     * the project's {@code .env} under the shell: a project that can redirect a repository id must
     * not also be able to supply the binding that authorises the redirect.
     */
    private final Function<String, @Nullable String> hostBindings;

    /** The repositories the user's own config declares — a declaration the project cannot edit. */
    private final Supplier<List<RepositorySpec>> userRepositories;

    public RepoCredentialResolver() {
        this(System::getenv);
    }

    /** Every collaborator explicit — tests. */
    public RepoCredentialResolver(
            Function<String, @Nullable String> env,
            MavenSettings settings,
            RepoCredentialStore store,
            ForgeAuth forgeAuth,
            ForgeIdentity identity,
            Function<String, @Nullable String> hostBindings,
            Supplier<List<RepositorySpec>> userRepositories) {
        this.env = Objects.requireNonNull(env, "env");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
        this.forgeAuth = Objects.requireNonNull(forgeAuth, "forgeAuth");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.hostBindings = Objects.requireNonNull(hostBindings, "hostBindings");
        this.userRepositories = Objects.requireNonNull(userRepositories, "userRepositories");
    }

    private RepoCredentialResolver(Function<String, @Nullable String> env) {
        // Host bindings come from the ambient environment — the request's shell, then the engine's
        // own — read at lookup time so a resolver built before the session is installed still
        // answers for the session that asks.
        this(
                env,
                MavenSettings.load(),
                new RepoCredentialStore(),
                new ForgeAuth(),
                ForgeIdentity.real(),
                name -> BuildEnv.ambient().apply(name),
                GlobalConfig::repositories);
    }

    /** The default resolver, but reading environment variables through {@code env}. */
    public static RepoCredentialResolver withEnv(Function<String, @Nullable String> env) {
        return new RepoCredentialResolver(env);
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
    private RepoCredential expand(@Nullable String repoId, RepoCredential credential) {
        return switch (credential) {
            case RepoCredential.Basic b ->
                new RepoCredential.Basic(interp(repoId, b.username()), interp(repoId, b.password()));
            case RepoCredential.Bearer t -> new RepoCredential.Bearer(interp(repoId, t.token()));
            default -> credential; // anonymous / anything without embedded text
        };
    }

    private String interp(@Nullable String repoId, String raw) {
        return Objects.requireNonNull(RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new IllegalStateException("repository "
                        + (repoId == null || repoId.isBlank() ? "credential" : repoId)
                        + " references unset environment variable ${" + var + "}");
            }
            return value;
        }));
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
    public RepoCredential resolve(@Nullable String repoId, @Nullable URI url, Optional<RepoCredential> inline) {
        RepoCredential credential = select(repoId, url, inline);
        ResolvedSecrets.record(credential.secret());
        return credential;
    }

    private RepoCredential select(@Nullable String repoId, @Nullable URI url, Optional<RepoCredential> inline) {
        // 1. inline jk.toml — expanding ${VAR} here rather than at parse time, because this
        // is where the request's environment is in scope. Doing it during the parse made the parse
        // environment-dependent, so a memoized result served the first caller's values to everyone,
        // and inside the engine it read the daemon's environment instead of the caller's.
        Optional<RepoCredential> fromInline = inline.map(c -> expand(repoId, c)).filter(c -> !c.isAnonymous());
        if (fromInline.isPresent()) return fromInline.get();

        // Sources 2–4 are keyed by repo id; skip them when there's no declared
        // name (e.g. `jk publish --repo-url <url>` with no matching repo). Each is sent only when
        // the name is bound to the URL's origin; a refused source falls through to the next.
        if (repoId != null && !repoId.isBlank()) {
            Binding binding = bindingOf(repoId, url);

            // 2. environment variables
            Optional<RepoCredential> fromEnv = fromEnv(repoId);
            if (fromEnv.isPresent()) {
                if (binding.bound()) return fromEnv.get();
                refuse(repoId, url, envVarPrefix(repoId) + "TOKEN / " + envVarPrefix(repoId) + "USERNAME", binding);
            }

            // 3. jk repo credential store — bound by the origin the login recorded when it has one
            Optional<RepoCredentialStore.Entry> stored = store.read(repoId);
            if (stored.isPresent()) {
                RepoCredentialStore.Entry entry = stored.get();
                URI origin = entry.origin();
                if (origin != null) {
                    if (url != null && Http.sameOrigin(origin, url)) return entry.credential();
                    refuse(
                            repoId,
                            url,
                            "the `jk repo login " + repoId + "` store",
                            Binding.mismatch("`jk repo login " + repoId + "` stored it for " + origin + ", not "
                                    + originText(url) + "; run `jk repo login " + repoId + " --url "
                                    + SafeUri.forMessage(url) + "` if the repository has moved there"));
                } else if (binding.bound()) {
                    return entry.credential();
                } else {
                    refuse(repoId, url, "the `jk repo login " + repoId + "` store", binding);
                }
            }

            // 4. ~/.m2/settings.xml
            Optional<RepoCredential> fromSettings = settings.server(repoId)
                    .map(s -> new RepoCredential.Basic(
                            s.username() == null ? "" : s.username(), s.password() == null ? "" : s.password()));
            if (fromSettings.isPresent()) {
                if (binding.bound()) return fromSettings.get();
                refuse(repoId, url, "the ~/.m2/settings.xml <server> `" + repoId + "`", binding);
            }
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

    /**
     * Whether, and why not, a name-keyed credential for one repository id may reach one URL.
     * {@code mismatch} is non-null when something binds the name to a <em>different</em> origin —
     * the case worth saying out loud, because it is exactly the redirect this guards against.
     */
    record Binding(boolean bound, @Nullable String mismatch) {
        static final Binding BOUND = new Binding(true, null);
        static final Binding UNBOUND = new Binding(false, null);

        static Binding mismatch(String detail) {
            return new Binding(false, detail);
        }
    }

    /**
     * In order of authority: the user's own {@code ~/.jk/config.toml} declaration, then a
     * {@code JK_REPO_<ID>_HOST} variable from the shell, then an id that is the host itself (a
     * container registry logged into as {@code jk repo login ghcr.io}). A declaration or a binding
     * that names another origin is a mismatch, not an absence.
     */
    Binding bindingOf(String repoId, @Nullable URI url) {
        if (url == null || url.getHost() == null) return Binding.UNBOUND;
        for (RepositorySpec spec : userRepositories.get()) {
            if (!spec.name().equals(repoId)) continue;
            if (Http.sameOrigin(spec.url(), url)) return Binding.BOUND;
            return Binding.mismatch("~/.jk/config.toml declares `" + repoId + "` at " + originText(spec.url())
                    + ", not " + originText(url));
        }
        String hostVar = envVarPrefix(repoId) + "HOST";
        String boundHost = nonBlank(hostBindings.apply(hostVar));
        if (boundHost != null) {
            if (hostMatches(boundHost, url)) return Binding.BOUND;
            return Binding.mismatch(hostVar + " names " + boundHost + ", not " + hostPort(url));
        }
        if (repoId.equalsIgnoreCase(url.getHost()) || repoId.equalsIgnoreCase(hostPort(url))) {
            return Binding.BOUND;
        }
        return Binding.UNBOUND;
    }

    /** {@code host}, {@code host:port}, or a full URL whose origin must match. */
    static boolean hostMatches(String binding, URI url) {
        if (binding.contains("://")) {
            try {
                return Http.sameOrigin(URI.create(binding), url);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
        int colon = binding.lastIndexOf(':');
        String host = colon >= 0 ? binding.substring(0, colon) : binding;
        if (url.getHost() == null || !host.equalsIgnoreCase(url.getHost())) return false;
        if (colon < 0) return true;
        try {
            return Integer.parseInt(binding.substring(colon + 1)) == effectivePort(url);
        } catch (NumberFormatException notAPort) {
            return false;
        }
    }

    private static int effectivePort(URI url) {
        if (url.getPort() != -1) return url.getPort();
        return "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
    }

    private static String hostPort(URI url) {
        return url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort());
    }

    private static String originText(@Nullable URI url) {
        if (url == null || url.getHost() == null) return "an unknown origin";
        return RepoCredentialStore.originOf(url).toString();
    }

    /**
     * Once per run per (repository, origin, source): the credential exists and was not sent. Loud
     * on purpose — the alternative is a 401 from a repository the user knows they hold a credential
     * for, with nothing to say why.
     */
    private static void refuse(String repoId, @Nullable URI url, String source, Binding binding) {
        String origin = originText(url);
        RunNotices.warnOnce("repo-credential-refused:" + repoId + " " + origin + " " + source, () -> {
            String head = "jk: warning: repository `" + repoId + "` at " + origin
                    + " is accessed anonymously: a credential for that name exists in " + source + ", but ";
            if (binding.mismatch() != null) return head + binding.mismatch() + ".";
            String prefix = envVarPrefix(repoId);
            return head + "nothing binds the name `" + repoId + "` to that host — the repository is declared by the "
                    + "project, not by ~/.jk/config.toml. To send it, declare [repositories." + repoId
                    + "] with this URL in ~/.jk/config.toml, set " + prefix + "HOST="
                    + (url == null || url.getHost() == null ? "<host>" : hostPort(url)) + " in your shell, or run "
                    + "`jk repo login " + repoId + " --url " + SafeUri.forMessage(url) + "`.";
        });
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
    private Optional<RepoCredential> forgeBridge(@Nullable URI url) {
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

    private static @Nullable String nonBlank(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
