// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretVault;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                        "ok:      logins — 3 stored; new ones go to the owner-only file",
                        "login:    repo nexus → https://nexus.acme (the Secret Service)",
                        "login:    repo ghcr.io → https://ghcr.io (the owner-only file)",
                        "login:    forge github.com (the macOS Keychain)");
    }

    @Test
    void a_config_toml_that_does_not_parse_leaves_doctor_running_and_names_the_position(@TempDir Path home)
            throws Exception {
        System.setProperty("jk.env.JK_HOME", home.toString());
        Files.writeString(JkDirs.userConfigFile(), "[credentials]\nstore = \"keychain\"\n\n[mirrors\n");

        StoredLogins.Rows rows = StoredLogins.collect();

        assertThat(rows.destination()).isEqualTo("unknown");
        assertThat(rows.error())
                .startsWith("where a new login goes is unknown: " + JkDirs.userConfigFile() + ":4:")
                .endsWith(" does not parse");
        assertThat(StoredLogins.render(rows, Theme.active()).stream().map(TestAnsi::strip))
                .first()
                .asString()
                .startsWith("warn:    logins — where a new login goes is unknown");
    }
}
