// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.sigstore;

import static dev.sigstore.AlgorithmRegistry.HashAlgorithm.SHA2_256;

import dev.sigstore.KeylessSignerException;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.ImmutableBundle;
import dev.sigstore.rekor.client.HashedRekordRequest;
import dev.sigstore.rekor.client.ImmutableInclusionProof;
import dev.sigstore.rekor.client.ImmutableRekorEntry;
import dev.sigstore.rekor.client.ImmutableVerification;
import dev.sigstore.rekor.client.RekorEntry;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Does what sigstore-java's keyless signer does with what it is handed: treats it as a SHA-256
 * digest, signs it as one with a short-lived key and certifies that key. Without Fulcio and
 * Rekor the certificate is self-signed and the log entry's proofs are placeholders.
 */
public final class StubSigner implements KeylessSigstoreSigner.DigestSigner {

    /** Every digest handed to {@link #sign}, in order. */
    public final List<byte[]> handed = new ArrayList<>();

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
                        .encodeToString(certPath.getCertificates().getFirst().getEncoded())
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

    /** A keyless signer over this stub, with nothing to close. */
    public KeylessSigstoreSigner signer() {
        return new KeylessSigstoreSigner(this, () -> {});
    }
}
