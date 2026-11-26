// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.ProtocolWriter;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The release lane's signing op: a release's SHA256SUMS signed beside itself with the PGP key. */
class PublisherSignReleaseTest {

    @Test
    void sha256sums_gets_a_detached_signature_beside_it(@TempDir Path dir) throws Exception {
        GpgTestFixture.GeneratedKey key = GpgTestFixture.generate(dir, "pw");
        Path sums = Files.writeString(dir.resolve("SHA256SUMS"), "a".repeat(64) + "  app-1.0-all.jar\n");
        Path spec = Files.write(
                dir.resolve("sign.spec"),
                new SpecWriter()
                        .op(PluginProtocol.OP_SIGN_RELEASE, null, "jk-publisher")
                        .configBool("signGpg", true)
                        .configString("gpgKeyFile", key.secretKeyFile().toString())
                        .secret("gpgPassphrase", "pw")
                        .configBool("signSigstore", false)
                        .artifact(sums)
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new Publisher().run(List.of(spec.toString()), out);

        assertThat(exit).isZero();
        Path asc = dir.resolve("SHA256SUMS.asc");
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("\"written\":[\"" + asc + "\"]");
        GpgTestFixture.verifyDetached(Files.readAllBytes(sums), Files.readAllBytes(asc), key.publicRing());
        assertThat(dir.resolve("SHA256SUMS.sigstore.json")).doesNotExist();
    }
}
