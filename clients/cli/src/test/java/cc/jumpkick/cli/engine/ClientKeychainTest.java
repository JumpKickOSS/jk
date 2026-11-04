// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackend;
import cc.jumpkick.secret.SecretVault;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientKeychainTest {

    /** A keychain that answers every read with {@code <item>-secret}. */
    private record Answering(String id, boolean daemonReachable) implements SecretBackend {
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
            return Optional.of(item + "-secret");
        }

        @Override
        public void write(String item, String label, String secret) {}

        @Override
        public void delete(String item) {}
    }

    @Test
    void only_the_items_of_a_keychain_the_engine_cannot_reach_ride_the_request() {
        SecretVault vault = SecretVault.of(
                SecretVault.Choice.AUTO, List.of(new Answering("bus", false), new Answering("session", true)));
        var bus = new SecretVault.Pointer("bus", "repo/nexus");
        var session = new SecretVault.Pointer("session", "repo/other");
        var forge = new SecretVault.Pointer("bus", "forge/github.com");
        List<SecretVault.Pointer> pointers = ClientKeychain.pointers(
                List.of(
                        new RepoCredentialStore.Login("nexus", "bearer", URI.create("https://n"), bus),
                        new RepoCredentialStore.Login("other", "bearer", URI.create("https://o"), session),
                        new RepoCredentialStore.Login("plain", "bearer", URI.create("https://p"), null)),
                List.of(new TokenStore.Login("github.com", forge)));

        assertThat(ClientKeychain.read(vault, pointers))
                .isEqualTo(Map.of(
                        "bus repo/nexus", "repo/nexus-secret", "bus forge/github.com", "forge/github.com-secret"));
    }

    @Test
    void no_keychain_login_reads_nothing() {
        assertThat(ClientKeychain.read(SecretVault.fileOnly(), List.of())).isEmpty();
    }
}
