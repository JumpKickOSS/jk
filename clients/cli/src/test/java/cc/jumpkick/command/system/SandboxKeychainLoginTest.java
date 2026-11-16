// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.util.KeychainSandbox;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code jk repo login} in a test JVM, with no {@code --credentials-dir} and a store that insists on
 * a keychain, puts the secret in the sandbox keychain the launcher named and never in the OS
 * keychain of whoever runs the suite; {@code jk repo logout} removes it from there (JK-PB8M1). This
 * is the path {@code PublishCentralCommandTest} took to the real Secret Service's {@code
 * repo/central}.
 */
class SandboxKeychainLoginTest {

    private static final String STORE = "jk.env." + SecretVault.ENV;

    @BeforeEach
    void insistOnAKeychain() {
        System.setProperty(STORE, "keychain");
    }

    @AfterEach
    void restore() {
        System.clearProperty(STORE);
    }

    private static int run(String stdin, String... args) {
        InputStream prev = System.in;
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            return Jk.execute(args);
        } finally {
            System.setIn(prev);
        }
    }

    @Test
    void a_login_and_logout_keep_the_secret_in_the_sandbox_keychain() throws Exception {
        Path sandbox = Objects.requireNonNull(KeychainSandbox.dir(), "jk's test launcher sets " + KeychainSandbox.ENV);
        String id = "sbx-" + UUID.randomUUID().toString().substring(0, 8);
        Path record = JkDirs.creds().resolve("repo").resolve(id);
        Path item = sandbox.resolve("item.repo+" + id);

        assertThat(run("tok-sandboxed\n", "repo", "login", id, "--url", "https://nexus.example:8443/m2/"))
                .isZero();

        assertThat(Files.readAllLines(record))
                .containsExactly("bearer https://nexus.example:8443 keychain", "sandbox-keychain repo/" + id);
        assertThat(item).isRegularFile();
        assertThat(new RepoCredentialStore().read(id))
                .map(RepoCredentialStore.Entry::credential)
                .contains(new RepoCredential.Bearer("tok-sandboxed"));

        assertThat(run("", "repo", "logout", id)).isZero();
        assertThat(record).doesNotExist();
        assertThat(item).doesNotExist();
    }
}
