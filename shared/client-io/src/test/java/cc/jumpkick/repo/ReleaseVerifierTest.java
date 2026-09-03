// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseVerifierTest {

    @Test
    void baked_in_release_key_is_present_and_parseable() {
        assertThat(ReleaseVerifier.BUILT_IN_KEY).isNotBlank();
        ReleaseVerifier v = ReleaseVerifier.current(List.of());
        assertThat(v.available()).isTrue();
    }

    @Test
    void installer_key_parameters_are_the_baked_in_spki_key() throws Exception {
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(ReleaseVerifier.BUILT_IN_KEY)));

        assertThat(unsigned(key.getModulus().toByteArray()))
                .isEqualTo(Base64.getDecoder().decode(ReleaseVerifier.BUILT_IN_RSA_MODULUS));
        assertThat(unsigned(key.getPublicExponent().toByteArray()))
                .isEqualTo(Base64.getDecoder().decode(ReleaseVerifier.BUILT_IN_RSA_EXPONENT));
        assertThat(key.getModulus().bitLength()).isEqualTo(3072);
    }

    @Test
    void signature_verifies_against_a_trusted_key_and_tampering_is_fatal() throws Exception {
        KeyPair pair = rsaPair();
        byte[] sums = "abc123  jk-engine-1.0.0.jar\n".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(sums);
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        ReleaseVerifier verifier = ReleaseVerifier.of(List.of(pub));
        assertThat(verifier.available()).isTrue();
        verifier.verify(sums, sig); // does not throw

        // Rotation shape: an unknown key first, the trusted one second — any trusted key passes.
        KeyPair other = rsaPair();
        String otherPub = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
        ReleaseVerifier.of(List.of(otherPub, pub)).verify(sums, sig);

        // Tampered sums: fatal, with the refusal spelled out.
        byte[] tampered = "evil000  jk-engine-1.0.0.jar\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(tampered, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");

        // Wrong key only: fatal.
        assertThatThrownBy(() -> ReleaseVerifier.of(List.of(otherPub)).verify(sums, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");

        // No keys at all: verification unavailable is itself fatal.
        assertThatThrownBy(() -> ReleaseVerifier.of(List.of()).verify(sums, sig))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no trusted release keys");
    }

    @Test
    void checksum_manifest_requires_one_strict_exact_entry() throws Exception {
        byte[] valid = ("a".repeat(64) + "  jk-linux-x86_64.xz\n" + "b".repeat(64) + "  jk-engine-1.0.0.jar\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(ReleaseVerifier.sha256For(valid, "jk-linux-x86_64.xz")).isEqualTo("a".repeat(64));

        byte[] duplicate = ("a".repeat(64) + "  jk-linux-x86_64.xz\n" + "b".repeat(64) + "  jk-linux-x86_64.xz\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ReleaseVerifier.sha256For(duplicate, "jk-linux-x86_64.xz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ReleaseVerifier.sha256For(valid, "other.xz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no unique exact entry");
        assertThatThrownBy(() -> ReleaseVerifier.sha256For(
                        ("a".repeat(64) + " *jk-linux-x86_64.xz\n").getBytes(StandardCharsets.UTF_8),
                        "jk-linux-x86_64.xz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("malformed");
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        return generator.generateKeyPair();
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
