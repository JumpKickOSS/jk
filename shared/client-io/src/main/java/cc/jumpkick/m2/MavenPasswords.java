// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Maven 3's encrypted {@code settings.xml} passwords, read so a Maven user's working file keeps
 * working. This is compatibility, not protection: the master password that unlocks every value
 * sits in {@code settings-security.xml} beside them, under a passphrase that is the same on every
 * machine.
 *
 * <p>The format is plexus-cipher's, as {@code mvn --encrypt-master-password} and {@code mvn
 * --encrypt-password} write it. A value is encrypted when it holds a {@code {...}} whose braces are
 * not backslash-escaped; text outside the braces is a comment and is ignored. The braces hold
 * base64 of {@code salt[8] || padLen[1] || ciphertext || padding[padLen]}. The key and IV are the
 * two halves of {@code SHA-256(UTF-8 passphrase || salt)}, and the cipher is {@code
 * AES/CBC/PKCS5Padding}. A server password's passphrase is the master password; the master
 * password's own passphrase is the fixed string {@code settings.security}.
 *
 * <p>Maven 4's {@code mvnenc} writes a different format ({@code {[name=master,cipher=…]…}} with
 * the master password from an external source); {@link MvnencPasswords} reads it.
 */
final class MavenPasswords {

    private MavenPasswords() {}

    /** The passphrase the master password itself is encrypted under — plexus-sec-dispatcher's. */
    static final String MASTER_PASSPHRASE = "settings.security";

    /** The environment variable naming the security file: Maven's {@code -Dsettings.security} for jk. */
    static final String SECURITY_ENV = "JK_M2_SETTINGS_SECURITY";

    /** Maven's own pattern for "this value is encrypted": the first unescaped, non-empty {@code {...}}. */
    private static final Pattern ENCRYPTED = Pattern.compile(".*?[^\\\\]?\\{(.*?[^\\\\])\\}.*");

    private static final int SALT = 8;

    /** How many {@code <relocation>} hops are followed before the chain is taken to be a loop. */
    private static final int MAX_RELOCATIONS = 8;

    /** Why a value could not be decrypted, in words a warning can carry; never the value. */
    static final class Undecryptable extends Exception {

        Undecryptable(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The text inside the braces of an encrypted value; null when {@code value} is not encrypted. */
    static @Nullable String payload(String value) {
        Matcher m = ENCRYPTED.matcher(value);
        return m.matches() || m.find() ? m.group(1) : null;
    }

    /** {@code payload} (the inside of the braces) decrypted under {@code passphrase}. */
    static String decrypt(String payload, String passphrase) throws Undecryptable {
        if (payload.startsWith("[") && payload.indexOf(']') > 0) {
            String attributes = payload.substring(1, payload.indexOf(']'));
            if (attributes.contains("type=")) {
                throw new Undecryptable("decryptor " + attributes + " needs a Maven plugin");
            }
            if (attributes.contains("name=") || attributes.contains("cipher=")) {
                throw new Undecryptable("text outside the {...} braces; keep only what mvnenc wrote");
            }
        }
        byte[] all;
        try {
            all = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException notBase64) {
            throw new Undecryptable("it is not base64");
        }
        if (all.length <= SALT + 1) throw new Undecryptable("it is too short to be an encrypted value");
        int padding = all[SALT];
        int end = all.length - padding;
        if (padding < 0 || end <= SALT + 1) throw new Undecryptable("its padding length is out of range");
        byte[] salt = Arrays.copyOf(all, SALT);
        byte[] ciphertext = Arrays.copyOfRange(all, SALT + 1, end);
        try {
            MessageDigest sha = Hashing.newSha256();
            sha.update(passphrase.getBytes(StandardCharsets.UTF_8));
            sha.update(salt);
            byte[] keyAndIv = sha.digest();
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyAndIv, 0, 16, "AES"),
                    new IvParameterSpec(keyAndIv, 16, 16));
            return new String(aes.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException wrongKey) {
            throw new Undecryptable("it does not decrypt with that key");
        }
    }

    /**
     * The master password as {@code settings-security.xml} unlocks it: Maven decrypts the braces
     * when there are some and the whole value when there are none.
     */
    static String master(String written) throws Undecryptable {
        String inside = payload(written);
        return decrypt(inside != null ? inside : written, MASTER_PASSPHRASE);
    }

    /**
     * The {@code <master>} of the security file at {@code location}, following {@code
     * <relocation>}: a relative relocation is taken against the directory of the file that names
     * it, and {@code ~/} is {@code home}. {@code read} records every file the chain touched.
     */
    static String readMaster(Path location, Path home, Set<Path> read) throws Undecryptable {
        Path file = location;
        Set<Path> seen = new LinkedHashSet<>();
        for (int hop = 0; hop <= MAX_RELOCATIONS; hop++) {
            Path normal = file.toAbsolutePath().normalize();
            read.add(normal);
            if (!seen.add(normal)) throw new Undecryptable("the <relocation> chain from " + location + " loops");
            if (!Files.isRegularFile(normal)) throw new Undecryptable("there is no " + normal);
            MinimalXml.Element root;
            try {
                root = MinimalXml.parse(Files.readString(normal));
            } catch (IOException | RuntimeException unreadable) {
                throw new Undecryptable(normal + " does not parse");
            }
            String relocation = text(root, "relocation");
            if (relocation != null) {
                file = resolve(normal.getParent(), relocation, home, "the <relocation> in " + normal);
                continue;
            }
            String master = text(root, "master");
            if (master == null) throw new Undecryptable(normal + " has no <master>");
            try {
                return master(master);
            } catch (Undecryptable e) {
                throw new Undecryptable("the <master> in " + normal + " does not decrypt");
            }
        }
        throw new Undecryptable("the <relocation> chain from " + location + " is longer than " + MAX_RELOCATIONS);
    }

    /**
     * {@code written} as a path: {@code ~/} under {@code home}, a relative one against {@code dir}.
     * {@code what} names where it was written, for the reason it is not a path.
     */
    static Path resolve(@Nullable Path dir, String written, Path home, String what) throws Undecryptable {
        try {
            if (written.equals("~")) return home;
            if (written.startsWith("~/") || written.startsWith("~\\")) return home.resolve(written.substring(2));
            Path path = Path.of(written);
            if (path.isAbsolute() || dir == null) return path;
            return dir.resolve(path);
        } catch (InvalidPathException notAPath) {
            throw new Undecryptable(what + " is not a path");
        }
    }

    private static @Nullable String text(MinimalXml.Element root, String tag) {
        return root.element(tag)
                .map(MinimalXml.Element::text)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }
}
