// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.util.KeychainSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxKeychainBackendTest {

    @Test
    void an_item_round_trips_through_one_owner_only_file_and_is_removed(@TempDir Path dir) throws Exception {
        SandboxKeychainBackend backend = new SandboxKeychainBackend(dir.resolve("keychain"));
        assertThat(backend.read("repo/nexus")).isEmpty();

        backend.write("repo/nexus", "jk repo login nexus", "p@ss w0rd \"x\"\n");

        Path file = dir.resolve("keychain/item.repo+nexus");
        assertThat(file).isRegularFile();
        if (!Os.isWindows()) assertThat(Files.getPosixFilePermissions(file)).hasSize(2);
        assertThat(backend.read("repo/nexus")).contains("p@ss w0rd \"x\"\n");
        backend.write("repo/nexus", "jk repo login nexus", "second");
        assertThat(backend.read("repo/nexus")).contains("second");

        backend.delete("repo/nexus");
        backend.delete("repo/nexus");
        assertThat(file).doesNotExist();
        assertThat(backend.read("repo/nexus")).isEmpty();
    }

    @Test
    void an_item_that_names_a_parent_stays_inside_the_directory(@TempDir Path dir) {
        SandboxKeychainBackend backend = new SandboxKeychainBackend(dir.resolve("keychain"));
        backend.write("..", "l", "s");
        assertThat(dir.resolve("keychain/item...")).isRegularFile();
        assertThatThrownBy(() -> backend.write("repo/Nexus", "l", "s")).isInstanceOf(IllegalArgumentException.class);
    }

    /** jk's test launcher names a directory in the test JVM's own home; its vault keeps logins there. */
    @Test
    void the_launcher_names_a_sandbox_under_the_test_home_and_the_vault_writes_only_there() {
        Path sandbox = Objects.requireNonNull(KeychainSandbox.dir(), "jk's test launcher sets " + KeychainSandbox.ENV);
        assertThat(sandbox).isEqualTo(JkDirs.home().resolve("keychain"));
        SecretBackend backend = SecretVault.hostBackends(SecretCommand.REAL, HostSession.current())
                .get(0);
        assertThat(((SandboxKeychainBackend) backend).dir()).isEqualTo(sandbox);
        String item = "test/" + UUID.randomUUID();
        try {
            backend.write(item, "jk test item", "sandboxed");
            assertThat(sandbox.resolve("item." + item.replace('/', '+'))).isRegularFile();
        } finally {
            backend.delete(item);
        }
        assertThat(SecretVault.displayName(SandboxKeychainBackend.ID)).isEqualTo("sandbox keychain");
    }

    /** A refusal is kept for the harness that fails the class, and handed over once. */
    @Test
    void a_refusal_is_kept_until_drained() {
        KeychainSandbox.drainRefusals();
        assertThatThrownBy(() -> KeychainSandbox.refuse("Secret Service"))
                .isInstanceOf(KeychainSandbox.Refused.class)
                .hasMessageContaining("asked the real Secret Service while JK_KEYCHAIN_SANDBOX is set")
                .hasMessageContaining(String.valueOf(KeychainSandbox.dir()));
        assertThat(KeychainSandbox.drainRefusals())
                .singleElement()
                .asString()
                .contains("asked the real Secret Service");
        assertThat(KeychainSandbox.drainRefusals()).isEmpty();
    }
}
