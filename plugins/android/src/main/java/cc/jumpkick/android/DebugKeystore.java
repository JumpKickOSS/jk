// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The debug signing identity: ONE keystore, generated once at a stable location and reused by every
 * later build.
 *
 * <p>Android refuses {@code adb install -r} when the update is signed by a different key than the
 * installed app ({@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}). A keystore generated per run
 * therefore breaks the whole device inner loop: every rebuild forces an uninstall first, and the
 * app's data goes with it. So the rule here is generate-once-never-again — {@link #ensure}
 * regenerates nothing it finds, and that is the behaviour, not an optimization.
 *
 * <p>The location and credentials are the ones the Android ecosystem publishes
 * ({@code ~/.android/debug.keystore}, alias {@code androiddebugkey}, password {@code android}), so
 * a jk-built debug APK updates a Studio/AGP-built one in place and vice versa. The password is a
 * documented constant of the platform's debug identity, not a secret.
 *
 * <p>Because the signature is part of the artifact, the keystore is also a declared packager input
 * ({@link Signing#keystoreInput}): a rotated key at the same path re-signs instead of restoring an
 * artifact carrying the old signature.
 */
final class DebugKeystore {

    /** The alias every Android debug build signs under — aapt2, Studio, AGP and jk agree on it. */
    static final String ALIAS = "androiddebugkey";

    /** The published debug store/key password. Documented platform constant, not a secret. */
    static final String PASSWORD = "android";

    private static final String FILE_NAME = "debug.keystore";

    private DebugKeystore() {}

    /**
     * Where the debug identity lives: {@code $ANDROID_USER_HOME}, else {@code $ANDROID_SDK_HOME}'s
     * {@code .android}, else {@code ~/.android} — the lookup order the Android tools themselves use.
     */
    static Path stableDir() {
        return stableDir(System::getenv, System.getProperty("user.home", ""));
    }

    /** Seam: the same rule over an explicit environment. */
    static Path stableDir(Function<String, @Nullable String> env, String userHome) {
        String androidUserHome = env.apply("ANDROID_USER_HOME");
        if (androidUserHome != null && !androidUserHome.isBlank()) return Path.of(androidUserHome);
        String legacySdkHome = env.apply("ANDROID_SDK_HOME");
        if (legacySdkHome != null && !legacySdkHome.isBlank()) return Path.of(legacySdkHome, ".android");
        return Path.of(userHome, ".android");
    }

    /** The keystore path under {@code dir} — resolvable without generating anything. */
    static Path path(Path dir) {
        return dir.resolve(FILE_NAME);
    }

    /**
     * The debug keystore under {@code dir}, generated with {@code keytool} on first use only. An
     * existing file is handed back untouched: that is what keeps one machine's debug signature
     * stable across rebuilds.
     */
    static Path ensure(Path dir, Path javaHome) throws IOException, InterruptedException {
        Path keystore = path(dir);
        if (Files.isRegularFile(keystore)) return keystore;
        Files.createDirectories(dir);
        // Generate into a sibling, then rename into place: a concurrent build must never load a
        // half-written store, and if it wins the race both runs go on signing with its keystore
        // rather than one silently replacing the other's identity mid-flight.
        Path staging = Files.createTempFile(dir, ".debug-keystore-", ".tmp");
        Files.delete(staging); // keytool creates the store itself and refuses an existing empty file
        try {
            run(javaHome, staging);
            try {
                Files.move(staging, keystore);
            } catch (FileAlreadyExistsException raceLost) {
                // Another build generated it first — sign with theirs, not with a second identity.
                Files.deleteIfExists(staging);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        return keystore;
    }

    /**
     * The generation args after the tool itself, readable by a test: no password appears in them.
     * The {@code keytool} path is not spelled here — {@link TaskExec.ToolRun} resolves it off the
     * build's JDK and owns the Windows {@code .exe} shape.
     */
    static List<String> genKeypairArgs(Path keystore, Signing.PasswordFile pass) {
        return List.of(
                "-genkeypair",
                "-keystore",
                keystore.toAbsolutePath().toString(),
                "-storepass:file",
                pass.arg(),
                "-keypass:file",
                pass.arg(),
                "-alias",
                ALIAS,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "10000",
                "-dname",
                "CN=Android Debug,O=Android,C=US");
    }

    /**
     * The one {@code keytool -genkeypair} invocation in the plugin: 2048-bit RSA, ~27 years of
     * validity and the standard debug distinguished name. Two copies of this argv had drifted apart
     * across the packagers and the deploy command.
     *
     * <p>{@link #PASSWORD} is published, so putting it on argv leaked nothing — but it takes the
     * same {@link Signing#passwordFile} route the release passwords take, so there is one shape to
     * read and no example of the risky spelling left in the plugin to copy.
     */
    private static void run(Path javaHome, Path keystore) throws IOException, InterruptedException {
        try (Signing.PasswordFile pass = Signing.passwordFile(PASSWORD)) {
            var result = new TaskExec.ToolRun(javaHome, "keytool")
                    .args(genKeypairArgs(keystore, pass))
                    .run();
            if (result.exit() != 0) {
                throw new IOException("keytool failed to generate the debug keystore:\n" + result.output());
            }
        }
    }
}
