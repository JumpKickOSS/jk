// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish.testkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.S2K;
import org.bouncycastle.bcpg.SecretKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

/**
 * Generates an ephemeral PGP keypair for tests: a small (1024-bit) RSA key bound to a test
 * identity, written as an armored secret-key file that {@code GpgSigner.fromKeyFile(Path, char[])}
 * can consume.
 */
public final class GpgTestFixture {

    private GpgTestFixture() {}

    public record GeneratedKey(Path secretKeyFile, char[] passphrase, PGPPublicKeyRing publicRing) {}

    public static GeneratedKey generate(Path dir, String passphrase) throws Exception {
        RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
        // 1024-bit is fast for tests; never use this size in production.
        rsa.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 1024, 12));
        AsymmetricCipherKeyPair pair = rsa.generateKeyPair();
        PGPKeyPair pgpPair =
                new BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, pair, new Date());

        var sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpPair,
                "jk-test <test@example.com>",
                sha1Calc,
                null,
                null,
                new BcPGPContentSignerBuilder(pgpPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .build(passphrase.toCharArray()));

        PGPSecretKeyRing secretRing = gen.generateSecretKeyRing();
        PGPPublicKeyRing publicRing = gen.generatePublicKeyRing();

        Path keyFile = dir.resolve("test-secret.asc");
        try (var out = Files.newOutputStream(keyFile);
                var armored = new ArmoredOutputStream(out)) {
            secretRing.encode(armored);
        }
        return new GeneratedKey(keyFile, passphrase.toCharArray(), publicRing);
    }

    /**
     * One subkey of {@link #generateWithSubkeys}: its usage flags ({@link KeyFlags}), when it was
     * made, how long after that it expires (0: never), and whether the primary has revoked it.
     */
    public record Subkey(int flags, Date created, long validSeconds, boolean revoked) {
        public static Subkey signing() {
            return new Subkey(KeyFlags.SIGN_DATA, new Date(), 0, false);
        }
    }

    /**
     * A ring laid out the way GnuPG writes one: a primary carrying {@code primaryFlags} on its
     * self-signature, and {@code subkeys} bound to it in order. With {@code offlinePrimary} the
     * primary's secret is replaced by the GNU dummy stub {@code gpg --export-secret-subkeys} writes,
     * so the file holds the primary's public half only.
     */
    public static GeneratedKey generateWithSubkeys(
            Path dir, String passphrase, int primaryFlags, boolean offlinePrimary, List<Subkey> subkeys)
            throws Exception {
        PGPKeyPair primary = rsaPair(new Date());
        var sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PGPSignatureSubpacketGenerator primaryPackets = new PGPSignatureSubpacketGenerator();
        primaryPackets.setKeyFlags(false, primaryFlags);
        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                primary,
                "jk-test <test@example.com>",
                sha1Calc,
                primaryPackets.generate(),
                null,
                new BcPGPContentSignerBuilder(primary.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .build(passphrase.toCharArray()));
        for (Subkey sub : subkeys) {
            PGPSignatureSubpacketGenerator packets = new PGPSignatureSubpacketGenerator();
            packets.setKeyFlags(false, sub.flags());
            if (sub.validSeconds() > 0) packets.setKeyExpirationTime(false, sub.validSeconds());
            gen.addSubKey(rsaPair(sub.created()), packets.generate(), null);
        }
        PGPSecretKeyRing secretRing = gen.generateSecretKeyRing();

        List<PGPSecretKey> keys = new ArrayList<>();
        secretRing.getSecretKeys().forEachRemaining(keys::add);
        for (int i = 0; i < subkeys.size(); i++) {
            if (!subkeys.get(i).revoked()) continue;
            PGPPublicKey subPub = keys.get(i + 1).getPublicKey();
            PGPSignatureGenerator revoker = new PGPSignatureGenerator(
                    new BcPGPContentSignerBuilder(primary.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                    primary.getPublicKey());
            revoker.init(PGPSignature.SUBKEY_REVOCATION, primary.getPrivateKey());
            PGPSignature revocation = revoker.generateCertification(primary.getPublicKey(), subPub);
            PGPPublicKey revoked = PGPPublicKey.addCertification(subPub, revocation);
            secretRing = PGPSecretKeyRing.insertSecretKey(
                    secretRing, PGPSecretKey.replacePublicKey(keys.get(i + 1), revoked));
        }
        if (offlinePrimary) {
            PGPPublicKey primaryPub = secretRing.getPublicKey();
            SecretKeyPacket stub = new SecretKeyPacket(
                    primaryPub.getPublicKeyPacket(),
                    SymmetricKeyAlgorithmTags.NULL,
                    SecretKeyPacket.USAGE_CHECKSUM,
                    S2K.gnuDummyS2K(S2K.GNUDummyParams.noPrivateKey()),
                    null,
                    new byte[0]);
            secretRing = PGPSecretKeyRing.insertSecretKey(secretRing, new PGPSecretKey(stub, primaryPub));
        }

        Path keyFile = dir.resolve("test-secret.asc");
        try (var out = Files.newOutputStream(keyFile);
                var armored = new ArmoredOutputStream(out)) {
            secretRing.encode(armored);
        }
        List<PGPPublicKey> publics = new ArrayList<>();
        secretRing.getPublicKeys().forEachRemaining(publics::add);
        return new GeneratedKey(keyFile, passphrase.toCharArray(), new PGPPublicKeyRing(publics));
    }

    private static PGPKeyPair rsaPair(Date created) throws PGPException {
        RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
        rsa.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 1024, 12));
        return new BcPGPKeyPair(
                PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), created);
    }

    /** The id of the key that made {@code armoredSignature}. */
    public static long signerKeyId(byte[] armoredSignature) throws IOException {
        var decoded = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredSignature));
        Object obj = new PGPObjectFactory(decoded, new JcaKeyFingerprintCalculator()).nextObject();
        if (!(obj instanceof PGPSignatureList list)) {
            throw new IllegalStateException("expected PGPSignatureList, got " + obj);
        }
        return list.get(0).getKeyID();
    }

    public static void verifyDetached(byte[] data, byte[] armoredSignature, PGPPublicKeyRing publicRing)
            throws IOException, PGPException {
        var decoded = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredSignature));
        var fact = new PGPObjectFactory(decoded, new JcaKeyFingerprintCalculator());
        Object obj = fact.nextObject();
        if (!(obj instanceof PGPSignatureList list)) {
            throw new IllegalStateException("expected PGPSignatureList, got " + obj);
        }
        PGPSignature sig = list.get(0);
        sig.init(new BcPGPContentVerifierBuilderProvider(), publicRing.getPublicKey(sig.getKeyID()));
        sig.update(data);
        if (!sig.verify()) {
            throw new IllegalStateException("signature did not verify");
        }
    }
}
