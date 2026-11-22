// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.sigstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jsonl.Jsonl;
import dev.sigstore.KeylessSignerException;
import dev.sigstore.bundle.Bundle;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeylessSigstoreSignerTest {

    @Test
    void the_signer_is_handed_the_artifacts_sha256_and_the_bundle_verifies_against_the_artifact(@TempDir Path dir)
            throws Exception {
        byte[] content = new byte[200_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
        Path artifact = Files.write(dir.resolve("lib-1.0.jar"), content);
        StubSigner stub = new StubSigner();

        byte[] json = new KeylessSigstoreSigner(stub, () -> {}).signBundle(artifact);

        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(content);
        assertThat(stub.handed).containsExactly(sha256);
        Bundle bundle = Bundle.from(new StringReader(new String(json, StandardCharsets.UTF_8)));
        Bundle.MessageSignature message = bundle.getMessageSignature().orElseThrow();
        assertThat(message.getMessageDigest().orElseThrow().getDigest()).isEqualTo(sha256);
        X509Certificate leaf =
                (X509Certificate) bundle.getCertPath().getCertificates().getFirst();
        Signature verify = Signature.getInstance("SHA256withECDSA");
        verify.initVerify(leaf.getPublicKey());
        verify.update(content);
        assertThat(verify.verify(message.getSignature())).isTrue();
    }

    @Test
    void an_attestation_is_an_envelope_over_the_statement_whose_signature_the_bundle_certifies() throws Exception {
        byte[] statement = "{\"_type\":\"https://in-toto.io/Statement/v1\"}\n".getBytes(StandardCharsets.UTF_8);
        StubSigner stub = new StubSigner();

        KeylessSigstoreSigner.Attestation attestation = new KeylessSigstoreSigner(stub, () -> {}).attest(statement);

        String line = new String(attestation.envelope(), StandardCharsets.UTF_8);
        assertThat(line).endsWith("}\n").doesNotContain("\n{");
        assertThat(Jsonl.str(line, "payloadType")).isEqualTo(Dsse.IN_TOTO);
        assertThat(Base64.getDecoder().decode(Jsonl.requiredStr(line, "payload")))
                .isEqualTo(statement);
        String signature = Jsonl.objectArray(line, "signatures").getFirst();
        assertThat(Jsonl.has(signature, "keyid")).isFalse();
        byte[] sig = Base64.getDecoder().decode(Jsonl.requiredStr(signature, "sig"));

        byte[] pae = Dsse.pae(Dsse.IN_TOTO, statement);
        assertThat(stub.handed)
                .containsExactly(MessageDigest.getInstance("SHA-256").digest(pae));
        Bundle bundle = Bundle.from(new StringReader(new String(attestation.bundle(), StandardCharsets.UTF_8)));
        assertThat(bundle.getMessageSignature().orElseThrow().getSignature()).isEqualTo(sig);
        Signature verify = Signature.getInstance("SHA256withECDSA");
        verify.initVerify(bundle.getCertPath().getCertificates().getFirst().getPublicKey());
        verify.update(pae);
        assertThat(verify.verify(sig)).isTrue();
    }

    @Test
    void a_failed_signing_surfaces_as_an_io_exception(@TempDir Path dir) throws Exception {
        Path artifact = Files.writeString(dir.resolve("lib-1.0.jar"), "jar");
        KeylessSigstoreSigner signer = new KeylessSigstoreSigner(
                digest -> {
                    throw new KeylessSignerException("no OIDC token");
                },
                () -> {});

        assertThatThrownBy(() -> signer.signBundle(artifact))
                .isInstanceOf(IOException.class)
                .hasMessage("Sigstore signing failed: no OIDC token");
    }

    @Test
    void closing_closes_the_underlying_signer() {
        AtomicBoolean closed = new AtomicBoolean();
        new KeylessSigstoreSigner(new StubSigner(), () -> closed.set(true)).close();
        assertThat(closed).isTrue();
    }
}
