// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.host.time.Clock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.jspecify.annotations.Nullable;

/**
 * Detached ASCII-armored GPG signatures ({@code .asc}) via BouncyCastle — no system {@code gpg}.
 * The key that signs is the newest one in the secret-key file that may: its secret is present, it
 * is neither revoked nor expired (nor is its primary), and its self-signature flags it for signing.
 * A key whose self-signature carries no usage flags at all is taken as a signing key, after every
 * key that is flagged.
 */
public final class GpgSigner {

    private final PGPPrivateKey privateKey;
    private final PGPPublicKey publicKey;

    private GpgSigner(PGPPrivateKey privateKey, PGPPublicKey publicKey) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    /** A key that may sign, and whether its self-signature says so rather than leaving usage open. */
    private record Candidate(PGPSecretKey key, boolean flagged) {}

    /** Load a signer from a secret-key file (armored or binary). */
    public static GpgSigner fromKeyFile(Path keyFile, char[] passphrase) throws IOException {
        Objects.requireNonNull(keyFile, "keyFile");
        Instant now = Clock.SYSTEM.instant();
        List<String> passedOver = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(keyFile);
                InputStream decoded = PGPUtil.getDecoderStream(raw)) {
            PGPSecretKeyRingCollection rings =
                    new PGPSecretKeyRingCollection(decoded, new JcaKeyFingerprintCalculator());
            List<Candidate> candidates = new ArrayList<>();
            for (Iterator<PGPSecretKeyRing> rIter = rings.getKeyRings(); rIter.hasNext(); ) {
                PGPSecretKeyRing ring = rIter.next();
                PGPPublicKey primary = ring.getPublicKey();
                for (Iterator<PGPSecretKey> kIter = ring.getSecretKeys(); kIter.hasNext(); ) {
                    PGPSecretKey key = kIter.next();
                    int flags = keyFlags(key.getPublicKey(), primary.getKeyID());
                    String problem = problem(key, primary, flags, now);
                    if (problem != null) {
                        passedOver.add(describe(key, problem));
                    } else {
                        candidates.add(new Candidate(key, flags >= 0));
                    }
                }
            }
            candidates.sort(Comparator.comparing(Candidate::flagged)
                    .thenComparing(c -> c.key().getPublicKey().getCreationTime())
                    .reversed());
            for (Candidate c : candidates) {
                PGPPrivateKey priv = c.key()
                        .extractPrivateKey(new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
                                .build(passphrase == null ? new char[0] : passphrase));
                if (priv != null) return new GpgSigner(priv, c.key().getPublicKey());
                passedOver.add(describe(c.key(), "has no secret material"));
            }
        } catch (PGPException e) {
            throw new IOException("failed to load GPG key from " + keyFile + ": " + e.getMessage(), e);
        }
        StringBuilder message = new StringBuilder("no usable signing key in " + keyFile);
        for (String line : passedOver) message.append("\n  ").append(line);
        throw new IOException(message.toString());
    }

    /** Why {@code key} may not sign, or null when it may. */
    private static @Nullable String problem(PGPSecretKey key, PGPPublicKey primary, int flags, Instant now) {
        PGPPublicKey pub = key.getPublicKey();
        if (!key.isSigningKey()) return "has an algorithm that cannot sign";
        if (flags >= 0 && (flags & KeyFlags.SIGN_DATA) == 0) return "is not flagged for signing";
        if (pub.hasRevocation()) return "is revoked";
        if (!pub.isMasterKey() && primary.hasRevocation()) return "belongs to a revoked primary key";
        if (expired(pub, now)) return "expired";
        if (!pub.isMasterKey() && expired(primary, now)) return "belongs to an expired primary key";
        if (key.isPrivateKeyEmpty()) {
            return "has no secret material (a primary kept offline, or a key on a card)";
        }
        return null;
    }

    private static boolean expired(PGPPublicKey key, Instant now) {
        long valid = key.getValidSeconds();
        return valid > 0
                && !key.getCreationTime().toInstant().plusSeconds(valid).isAfter(now);
    }

    /**
     * The usage flags on the newest self-signature that states any: the user-id certifications or
     * direct-key signature for a primary, the binding signature for a subkey. -1 when none does.
     */
    private static int keyFlags(PGPPublicKey key, long primaryId) {
        PGPSignature newest = null;
        for (Iterator<PGPSignature> it = key.getSignatures(); it.hasNext(); ) {
            PGPSignature sig = it.next();
            if (sig.getKeyID() != primaryId || !SELF_SIGNATURES.contains(sig.getSignatureType())) continue;
            PGPSignatureSubpacketVector hashed = sig.getHashedSubPackets();
            if (hashed == null || !hashed.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) continue;
            if (newest == null || sig.getCreationTime().after(newest.getCreationTime())) newest = sig;
        }
        return newest == null ? -1 : newest.getHashedSubPackets().getKeyFlags();
    }

    private static final Set<Integer> SELF_SIGNATURES = Set.of(
            PGPSignature.DEFAULT_CERTIFICATION,
            PGPSignature.NO_CERTIFICATION,
            PGPSignature.CASUAL_CERTIFICATION,
            PGPSignature.POSITIVE_CERTIFICATION,
            PGPSignature.DIRECT_KEY,
            PGPSignature.SUBKEY_BINDING);

    private static String describe(PGPSecretKey key, String problem) {
        return (key.isMasterKey() ? "primary " : "subkey ") + String.format(Locale.ROOT, "%016X", key.getKeyID()) + " "
                + problem;
    }

    /**
     * For programmatic use (e.g. tests): wrap an already-extracted {@link PGPPrivateKey} together
     * with its matching {@link PGPPublicKey}.
     */
    public static GpgSigner of(PGPPrivateKey privateKey, PGPPublicKey publicKey) {
        return new GpgSigner(privateKey, publicKey);
    }

    /**
     * Produce a detached, ASCII-armored signature over {@code file}, read through a fixed buffer. The
     * returned bytes are what gets uploaded as the {@code .asc} file.
     */
    public byte[] signArmored(Path file) throws IOException {
        try {
            PGPSignatureGenerator gen = new PGPSignatureGenerator(
                    new BcPGPContentSignerBuilder(publicKey.getAlgorithm(), HashAlgorithmTags.SHA256), publicKey);
            gen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) gen.update(buf, 0, n);
            }
            PGPSignature sig = gen.generate();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ArmoredOutputStream aos = new ArmoredOutputStream(baos)) {
                sig.encode(aos);
            }
            // RFC 4880 ASCII armor is LF-terminated; BouncyCastle may emit CRLF on Windows.
            return lfOnly(baos.toByteArray());
        } catch (PGPException e) {
            throw new IOException("GPG signing failed: " + e.getMessage(), e);
        }
    }

    /** Drop CR so {@code .asc} bytes are identical across hosts. */
    static byte[] lfOnly(byte[] armored) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(armored.length);
        for (byte b : armored) {
            if (b != '\r') out.write(b);
        }
        return out.toByteArray();
    }
}
