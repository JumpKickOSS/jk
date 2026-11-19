// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret a suite's environment reads are keyed under ({@code SuiteEnvReads}), and the copies of
 * an issued key seed are named under ({@link CacheSeeds}): 32 random bytes
 * made once per jk home, kept in its state directory readable by the owner alone. A key carries an
 * HMAC of each value under it rather than a plain digest, so a key that leaves this host gives no
 * way to test guesses at a short secret it hashes. A home whose secret is lost or replaced keys
 * every such suite anew: it runs once more, never takes a wrong result.
 */
public final class EnvReadKey {

    private static final String FILE = "env-read.key";

    private final byte[] secret;

    private EnvReadKey(byte[] secret) {
        this.secret = secret;
    }

    /**
     * This jk home's key, read from its state directory, or made and kept there when there is none.
     * Read again for each key taken: a home is free to change under a resident engine.
     */
    public static EnvReadKey load() {
        return new EnvReadKey(readOrMake(JkDirs.state().resolve(FILE)));
    }

    /** The HMAC-SHA256 of {@code text} under this key, hex. */
    public String hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Hashing.hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }

    private static synchronized byte[] readOrMake(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                byte[] read = Hashing.unhex(
                        Files.readString(file, StandardCharsets.UTF_8).strip());
                if (read.length >= 32) return read;
            }
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("env read key unreadable; making a new one", unreadable);
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try {
            Path dir = file.toAbsolutePath().getParent();
            OwnerOnlyFiles.write(dir == null ? file : dir, file, Hashing.hex(secret) + "\n");
        } catch (IOException unwritable) {
            // This key alone keys with it; the next one is made anew, and its suites run once more.
            Log.debug("env read key not kept", unwritable);
        }
        return secret;
    }
}
