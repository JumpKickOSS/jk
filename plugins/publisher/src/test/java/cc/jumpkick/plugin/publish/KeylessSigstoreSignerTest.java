// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static dev.sigstore.AlgorithmRegistry.HashAlgorithm.SHA2_256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sigstore.KeylessSignerException;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.ImmutableBundle;
import dev.sigstore.rekor.client.HashedRekordRequest;
import dev.sigstore.rekor.client.ImmutableInclusionProof;
import dev.sigstore.rekor.client.ImmutableRekorEntry;
import dev.sigstore.rekor.client.ImmutableVerification;
import dev.sigstore.rekor.client.RekorEntry;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
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

    /**
     * Does what sigstore-java's keyless signer does with what it is handed: treats it as a SHA-256
     * digest, signs it as one with a short-lived key and certifies that key. Without Fulcio and
     * Rekor the certificate is self-signed and the log entry's proofs are placeholders.
     */
    private static final class StubSigner implements KeylessSigstoreSigner.DigestSigner {

        final List<byte[]> handed = new ArrayList<>();

        @Override
        public Bundle sign(byte[] sha256) throws KeylessSignerException {
            handed.add(sha256.clone());
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(256);
                KeyPair key = generator.generateKeyPair();
                Signature ecdsa = Signature.getInstance("NONEwithECDSA");
                ecdsa.initSign(key.getPrivate());
                ecdsa.update(sha256);
                byte[] signature = ecdsa.sign();
                CertPath certPath = certify(key);
                return ImmutableBundle.builder()
                        .certPath(certPath)
                        .messageSignature(Bundle.MessageSignature.of(SHA2_256, sha256, signature))
                        .addEntries(logEntry(sha256, certPath, signature))
                        .build();
            } catch (Exception e) {
                throw new KeylessSignerException("stub signing failed", e);
            }
        }

        /** The entry Rekor would log for this signature; its proofs are placeholders. */
        private static RekorEntry logEntry(byte[] sha256, CertPath certPath, byte[] signature) throws Exception {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(
                                    certPath.getCertificates().getFirst().getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
            String body = HashedRekordRequest.newHashedRekordRequest(
                            sha256, SHA2_256, pem.getBytes(StandardCharsets.UTF_8), signature)
                    .toJsonPayload();
            String zeros = "0".repeat(64);
            return ImmutableRekorEntry.builder()
                    .body(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                    .integratedTime(Instant.now().getEpochSecond())
                    .logID(zeros)
                    .logIndex(1)
                    .verification(ImmutableVerification.builder()
                            .signedEntryTimestamp(Base64.getEncoder().encodeToString(new byte[] {0}))
                            .inclusionProof(ImmutableInclusionProof.builder()
                                    .logIndex(1L)
                                    .rootHash(zeros)
                                    .treeSize(2L)
                                    .addHashes(zeros)
                                    .checkpoint("stub\n2\n" + zeros + "\n")
                                    .build())
                            .build())
                    .build();
        }

        private static CertPath certify(KeyPair key) throws Exception {
            X500Name subject = new X500Name("CN=sigstore-stub");
            Instant now = Instant.now();
            var holder = new JcaX509v3CertificateBuilder(
                            subject,
                            BigInteger.ONE,
                            Date.from(now),
                            Date.from(now.plus(Duration.ofMinutes(10))),
                            subject,
                            key.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate()));
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
            return CertificateFactory.getInstance("X.509").generateCertPath(List.of(cert));
        }
    }
}
