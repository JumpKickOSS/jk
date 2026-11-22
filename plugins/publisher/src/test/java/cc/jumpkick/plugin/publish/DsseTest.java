// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import cc.jumpkick.sigstore.Dsse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DsseTest {

    /** The worked example of the DSSE protocol document. */
    @Test
    void the_pre_authentication_encoding_is_the_specs() {
        byte[] pae = Dsse.pae("http://example.com/HelloWorld", "hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(pae, StandardCharsets.UTF_8))
                .isEqualTo("DSSEv1 29 http://example.com/HelloWorld 11 hello world");
    }

    @Test
    void lengths_count_bytes_not_characters() {
        byte[] pae = Dsse.pae("t", "é".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(pae, StandardCharsets.UTF_8)).isEqualTo("DSSEv1 1 t 2 é");
    }

    @Test
    void an_envelope_carries_the_statement_and_verifies_against_the_key(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "pass".toCharArray());
        byte[] statement = "{\"_type\":\"https://in-toto.io/Statement/v1\"}\n".getBytes(StandardCharsets.UTF_8);

        byte[] envelope = signer.dsse(statement);

        String line = new String(envelope, StandardCharsets.UTF_8);
        assertThat(line).endsWith("}\n").doesNotContain("\n{");
        assertThat(Jsonl.str(line, "payloadType")).isEqualTo("application/vnd.in-toto+json");
        assertThat(GpgTestFixture.verifyEnvelope(envelope, key.publicRing())).isEqualTo(statement);
    }

    @Test
    void an_envelope_whose_payload_was_changed_does_not_verify(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "pass".toCharArray());
        String line = new String(signer.dsse("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        String forged = Base64.getEncoder().encodeToString("{\"a\":2}".getBytes(StandardCharsets.UTF_8));

        byte[] tampered =
                line.replace(Jsonl.requiredStr(line, "payload"), forged).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> GpgTestFixture.verifyEnvelope(tampered, key.publicRing()))
                .hasMessageContaining("did not verify");
    }

    @Test
    void two_signings_of_one_statement_carry_the_same_payload(@TempDir Path dir) throws Exception {
        GpgSigner first = GpgSigner.fromKeyFile(
                GpgTestFixture.generate(Files.createDirectories(dir.resolve("a")), "pass")
                        .secretKeyFile(),
                "pass".toCharArray());
        GpgSigner second = GpgSigner.fromKeyFile(
                GpgTestFixture.generate(Files.createDirectories(dir.resolve("b")), "pass")
                        .secretKeyFile(),
                "pass".toCharArray());
        byte[] statement = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(Dsse.samePayload(first.dsse(statement), second.dsse(statement)))
                .isTrue();
        assertThat(Dsse.samePayload(first.dsse(statement), first.dsse("{\"a\":2}".getBytes(StandardCharsets.UTF_8))))
                .isFalse();
        assertThat(Dsse.samePayload("not json".getBytes(StandardCharsets.UTF_8), first.dsse(statement)))
                .isFalse();
    }
}
