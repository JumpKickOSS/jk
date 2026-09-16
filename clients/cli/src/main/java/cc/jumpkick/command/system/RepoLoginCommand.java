// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
 * in {@code ~/.jk/config.toml}, else in the current directory's {@code jk.toml}, else the id
 * itself when it is a host ({@code ghcr.io}, {@code 127.0.0.1:5000}). The resolver sends the stored
 * credential to that origin only, so a project that later points the same id at another host gets
 * nothing — and the origin is printed here so the user sees what they bound.
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
                Opt.value("<USER>", "HTTP Basic username (else stdin = bearer token)", "--username"),
                Opt.value("<url>", "URL the credential is for (default: the declared URL)", "--url"),
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
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        URI url;
        try {
            url = urlFor(id, in.value("url").orElse(null), global.workingDir());
        } catch (IllegalArgumentException malformed) {
            CliOutput.err("error: " + malformed.getMessage());
            return 1;
        }
        if (url == null) {
            CliOutput.err("error: no repository named '" + id + "' is declared in " + ManifestPaths.MANIFEST
                    + " here or in ~/.jk/config.toml, and '" + id
                    + "' is not a host. Pass --url <repository-url> to say where this credential may be sent.");
            return 1;
        }

        String secret;
        try {
            secret = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            CliOutput.err("error: could not read secret from stdin: " + e.getMessage());
            return 1;
        }
        if (secret.isBlank()) {
            CliOutput.err("error: no " + (username != null ? "password" : "token") + " supplied on stdin.");
            return 1;
        }

        RepoCredential cred = (username != null && !username.isBlank())
                ? new RepoCredential.Basic(username.strip(), secret)
                : new RepoCredential.Bearer(secret);
        store(credentialsDir).write(id, cred, url);

        if (!global.quiet) {
            CommandWedge.printOk(
                    "Login",
                    "Stored " + (username != null ? "basic" : "token") + " credentials for repository '" + id
                            + "', sent only to " + RepoCredentialStore.originOf(url) + ".");
        }
        return 0;
    }

    /**
     * The URL the credential is bound to, or null when nothing names one. The user's own config
     * outranks the project's manifest: when both declare the id, the login binds to the one the
     * project cannot edit, and a project pointing elsewhere is refused with a warning naming both.
     */
    static @Nullable URI urlFor(String id, @Nullable String urlFlag, Path workingDir) {
        if (urlFlag != null && !urlFlag.isBlank()) {
            URI explicit = URI.create(urlFlag.strip());
            RepoCredentialStore.originOf(explicit); // validates scheme + host
            return explicit;
        }
        for (RepositorySpec spec : GlobalConfig.repositories()) {
            if (spec.name().equals(id)) return spec.url();
        }
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(ManifestPaths.manifestIn(workingDir))) {
            if (repo.name().equals(id)) return URI.create(repo.url());
        }
        return hostAsUrl(id);
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
