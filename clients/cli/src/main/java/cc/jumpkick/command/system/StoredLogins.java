// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackend;
import cc.jumpkick.secret.SecretVault;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The logins {@code jk repo login} and {@code jk auth login} stored, for {@code jk doctor}: each
 * one's repository or host, the origin it is bound to, and where its secret is — a keychain, or
 * the owner-only file. Read from the records alone, so listing them never asks a keychain for a
 * secret.
 */
public final class StoredLogins {

    private StoredLogins() {}

    /** One login. {@code store} is a keychain's id, or {@code file}. */
    public record Login(String kind, String name, @Nullable String origin, String store) {}

    /**
     * Every login, where a new one would go ({@code destination}: a keychain's id or {@code file}),
     * and why the configured choice is unreadable when it is.
     */
    public record Rows(
            List<Login> rows, String destination, @Nullable String error) {}

    /**
     * As {@link #collect(List, List, SecretVault)} with this machine's stores. A config.toml that
     * does not parse leaves {@code [credentials] store} unknown, so the destination is unknown too
     * and says why; the logins themselves are still listed, and doctor keeps running.
     */
    static Rows collect() {
        // Listing reads only the records, never a secret, so it needs no keychain choice.
        List<RepoCredentialStore.Login> repo =
                new RepoCredentialStore(JkDirs.creds().resolve("repo")).logins();
        List<TokenStore.Login> forge = new TokenStore(JkDirs.creds().resolve("forge")).logins();
        SecretVault vault;
        try {
            vault = SecretVault.current();
        } catch (UserConfigParseException unparsed) {
            return new Rows(
                    rows(repo, forge),
                    "unknown",
                    "where a new login goes is unknown: " + unparsed.file() + ":" + unparsed.line() + ":"
                            + unparsed.column() + " does not parse");
        }
        return collect(repo, forge, vault);
    }

    static Rows collect(List<RepoCredentialStore.Login> repo, List<TokenStore.Login> forge, SecretVault vault) {
        Optional<SecretBackend> destination = vault.destination(new ArrayList<>());
        return new Rows(rows(repo, forge), destination.map(SecretBackend::id).orElse("file"), vault.misconfigured());
    }

    private static List<Login> rows(List<RepoCredentialStore.Login> repo, List<TokenStore.Login> forge) {
        List<Login> rows = new ArrayList<>();
        for (RepoCredentialStore.Login login : repo) {
            rows.add(new Login(
                    "repo",
                    login.name(),
                    login.origin() == null ? null : login.origin().toString(),
                    storeOf(login.pointer())));
        }
        for (TokenStore.Login login : forge) {
            rows.add(new Login("forge", login.host(), null, storeOf(login.pointer())));
        }
        return List.copyOf(rows);
    }

    private static String storeOf(SecretVault.@Nullable Pointer pointer) {
        return pointer == null ? "file" : pointer.backend();
    }

    private static String named(String store) {
        return store.equals("file") ? "the owner-only file" : "the " + SecretVault.displayName(store);
    }

    /** A head line saying where new logins go, then one line per login. */
    public static List<String> render(Rows logins, Theme t) {
        List<String> out = new ArrayList<>();
        if (logins.error() != null) {
            out.add(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("logins", t.cyan()) + " — "
                    + logins.error());
        } else {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + Theme.colorize("logins", t.cyan()) + " — "
                    + logins.rows().size() + " stored; new ones go to " + named(logins.destination()));
        }
        for (Login login : logins.rows()) {
            String bound = login.origin() == null ? "" : " " + Theme.colorize("→", t.darkGray()) + " " + login.origin();
            out.add(Theme.colorize("login:   ", t.completedStep()) + " " + login.kind() + " "
                    + Theme.colorize(login.name(), t.cyan()) + bound + " (" + named(login.store()) + ")");
        }
        return out;
    }

    /** The {@code logins} member of a JSON report. */
    public static String json(Rows logins) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < logins.rows().size(); i++) {
            Login l = logins.rows().get(i);
            if (i > 0) array.append(',');
            array.append(JsonFields.object()
                    .string("kind", l.kind())
                    .string("name", l.name())
                    .string("origin", l.origin())
                    .string("store", l.store())
                    .finish());
        }
        array.append(']');
        return JsonFields.object()
                .string("destination", logins.destination())
                .string("error", logins.error())
                .token("rows", array.toString())
                .finish();
    }
}
