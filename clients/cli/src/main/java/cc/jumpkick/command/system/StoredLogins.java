// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.forge.TokenStore;
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

    static Rows collect() {
        SecretVault vault = SecretVault.current();
        return collect(new RepoCredentialStore().logins(), new TokenStore().logins(), vault);
    }

    static Rows collect(List<RepoCredentialStore.Login> repo, List<TokenStore.Login> forge, SecretVault vault) {
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
        Optional<SecretBackend> destination = vault.destination(new ArrayList<>());
        return new Rows(List.copyOf(rows), destination.map(SecretBackend::id).orElse("file"), vault.misconfigured());
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
                    + logins.rows().size() + " stored; a new one keeps its secret in " + named(logins.destination()));
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
