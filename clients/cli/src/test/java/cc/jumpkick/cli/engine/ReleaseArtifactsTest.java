// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one verified download path: a release's signed manifest is fetched once and every artifact
 * of the release is hashed against it, and the signed latest-release pointer is read under the
 * same verifier with a rollback refused.
 */
@Tag("integration")
class ReleaseArtifactsTest {

    private static final String VERSION = "1.2.3";

    @Test
    void one_manifest_serves_every_artifact_of_the_release_with_one_checksum_fetch() throws Exception {
        byte[] engine = "engine".getBytes(StandardCharsets.UTF_8);
        byte[] client = "client".getBytes(StandardCharsets.UTF_8);
        try (StubReleaseDirectory release = new StubReleaseDirectory(VERSION)) {
            release.put("jk-engine-1.2.3.jar", engine);
            release.put("jk-linux-x86_64-1.2.3.xz", client);

            ReleaseArtifacts.Manifest manifest = ReleaseArtifacts.manifest(release.base(), VERSION, release.verifier());
            assertThat(manifest.has("jk-linux-x86_64-1.2.3.xz")).isTrue();
            assertThat(manifest.has("jk-1.2.3.jar")).isFalse();
            assertThat(manifest.text()).contains("jk-engine-1.2.3.jar");

            assertThat(manifest.fetch("jk-engine-1.2.3.jar", "engine jar", ReleaseArtifacts.Progress.NONE)
                            .bytes())
                    .isEqualTo(engine);
            assertThat(manifest.fetch("jk-linux-x86_64-1.2.3.xz", "client binary", ReleaseArtifacts.Progress.NONE)
                            .bytes())
                    .isEqualTo(client);
            assertThat(release.requested())
                    .containsExactly("SHA256SUMS", "SHA256SUMS.sig", "jk-engine-1.2.3.jar", "jk-linux-x86_64-1.2.3.xz");
        }
    }

    @Test
    void an_artifact_the_manifest_does_not_vouch_for_is_refused_before_it_is_read() throws Exception {
        try (StubReleaseDirectory release = new StubReleaseDirectory(VERSION)) {
            release.put("jk-engine-1.2.3.jar", "engine".getBytes(StandardCharsets.UTF_8));
            ReleaseArtifacts.Manifest manifest = ReleaseArtifacts.manifest(release.base(), VERSION, release.verifier());
            release.freezeSums();
            release.put("jk-engine-1.2.3.jar", "tampered".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(
                            () -> manifest.fetch("jk-engine-1.2.3.jar", "engine jar", ReleaseArtifacts.Progress.NONE))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("checksum mismatch");
            assertThatThrownBy(() -> manifest.fetch("jk-1.2.3.jar", "client jar", ReleaseArtifacts.Progress.NONE))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no unique exact entry");
        }
    }

    @Test
    void the_latest_pointer_must_verify_and_must_not_roll_back_below_the_running_version() throws Exception {
        KeyPair pair = rsaPair();
        ReleaseVerifier verifier = ReleaseVerifier.of(List.of(spki(pair)));
        byte[] newer = pointer(pair, "0.14.0");
        byte[] same = pointer(pair, "0.13.3");
        byte[] older = pointer(pair, "0.13.0");

        assertThat(ReleaseArtifacts.latestVersion(verifier, newer, "0.13.3")).isEqualTo("0.14.0");
        assertThat(ReleaseArtifacts.latestVersion(verifier, same, "0.13.3")).isEqualTo("0.13.3");

        // Valid signature, older release: the rollback shape a bucket writer or a mirror can stage.
        assertThatThrownBy(() -> ReleaseArtifacts.latestVersion(verifier, older, "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING")
                .hasMessageContaining("0.13.0");
        // Tampered after signing: the two lines changed, the signature line did not.
        byte[] tampered = object(body("0.14.0"), signBase64(pair, body("0.13.0")));
        assertThatThrownBy(() -> ReleaseArtifacts.latestVersion(verifier, tampered, "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");
        // Signed by a key this jk does not trust.
        byte[] foreign = pointer(rsaPair(), "0.14.0");
        assertThatThrownBy(() -> ReleaseArtifacts.latestVersion(verifier, foreign, "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");
        // Not a pointer object.
        byte[] bare = "0.14.0\n".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> ReleaseArtifacts.latestVersion(verifier, bare, "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("malformed");
    }

    private static byte[] body(String version) {
        return ("version " + version + "\nissued 1757700000\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] pointer(KeyPair pair, String version) throws Exception {
        return object(body(version), signBase64(pair, body(version)));
    }

    private static byte[] object(byte[] signedLines, String signatureBase64) {
        return (new String(signedLines, StandardCharsets.US_ASCII) + "signature " + signatureBase64 + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String signBase64(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String spki(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        return generator.generateKeyPair();
    }
}
