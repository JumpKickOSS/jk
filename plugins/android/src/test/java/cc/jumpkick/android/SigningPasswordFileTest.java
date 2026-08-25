// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keystore passwords must not appear on a signer's command line.
 *
 * <p>{@code /proc/<pid>/cmdline} is world-readable on Linux and {@code ps} shows the same bytes to
 * every local account, so a release password on {@code jarsigner -storepass <pass>} is a credential
 * published to the whole machine for as long as the signer runs. These cases read the argv the
 * plugin actually builds — all three signers — and assert the password is absent from it.
 */
class SigningPasswordFileTest {

    private static final String RELEASE_PASSWORD = "release-store-p@ssw0rd";

    /** The value is on the file, owner-only, and nothing else in the process can be seen holding it. */
    @Test
    void the_password_lands_on_an_owner_only_file_and_is_deleted_after() throws Exception {
        Path path;
        try (Signing.PasswordFile pass = Signing.passwordFile(RELEASE_PASSWORD)) {
            path = pass.path();
            assertThat(Files.readString(path, StandardCharsets.UTF_8))
                    .as("keytool/jarsigner/bundletool all read the first line — no terminator")
                    .isEqualTo(RELEASE_PASSWORD);
            assertThat(pass.arg()).isEqualTo(path.toAbsolutePath().toString());
            if (!Os.isWindows()) {
                assertThat(Files.getPosixFilePermissions(path))
                        .as("0600: no group, no other")
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
        assertThat(path).doesNotExist();
    }

    /** jarsigner: the AAB packager's argv names two paths and no password. */
    @Test
    void jarsigner_argv_carries_no_password(@TempDir Path dir) throws Exception {
        try (Signing.PasswordFile store = Signing.passwordFile(RELEASE_PASSWORD);
                Signing.PasswordFile key = Signing.passwordFile("release-key-p@ssw0rd")) {
            List<String> argv =
                    AabPackager.jarsignerArgs(dir.resolve("release.jks"), store, key, dir.resolve("app.aab"), "upload");

            assertThat(argv).contains("-storepass:file", store.arg(), "-keypass:file", key.arg());
            assertThat(argv).doesNotContain("-storepass", "-keypass");
            assertThat(String.join(" ", argv))
                    .as("the whole command line, as ps would render it")
                    .doesNotContain(RELEASE_PASSWORD)
                    .doesNotContain("release-key-p@ssw0rd");
        }
    }

    /** keytool: the debug-keystore generation argv. Published password, same argv discipline. */
    @Test
    void keytool_argv_carries_no_password(@TempDir Path dir) throws Exception {
        try (Signing.PasswordFile pass = Signing.passwordFile(DebugKeystore.PASSWORD)) {
            List<String> argv = DebugKeystore.genKeypairArgs(dir.resolve("debug.keystore"), pass);

            assertThat(argv).contains("-storepass:file", "-keypass:file", pass.arg());
            assertThat(argv).doesNotContain("-storepass", "-keypass", DebugKeystore.PASSWORD);
        }
    }

    /** bundletool: {@code file:} rather than {@code pass:} on all four signing flags. */
    @Test
    void bundletool_argv_carries_no_password(@TempDir Path dir) throws Exception {
        try (Signing.PasswordFile pass = Signing.passwordFile(DebugKeystore.PASSWORD)) {
            List<String> argv = DeployCommand.signingFlags(dir.resolve("debug.keystore"), pass);

            assertThat(argv).contains("--ks-pass=file:" + pass.arg(), "--key-pass=file:" + pass.arg());
            assertThat(String.join(" ", argv)).doesNotContain("pass:" + DebugKeystore.PASSWORD);
        }
    }

    /**
     * End to end against the real tool: {@code keytool} accepts the {@code :file} form and produces
     * a store that opens with the password. Without this the argv assertions above would be happy
     * with a spelling no signer understands.
     *
     * <p>{@link DebugKeystoreTest} exercises the same generation for its own reasons; this one says
     * why the argv is shaped the way it is.
     */
    @Test
    void keytool_really_accepts_the_file_form(@TempDir Path dir) throws Exception {
        Path keystore = DebugKeystore.ensure(dir, Path.of(System.getProperty("java.home")));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, DebugKeystore.PASSWORD.toCharArray());
        }
        assertThat(ks.getKey(DebugKeystore.ALIAS, DebugKeystore.PASSWORD.toCharArray()))
                .isNotNull();
    }
}
