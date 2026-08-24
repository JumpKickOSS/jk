// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Os;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageIo;
import com.android.apksig.ApkSigner;
import com.android.apksig.KeyConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Android signing: debug ({@link DebugKeystore}, v1+v2) or release from {@code
 * [android.signing.*]} (passwords are secrets on the package side channel, never logs).
 */
final class Signing {

    private Signing() {}

    /** A loaded signer identity + which signature schemes to apply. */
    record Identity(PrivateKey key, List<X509Certificate> certs, String name, boolean v3) {}

    /** {@code 0600}, applied at creation so the value is never briefly world-readable on disk. */
    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /**
     * A password handed to a signer <em>out of band</em>: an owner-only temp file, deleted when the
     * run ends.
     *
     * <p>argv is public. {@code /proc/<pid>/cmdline} is world-readable on Linux and {@code ps} shows
     * the same bytes to every account on the box, for as long as the signer runs — so a release
     * keystore password on {@code jarsigner -storepass <pass>} is readable by anyone with a shell,
     * and by any process-listing agent that samples it. Every tool the plugin hands a password to
     * accepts a file instead: {@code jarsigner}/{@code keytool} take {@code -storepass:file <path>},
     * bundletool takes {@code --ks-pass=file:<path>}. All three read the file's <em>first line</em>,
     * so the value is written with no terminator.
     *
     * <p>The debug identity's password is a published Android constant ({@link
     * DebugKeystore#PASSWORD}) and leaks nothing on argv; it goes through here anyway so the plugin
     * has one spelling of "hand a signer a password" rather than a safe one and a risky one that
     * look alike at the call site.
     */
    static PasswordFile passwordFile(String password) throws IOException {
        // POSIX permissions are not a thing on Windows; the JDK's temp directory there is already
        // per-user, so create plainly rather than failing the sign.
        Path file = Os.isWindows()
                ? Files.createTempFile("jk-signing-", ".pass")
                : Files.createTempFile("jk-signing-", ".pass", OWNER_ONLY);
        Files.writeString(file, password, StandardCharsets.UTF_8);
        return new PasswordFile(file);
    }

    /** The file from {@link #passwordFile}; {@code close} removes it. */
    record PasswordFile(Path path) implements AutoCloseable {

        /** The argv value: an absolute path, never the password. */
        String arg() {
            return path.toAbsolutePath().toString();
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    /** True when the effective config carries a release signing reference. */
    static boolean hasReleaseConfig(PackageIo io) {
        return io.config().stringOpt("signing.store-file").isPresent();
    }

    /** The configured release identity ({@code [android.signing.<name>]}). */
    static Identity release(PackageIo io) throws Exception {
        Path storeFile = Path.of(io.config().string("signing.store-file"));
        if (!Files.isRegularFile(storeFile)) {
            throw new IllegalStateException("signing.store-file does not exist: " + storeFile);
        }
        String alias = io.config().string("signing.key-alias");
        char[] storePass = io.secret("signing.store-password").orElse("").toCharArray();
        char[] keyPass =
                io.secret("signing.key-password").map(String::toCharArray).orElse(storePass);
        KeyStore ks = KeyStore.getInstance(storeFile.toString().endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = Files.newInputStream(storeFile)) {
            ks.load(in, storePass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass);
        if (key == null) {
            throw new IllegalStateException("no key `" + alias + "` in " + storeFile.getFileName());
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (var cert : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) cert);
        }
        return new Identity(key, certs, alias, true);
    }

    /** The stable debug identity — generated on first use, then reused for every later build. */
    static Identity debug(PackageIo io) throws Exception {
        Path keystore = debugKeystore(io);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, DebugKeystore.PASSWORD.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(DebugKeystore.ALIAS, DebugKeystore.PASSWORD.toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (var cert : ks.getCertificateChain(DebugKeystore.ALIAS)) {
            certs.add((X509Certificate) cert);
        }
        return new Identity(key, certs, DebugKeystore.ALIAS, false);
    }

    /** The debug keystore file itself (jarsigner and bundletool's build-apks want the file). */
    static Path debugKeystore(PackageIo io) throws Exception {
        return DebugKeystore.ensure(DebugKeystore.stableDir(), io.javaHome());
    }

    /**
     * The keystore this build signs with, as a declared packager input, resolved at registration
     * time: the configured release store, else the stable debug one.
     *
     * <p>The signature is part of the artifact, so the keystore's <em>content</em> belongs in the
     * packager's action key. Keying only the configured path meant rotating a key in place shipped
     * an APK still carrying the old signature, restored from cache and reported as up-to-date.
     * {@code In.projectFiles} is the engine's content-fingerprinted file input; an absolute store
     * path (what {@code env:}-indirected release config produces) resolves to itself.
     */
    static In keystoreInput(PluginConfig config) {
        return In.projectFiles(config.stringOpt("signing.store-file")
                .orElseGet(() -> DebugKeystore.path(DebugKeystore.stableDir()).toString()));
    }

    /** apksig over {@code unsigned} → {@code out}: v1+v2 always, v3 for release identities. */
    static void sign(Identity identity, Path unsigned, Path out) throws Exception {
        // KeyConfig.Jca is the non-deprecated form (PrivateKey ctor is deprecated in apksig 8.x).
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                        identity.name(), new KeyConfig.Jca(identity.key()), identity.certs())
                .build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(out.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(identity.v3())
                .build()
                .sign();
    }
}
