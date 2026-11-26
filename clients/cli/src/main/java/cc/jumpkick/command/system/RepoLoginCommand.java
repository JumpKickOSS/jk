// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretVault;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk repo login <id>} — store credentials for an artifact repository, keyed by the
 * repository id used in {@code jk.toml} / {@code settings.xml} and bound to the repository's
 * origin.
 *
 * <p>The secret is read from stdin (never an argv flag, so it stays out of shell history and
 * process listings). With {@code --username} the secret is treated as a password (HTTP Basic);
 * otherwise it's a bearer token.
 *
 * <p>The login records where the credential may go: {@code --url}, else the URL the id resolves to
 * in {@code ~/.jk/config.toml}, else in the current directory's {@code jk.toml} (with its {@code
 * http(s)} publish-url beside it, when it has one), else the id itself when it is a host ({@code
 * ghcr.io}, {@code 127.0.0.1:5000}). The resolver sends the stored credential to those origins
 * only, so a project that later points the same id at another host gets nothing — and the origins
 * are printed here so the user sees what they bound.
 *
 * <p>The secret goes to the OS keychain when one answers, and the record keeps a pointer to it;
 * otherwise it stays in the owner-only record, with one notice saying why ({@link
 * cc.jumpkick.secret.SecretVault}). A login over an existing record moves its secret the same way.
 */
public final class RepoLoginCommand implements CliCommand {

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Store credentials for an artifact repository";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<USER>", "HTTP Basic username (else stdin = bearer token)", "-s", "--username"),
                Opt.value("<url>", "URL the credential is for (default: the declared URL)", "-U", "--url"),
                Opt.value(
                                "<dir>",
                                "Override the credentials directory. Default: <home>/creds/repo.",
                                "--credentials-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("id", Arity.ONE, "Repository id (matches [repositories.<id>] in jk.toml)."));
    }

    @Override
    public int run(Invocation in) {
        String id = in.positionals().get(0);
        String username = in.value("username").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(CliPaths::abs).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        List<URI> urls;
        try {
            urls = urlsFor(id, in.value("url").orElse(null), global.workingDir());
        } catch (IllegalArgumentException malformed) {
            CommandWedge.printFail("Repo", malformed.getMessage());
            return 1;
        }
        if (urls.isEmpty()) {
            CommandWedge.printFail(
                    "Repo",
                    "no repository '" + id + "' in " + ManifestPaths.MANIFEST
                            + " or ~/.jk/config.toml; pass --url <repository-url>");
            return 1;
        }

        String secret;
        try {
            secret = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            CommandWedge.printFail("Repo", "could not read secret from stdin: " + e.getMessage());
            return 1;
        }
        if (secret.isBlank()) {
            CommandWedge.printFail("Repo", "no " + (username != null ? "password" : "token") + " supplied on stdin.");
            return 1;
        }

        RepoCredential cred = (username != null && !username.isBlank())
                ? new RepoCredential.Basic(username.strip(), secret)
                : new RepoCredential.Bearer(secret);
        SecretVault.Stored stored;
        try {
            stored = store(credentialsDir).write(id, cred, urls);
        } catch (IllegalStateException noKeychain) {
            CommandWedge.printFail("Repo", noKeychain.getMessage());
            return 1;
        }
        if (stored.notice() != null) CliOutput.err(stored.notice());

        if (!global.quiet) {
            SecretVault.Pointer pointer = stored.pointer();
            CommandWedge.printOk(
                    "Login",
                    "Stored " + (username != null ? "basic" : "token") + " credentials for repository '" + id
                            + "', sent only to "
                            + RepoCredentialStore.spelled(urls.stream()
                                    .map(RepoCredentialStore::originOf)
                                    .toList())
                            + (pointer == null
                                    ? ""
                                    : "; the " + (username != null ? "password" : "token") + " is in the "
                                            + SecretVault.displayName(pointer.backend()))
                            + ".");
        }
        return 0;
    }

    /**
     * The URLs the credential is bound to, empty when nothing names one — a mirror of the user's
     * config as well as a repository; a declaration's http(s) publish-url rides beside its url. The
     * user's own config outranks the project's manifest: when both declare the id, the login binds
     * to the one the project cannot edit, and a project pointing elsewhere is refused with a warning
     * naming both.
     */
    static List<URI> urlsFor(String id, @Nullable String urlFlag, Path workingDir) {
        if (urlFlag != null && !urlFlag.isBlank()) {
            URI explicit = URI.create(urlFlag.strip());
            RepoCredentialStore.originOf(explicit); // validates scheme + host
            return List.of(explicit);
        }
        for (RepositorySpec spec : GlobalConfig.repositoryDeclarations()) {
            if (spec.name().equals(id)) return withPublishUrl(spec.url(), spec.publishUrl());
        }
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(ManifestPaths.manifestIn(workingDir))) {
            if (repo.name().equals(id)) {
                return withPublishUrl(
                        URI.create(repo.url()), repo.publishUrl() == null ? null : URI.create(repo.publishUrl()));
            }
        }
        URI host = hostAsUrl(id);
        return host == null ? List.of() : List.of(host);
    }

    /** {@code url}, and {@code publishUrl} beside it when that is an http(s) URL. */
    private static List<URI> withPublishUrl(URI url, @Nullable URI publishUrl) {
        if (publishUrl == null || publishUrl.getHost() == null) return List.of(url);
        String scheme =
                publishUrl.getScheme() == null ? "" : publishUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return List.of(url);
        return List.of(url, publishUrl);
    }

    /**
     * {@code https://<id>} when the id reads as a host — a dotted name or a host:port, so a
     * repository nickname like {@code corp-nexus} is not mistaken for a machine called that.
     */
    static @Nullable URI hostAsUrl(String id) {
        if (id.indexOf('.') < 0 && id.indexOf(':') < 0) return null;
        try {
            URI asHost = URI.create("https://" + id);
            String authority = asHost.getRawAuthority();
            return asHost.getHost() != null && authority != null && authority.equalsIgnoreCase(id) ? asHost : null;
        } catch (IllegalArgumentException notAHost) {
            return null;
        }
    }

    private static RepoCredentialStore store(@Nullable Path credentialsDir) {
        return credentialsDir != null ? new RepoCredentialStore(credentialsDir) : new RepoCredentialStore();
    }
}
