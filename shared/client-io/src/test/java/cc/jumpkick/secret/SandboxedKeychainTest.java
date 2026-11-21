// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A test JVM jk launched never reaches the OS keychain of whoever runs it: the vault it
 * builds keeps logins in the sandbox keychain the launcher names, and a backend over the real
 * Secret Service or macOS Keychain refuses. Every backend here is built over a session with no bus
 * and nothing on {@code PATH} and is never asked to store or read, so even where the sandbox were
 * missing nothing here would reach a keychain: the class fails instead.
 */
class SandboxedKeychainTest {

    @Test
    void a_test_jvms_vault_keeps_its_logins_in_the_sandbox_keychain() {
        assertThat(SecretVault.hostBackends(SecretCommand.REAL, HostSession.desktop()))
                .extracting(SecretBackend::id)
                .containsExactly("sandbox-keychain");
        SecretVault vault = SecretVault.of(
                SecretVault.Choice.KEYCHAIN, SecretVault.hostBackends(SecretCommand.REAL, HostSession.current()));
        assertThat(vault.backend("sandbox-keychain")).isPresent();
        assertThat(vault.backend("secret-service")).isEmpty();
        assertThat(vault.backend("macos-keychain")).isEmpty();
        assertThat(vault.backend("windows-credential-manager")).isEmpty();
    }

    @Test
    void a_backend_over_the_real_secret_service_refuses_in_a_test_jvm() {
        SecretServiceBackend real = new SecretServiceBackend(SecretCommand.REAL, HostSession.desktop());
        assertThatThrownBy(real::probe)
                .isInstanceOf(Error.class)
                .hasMessageContaining("asked the real Secret Service")
                .hasMessageContaining("JK_KEYCHAIN_SANDBOX");
        assertThatThrownBy(() -> real.read("repo/central")).isInstanceOf(Error.class);
        assertThatThrownBy(() -> real.delete("repo/central")).isInstanceOf(Error.class);
    }

    @Test
    void a_backend_over_the_real_macos_keychain_refuses_in_a_test_jvm() {
        assertThatThrownBy(() -> new MacKeychainBackend(SecretCommand.REAL).probe())
                .isInstanceOf(Error.class)
                .hasMessageContaining("asked the real macOS Keychain");
    }
}
