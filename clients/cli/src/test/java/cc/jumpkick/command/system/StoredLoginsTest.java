// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretVault;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoredLoginsTest {

    @Test
    void doctor_lists_each_login_with_where_its_secret_is_and_where_a_new_one_goes() {
        StoredLogins.Rows rows = StoredLogins.collect(
                List.of(
                        new RepoCredentialStore.Login(
                                "nexus",
                                "basic",
                                URI.create("https://nexus.acme"),
                                new SecretVault.Pointer("secret-service", "repo/nexus")),
                        new RepoCredentialStore.Login("ghcr.io", "bearer", URI.create("https://ghcr.io"), null)),
                List.of(new TokenStore.Login(
                        "github.com", new SecretVault.Pointer("macos-keychain", "forge/github.com"))),
                SecretVault.fileOnly());

        assertThat(rows.destination()).isEqualTo("file");
        assertThat(rows.rows().stream().map(StoredLogins.Login::store))
                .containsExactly("secret-service", "file", "macos-keychain");
        assertThat(StoredLogins.render(rows, Theme.active()).stream()
                        .map(TestAnsi::strip)
                        .toList())
                .containsExactly(
                        "ok:      logins — 3 stored; a new one keeps its secret in the owner-only file",
                        "login:    repo nexus → https://nexus.acme (the Secret Service)",
                        "login:    repo ghcr.io → https://ghcr.io (the owner-only file)",
                        "login:    forge github.com (the macOS Keychain)");
    }
}
