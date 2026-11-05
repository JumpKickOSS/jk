// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackend;
import cc.jumpkick.secret.SecretBackendException;
import cc.jumpkick.secret.SecretVault;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientKeychainTest {

    /** A keychain that answers every read with {@code <item>-secret}, except a locked item. */
    private record Answering(String id) implements SecretBackend {
        @Override
        public String displayName() {
            return id;
        }

        @Override
        public Probe probe() {
            return Probe.AVAILABLE;
        }

        @Override
        public Optional<String> read(String item) {
            if (item.endsWith("locked")) throw new SecretBackendException("the default keyring is locked");
            if (item.endsWith("gone")) return Optional.empty();
            return Optional.of(item + "-secret");
        }

        @Override
        public void write(String item, String label, String secret) {}

        @Override
        public void delete(String item) {}
    }

    @Test
    void every_keychain_login_rides_the_request_as_its_secret_or_why_there_is_none() {
        SecretVault vault = SecretVault.of(SecretVault.Choice.AUTO, List.of(new Answering("bus")));
        var nexus = new SecretVault.Pointer("bus", "repo/nexus");
        var locked = new SecretVault.Pointer("bus", "repo/locked");
        var gone = new SecretVault.Pointer("bus", "repo/gone");
        var elsewhere = new SecretVault.Pointer("macos-keychain", "repo/mac");
        var forge = new SecretVault.Pointer("bus", "forge/github.com");
        List<SecretVault.Pointer> pointers = ClientKeychain.pointers(
                List.of(
                        new RepoCredentialStore.Login("nexus", "bearer", URI.create("https://n"), nexus),
                        new RepoCredentialStore.Login("locked", "bearer", URI.create("https://l"), locked),
                        new RepoCredentialStore.Login("gone", "bearer", URI.create("https://g"), gone),
                        new RepoCredentialStore.Login("mac", "bearer", URI.create("https://m"), elsewhere),
                        new RepoCredentialStore.Login("plain", "bearer", URI.create("https://p"), null)),
                List.of(new TokenStore.Login("github.com", forge)));

        ClientKeychain.Carried carried = ClientKeychain.read(vault, pointers);

        assertThat(carried.secrets())
                .isEqualTo(Map.of(
                        "bus repo/nexus", "repo/nexus-secret", "bus forge/github.com", "forge/github.com-secret"));
        assertThat(carried.refused())
                .containsOnlyKeys("bus repo/locked", "bus repo/gone", "macos-keychain repo/mac")
                .containsEntry("bus repo/locked", "the default keyring is locked")
                .containsEntry("bus repo/gone", "it holds no item repo/gone");
        assertThat(carried.refused().get("macos-keychain repo/mac")).contains("this machine has no macos-keychain");
    }

    @Test
    void no_keychain_login_reads_nothing() {
        assertThat(ClientKeychain.read(SecretVault.fileOnly(), List.of())).isEqualTo(ClientKeychain.Carried.NONE);
    }
}
