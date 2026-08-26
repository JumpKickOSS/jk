// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The debug signing identity must survive a rebuild. Android refuses {@code adb install -r} when
 * the update is signed by a different key than the installed app, so a debug keystore regenerated
 * per build turns every device iteration into an uninstall — the app's data included. These cases
 * pin generate-once-never-again, which is the behaviour, not an optimization.
 */
class DebugKeystoreTest {

    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));

    /** The regression: a second build must sign with the very same key, byte for byte. */
    @Test
    void a_second_build_reuses_the_first_build_s_keystore(@TempDir Path dir) throws Exception {
        Path first = DebugKeystore.ensure(dir, JAVA_HOME);
        byte[] afterFirstBuild = Files.readAllBytes(first);

        Path second = DebugKeystore.ensure(dir, JAVA_HOME);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readAllBytes(second))
                .as("a regenerated keystore is a new key, and a new key cannot update an installed app")
                .isEqualTo(afterFirstBuild);
    }

    /**
     * Byte equality above would also hold for a deterministic regeneration, which RSA keygen is
     * not — but say the real thing anyway: the signing certificate is the same certificate.
     */
    @Test
    void the_signing_certificate_is_the_same_certificate_across_builds(@TempDir Path dir) throws Exception {
        X509Certificate first = certificateOf(DebugKeystore.ensure(dir, JAVA_HOME));
        X509Certificate second = certificateOf(DebugKeystore.ensure(dir, JAVA_HOME));

        assertThat(second.getSerialNumber()).isEqualTo(first.getSerialNumber());
        assertThat(second.getPublicKey()).isEqualTo(first.getPublicKey());
    }

    /** The generated store carries the identity apksig, jarsigner and bundletool are all told to use. */
    @Test
    void the_generated_store_carries_the_standard_debug_identity(@TempDir Path dir) throws Exception {
        KeyStore ks = load(DebugKeystore.ensure(dir, JAVA_HOME));

        assertThat(ks.aliases().asIterator()).toIterable().containsExactly(DebugKeystore.ALIAS);
        assertThat(ks.getKey(DebugKeystore.ALIAS, DebugKeystore.PASSWORD.toCharArray()))
                .isNotNull();
        assertThat(certificateOf(DebugKeystore.ensure(dir, JAVA_HOME))
                        .getSubjectX500Principal()
                        .getName())
                .contains("CN=Android Debug");
    }

    /** A keystore already on disk is adopted untouched — jk never rotates a debug identity. */
    @Test
    void an_existing_keystore_is_never_rewritten(@TempDir Path dir) throws Exception {
        Path planted = Files.write(DebugKeystore.path(dir), new byte[] {1, 2, 3});

        assertThat(DebugKeystore.ensure(dir, JAVA_HOME)).isEqualTo(planted);
        assertThat(Files.readAllBytes(planted)).containsExactly(1, 2, 3);
    }

    /** Generation is atomic: a failed keytool leaves no staging file behind to be adopted later. */
    @Test
    void a_failed_generation_leaves_no_partial_keystore(@TempDir Path dir) throws Exception {
        Path notAJdk = Files.createDirectories(dir.resolve("empty-jdk"));
        Path store = dir.resolve("store");

        assertThatThrownBy(() -> DebugKeystore.ensure(store, notAJdk)).isInstanceOf(IOException.class);
        try (var listing = Files.list(store)) {
            assertThat(listing)
                    .as("no half-written keystore and no staging leftover")
                    .isEmpty();
        }
    }

    /** The Android tools' own lookup order, so jk and Studio share one debug identity. */
    @Test
    void the_stable_dir_follows_the_android_lookup_order() {
        assertThat(DebugKeystore.stableDir(Map.of("ANDROID_USER_HOME", "/x/androidhome")::get, "/home/dev"))
                .isEqualTo(Path.of("/x/androidhome"));
        assertThat(DebugKeystore.stableDir(Map.of("ANDROID_SDK_HOME", "/x/legacy")::get, "/home/dev"))
                .isEqualTo(Path.of("/x/legacy/.android"));
        assertThat(DebugKeystore.stableDir(Map.of("ANDROID_USER_HOME", "", "ANDROID_SDK_HOME", " ")::get, "/home/dev"))
                .as("a blank override is not an override")
                .isEqualTo(Path.of("/home/dev/.android"));
        assertThat(DebugKeystore.stableDir(Map.<String, String>of()::get, "/home/dev"))
                .isEqualTo(Path.of("/home/dev/.android"));
    }

    private static KeyStore load(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, DebugKeystore.PASSWORD.toCharArray());
        }
        return ks;
    }

    private static X509Certificate certificateOf(Path keystore) throws Exception {
        return (X509Certificate) load(keystore).getCertificate(DebugKeystore.ALIAS);
    }
}
