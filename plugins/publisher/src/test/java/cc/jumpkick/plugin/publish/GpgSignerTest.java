// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpgSignerTest {

    @Test
    void sign_and_verify_round_trip(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "swordfish");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray());

        byte[] data = "the artifact bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signArmored(Files.write(tempDir.resolve("artifact"), data));

        String text = new String(signature, StandardCharsets.US_ASCII);
        assertThat(text).startsWith("-----BEGIN PGP SIGNATURE-----");
        assertThat(text).endsWith("-----END PGP SIGNATURE-----\n");

        // Signature verifies against the matching public key.
        GpgTestFixture.verifyDetached(data, signature, key.publicRing());

        // And does NOT verify against tampered bytes.
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> GpgTestFixture.verifyDetached(tampered, signature, key.publicRing()))
                .hasMessageContaining("did not verify");
    }

    @Test
    void wrong_passphrase_is_rejected_with_io_exception(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "correct");
        assertThatThrownBy(() -> GpgSigner.fromKeyFile(key.secretKeyFile(), "wrong".toCharArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("GPG key");
    }

    @Test
    void empty_keyring_is_rejected(@TempDir Path tempDir) throws Exception {
        Path empty = tempDir.resolve("empty.asc");
        Files.writeString(empty, "");
        assertThatThrownBy(() -> GpgSigner.fromKeyFile(empty, new char[0])).isInstanceOf(IOException.class);
    }

    /** {@code gpg --export-secret-subkeys}: the primary stays offline and its secret is a stub. */
    @Test
    void an_offline_primary_signs_with_its_signing_subkey(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generateWithSubkeys(
                tempDir, "swordfish", KeyFlags.CERTIFY_OTHER, true, List.of(GpgTestFixture.Subkey.signing()));
        assertThat(primarySecret(key.secretKeyFile()).isPrivateKeyEmpty()).isTrue();

        byte[] data = "the artifact bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray())
                .signArmored(Files.write(tempDir.resolve("artifact"), data));

        GpgTestFixture.verifyDetached(data, signature, key.publicRing());
        assertThat(GpgTestFixture.signerKeyId(signature)).isEqualTo(subkeyId(key, 0));
    }

    /** The primary has its secret but only the certify flag: a signature from it is one Central can refuse. */
    @Test
    void a_certify_only_primary_leaves_the_signing_to_the_subkey(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generateWithSubkeys(
                tempDir, "swordfish", KeyFlags.CERTIFY_OTHER, false, List.of(GpgTestFixture.Subkey.signing()));

        byte[] signature = GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray())
                .signArmored(Files.write(tempDir.resolve("artifact"), new byte[] {1}));

        assertThat(GpgTestFixture.signerKeyId(signature)).isEqualTo(subkeyId(key, 0));
    }

    @Test
    void revoked_and_expired_subkeys_are_passed_over(@TempDir Path tempDir) throws Exception {
        Date lastWeek = Date.from(Instant.now().minus(Duration.ofDays(7)));
        var key = GpgTestFixture.generateWithSubkeys(
                tempDir,
                "swordfish",
                KeyFlags.CERTIFY_OTHER,
                true,
                List.of(
                        new GpgTestFixture.Subkey(KeyFlags.SIGN_DATA, lastWeek, 0, false),
                        new GpgTestFixture.Subkey(KeyFlags.SIGN_DATA, new Date(), 0, true),
                        new GpgTestFixture.Subkey(
                                KeyFlags.SIGN_DATA,
                                new Date(),
                                Duration.ofDays(1).toSeconds(),
                                false),
                        new GpgTestFixture.Subkey(
                                KeyFlags.SIGN_DATA, lastWeek, Duration.ofDays(1).toSeconds(), false)));

        byte[] signature = GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray())
                .signArmored(Files.write(tempDir.resolve("artifact"), new byte[] {1}));

        // The newest key that may still sign: the second is revoked and the fourth expired days ago.
        assertThat(GpgTestFixture.signerKeyId(signature)).isEqualTo(subkeyId(key, 2));
    }

    @Test
    void a_ring_with_no_usable_signing_key_says_why_for_each_key(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generateWithSubkeys(
                tempDir,
                "swordfish",
                KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA,
                true,
                List.of(
                        new GpgTestFixture.Subkey(
                                KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE, new Date(), 0, false),
                        new GpgTestFixture.Subkey(KeyFlags.SIGN_DATA, new Date(), 0, true)));

        assertThatThrownBy(() -> GpgSigner.fromKeyFile(key.secretKeyFile(), "swordfish".toCharArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no usable signing key")
                .hasMessageContaining("no secret material")
                .hasMessageContaining("not flagged for signing")
                .hasMessageContaining("revoked");
    }

    private static PGPSecretKey primarySecret(Path keyFile) throws Exception {
        try (InputStream in = PGPUtil.getDecoderStream(Files.newInputStream(keyFile))) {
            return new PGPSecretKeyRingCollection(in, new JcaKeyFingerprintCalculator())
                    .getKeyRings()
                    .next()
                    .getSecretKey();
        }
    }

    private static long subkeyId(GpgTestFixture.GeneratedKey key, int index) {
        List<PGPPublicKey> keys = new ArrayList<>();
        key.publicRing().getPublicKeys().forEachRemaining(keys::add);
        return keys.get(index + 1).getKeyID();
    }

    @Test
    void armored_output_is_lf_only() {
        // Driven directly: on a POSIX host BouncyCastle already emits LF, so a round trip through
        // sign() would pass with or without the rewrite.
        byte[] crlf = "-----BEGIN PGP SIGNATURE-----\r\n\r\niQIz\r\n-----END PGP SIGNATURE-----\r\n"
                .getBytes(StandardCharsets.US_ASCII);

        byte[] lf = GpgSigner.lfOnly(crlf);

        assertThat(new String(lf, StandardCharsets.US_ASCII))
                .isEqualTo("-----BEGIN PGP SIGNATURE-----\n\niQIz\n-----END PGP SIGNATURE-----\n");
    }
}
