// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateArtifactTest {

    private static final String HASH = "a".repeat(64);

    private static final String V = "0.12.0";

    @Test
    void prefers_xz_on_every_os() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n"
                + HASH + "  jk-linux-x86_64-0.12.0.xz\n"
                + HASH + "  jk-windows-x86_64-0.12.0.xz\n"
                + HASH + "  jk-windows-x86_64-0.12.0.zip\n"
                + HASH + "  jk-macos-aarch64-0.12.0.xz\n";
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64", V))
                .isEqualTo("jk-linux-x86_64-0.12.0.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64", V))
                .isEqualTo("jk-windows-x86_64-0.12.0.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "macos", "aarch64", V))
                .isEqualTo("jk-macos-aarch64-0.12.0.xz");
    }

    @Test
    void windows_falls_back_to_zip_when_sums_have_no_xz() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-windows-x86_64-0.12.0.zip\n";
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64", V))
                .isEqualTo("jk-windows-x86_64-0.12.0.zip");
    }

    /**
     * The rollback shape: a valid, signed manifest from an older release served under a newer
     * version's directory. Its artifact names carry the older version, so a request for the newer
     * one finds nothing to verify against and refuses.
     */
    @Test
    void a_manifest_from_another_release_satisfies_no_request_for_this_one() {
        String older = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-linux-x86_64-0.12.0.xz\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(older, "linux", "x86_64", "0.13.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64-0.13.0.xz");
    }

    @Test
    void the_jvm_client_is_the_platform_neutral_jar_and_only_when_the_release_ships_one() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-0.12.0.jar\n";
        assertThat(SelfCommand.UpdateSub.jvmClientArtifact(sums, V)).isEqualTo("jk-0.12.0.jar");
        String without = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-linux-x86_64-0.12.0.xz\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.jvmClientArtifact(without, V))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-0.12.0.jar")
                .hasMessageContaining("no JVM client");
    }

    @Test
    void the_path_client_is_the_exe_then_the_bat_launcher_then_the_binary(@TempDir Path tmp) throws Exception {
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk"));
        Files.writeString(tmp.resolve("jk.bat"), "@echo off\r\n");
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk.bat"));
        Files.writeString(tmp.resolve("jk.exe"), "MZ");
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk.exe"));
    }

    @Test
    void ingest_client_reclaims_the_inflated_temp_binary(@TempDir Path tmp) throws Exception {
        // Cas.putFile copies (temp + atomic move) — without the delete, every successful
        // jk self update strands one native-binary-sized jk-self-*.bin in the system temp dir.
        Path client = Files.createTempFile(tmp, "jk-self-", ".bin");
        Files.write(client, new byte[] {1, 2, 3, 4});
        Cas cas = new Cas(tmp.resolve("cas"));
        String sha = SelfCommand.UpdateSub.ingestClient(cas, client);
        assertThat(cas.contains(sha)).isTrue();
        assertThat(client).doesNotExist();
    }

    @Test
    void the_latest_pointer_must_verify_and_must_not_roll_back_below_the_running_version() throws Exception {
        KeyPair pair = rsaPair();
        ReleaseVerifier verifier = ReleaseVerifier.of(List.of(spki(pair)));
        byte[] newer = pointer("0.14.0");
        byte[] same = pointer("0.13.3");
        byte[] older = pointer("0.13.0");

        assertThat(SelfCommand.UpdateSub.latestVersion(verifier, newer, sign(pair, newer), "0.13.3"))
                .isEqualTo("0.14.0");
        assertThat(SelfCommand.UpdateSub.latestVersion(verifier, same, sign(pair, same), "0.13.3"))
                .isEqualTo("0.13.3");

        // Valid signature, older release: the rollback shape a bucket writer or a mirror can stage.
        assertThatThrownBy(() -> SelfCommand.UpdateSub.latestVersion(verifier, older, sign(pair, older), "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING")
                .hasMessageContaining("0.13.0");
        // Tampered after signing.
        assertThatThrownBy(() -> SelfCommand.UpdateSub.latestVersion(verifier, newer, sign(pair, older), "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");
        // Signed by a key this jk does not trust.
        byte[] foreign = sign(rsaPair(), newer);
        assertThatThrownBy(() -> SelfCommand.UpdateSub.latestVersion(verifier, newer, foreign, "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSING");
        // Signed, but not a pointer.
        byte[] bare = "0.14.0\n".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> SelfCommand.UpdateSub.latestVersion(verifier, bare, sign(pair, bare), "0.13.3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("malformed");
    }

    private static byte[] pointer(String version) {
        return ("version " + version + "\nissued 1757700000\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sign(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return (Base64.getEncoder().encodeToString(signer.sign()) + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static String spki(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        return generator.generateKeyPair();
    }

    @Test
    void unix_does_not_fall_back_to_zip() {
        String sums = HASH + "  jk-linux-x86_64-0.12.0.zip\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64", V))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64-0.12.0.xz");
    }
}
