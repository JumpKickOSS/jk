// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import com.android.apksig.ApkVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading a release signing identity, and applying it.
 *
 * <p>{@link SigningPasswordFileTest} covers how the password reaches an external signer and
 * {@link SigningInputTest} covers the keystore being a declared cache input. What was left over is
 * the part that decides whether an APK is signed <em>at all</em>, and with what: which schemes are
 * applied, and what happens when the configured store, alias or password is wrong. Every failure
 * here reaches a user holding a keystore, so the message has to name the thing that is wrong —
 * apksig's own error for a null key is a {@code NullPointerException}.
 */
class SigningTest {

    private static final String PASSWORD = DebugKeystore.PASSWORD;

    /** No {@code [android.signing.*]} reference means the debug identity, and nothing else. */
    @Test
    void a_config_with_no_store_file_is_not_a_release_build(@TempDir Path tmp) throws Exception {
        assertThat(Signing.hasReleaseConfig(AndroidIo.packager(tmp, "app.apk"))).isFalse();
    }

    @Test
    void a_config_naming_a_store_file_is_a_release_build(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);

        assertThat(Signing.hasReleaseConfig(io)).isTrue();
    }

    /** The identity is the alias's key and its whole chain — a truncated chain fails on device. */
    @Test
    void the_release_identity_is_the_configured_alias_and_its_certificate_chain(@TempDir Path tmp) throws Exception {
        Signing.Identity identity = Signing.release(release(tmp));

        assertThat(identity.name()).isEqualTo(DebugKeystore.ALIAS);
        assertThat(identity.key()).isNotNull();
        assertThat(identity.certs()).hasSize(1);
        assertThat(identity.certs().get(0).getSubjectX500Principal().getName()).contains("CN=Android Debug");
        assertThat(identity.v3())
                .as("a release identity is v3-capable; the debug one is not")
                .isTrue();
    }

    /** {@code key-password} defaults to {@code store-password} — the common single-password store. */
    @Test
    @SuppressWarnings("NullAway") // deliberate: a null secret is how the fixture models an omitted key
    void an_absent_key_password_falls_back_to_the_store_password(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        io.secret("signing.key-password", null); // the engine simply omits it

        assertThat(Signing.release(io).key()).isNotNull();
    }

    /** A store path that resolves to nothing is a configuration error, named as one. */
    @Test
    void a_missing_store_file_is_refused_by_path(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = AndroidIo.packager(tmp, "app.apk")
                .config("signing.store-file", tmp.resolve("nowhere.jks").toString())
                .config("signing.key-alias", "upload");

        assertThatThrownBy(() -> Signing.release(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store-file does not exist")
                .hasMessageContaining("nowhere.jks");
    }

    /**
     * An alias that is not in the store yields a null key from JCA, and apksig's answer to a null
     * key is a {@code NullPointerException}. The name and the file both have to appear instead.
     */
    @Test
    void an_alias_that_is_not_in_the_store_is_refused_by_name(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp).config("signing.key-alias", "not-in-here");

        assertThatThrownBy(() -> Signing.release(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-in-here")
                .hasMessageContaining("debug.keystore");
    }

    /**
     * A keystore in the older JKS format opens and yields its alias — the case a team migrating an
     * existing Gradle release key hits first.
     *
     * <p>{@code Signing.release} loads through the probing {@code KeyStore.getInstance(File,
     * char[])}, which detects the format from the store's content — so JKS support needs no type
     * string, no extension check, and no help from the JDK's keystore compatibility mode.
     */
    @Test
    void a_jks_format_store_loads_and_yields_its_alias(@TempDir Path tmp) throws Exception {
        Path jks = keystore(tmp.resolve("jks"), "JKS", "release.jks");
        FakeBuildIo io = AndroidIo.packager(tmp, "app.apk")
                .config("signing.store-file", jks.toString())
                .config("signing.key-alias", "upload")
                .secret("signing.store-password", PASSWORD)
                .secret("signing.key-password", PASSWORD);

        assertThat(Signing.release(io).name()).isEqualTo("upload");
    }

    /**
     * The format is probed from the store's content, never guessed from its file name: keytool has
     * defaulted to PKCS12 since JDK 9, so a PKCS12 store named {@code .jks} is what most teams
     * actually hold, and the inverse (a JKS store under AGP's customary {@code .keystore} or any
     * other name) falls the other way. The JDK's keystore compatibility mode would mask an
     * extension-based guess — while it is on, either type string reads either format — so it is
     * off for the assertion. The property is read per load, which keeps the toggle test-local.
     */
    @Test
    void the_store_format_is_probed_from_content_not_from_the_extension(@TempDir Path tmp) throws Exception {
        Path jksNamedAsP12 = keystore(tmp.resolve("a"), "JKS", "store.p12");
        Path pkcs12NamedAsJks = keystore(tmp.resolve("b"), "PKCS12", "store.jks");
        String compat = Security.getProperty("keystore.type.compat");
        Security.setProperty("keystore.type.compat", "false");
        try {
            for (Path store : List.of(jksNamedAsP12, pkcs12NamedAsJks)) {
                FakeBuildIo io = AndroidIo.packager(tmp, "app.apk")
                        .config("signing.store-file", store.toString())
                        .config("signing.key-alias", "upload")
                        .secret("signing.store-password", PASSWORD)
                        .secret("signing.key-password", PASSWORD);

                assertThat(Signing.release(io).name())
                        .as("%s loads by content", store.getFileName())
                        .isEqualTo("upload");
            }
        } finally {
            Security.setProperty("keystore.type.compat", compat == null ? "true" : compat);
        }
    }

    /**
     * v1 and v2 are always applied; v3 rides the identity. Both halves matter and neither is
     * visible from the flags alone — apksig decides for itself which schemes it can honour, so the
     * only way to know a scheme was applied is to ask its verifier about the finished file.
     */
    @Test
    void v1_and_v2_are_always_applied_and_v3_only_for_a_release_identity(@TempDir Path tmp) throws Exception {
        Signing.Identity releaseIdentity = Signing.release(release(tmp));
        Signing.Identity debugShaped =
                new Signing.Identity(releaseIdentity.key(), releaseIdentity.certs(), releaseIdentity.name(), false);
        Path unsigned = unsignedApk(tmp);

        Signing.sign(releaseIdentity, unsigned, tmp.resolve("release.apk"));
        Signing.sign(debugShaped, unsigned, tmp.resolve("debug.apk"));

        assertThat(schemes(tmp.resolve("release.apk"))).containsExactly(true, true, true);
        assertThat(schemes(tmp.resolve("debug.apk")))
                .as("v1 and v2 still hold; only the rotation scheme is off")
                .containsExactly(true, true, false);
    }

    /** The signature covers the archive as it is written, so a later edit invalidates it. */
    @Test
    void a_byte_changed_after_signing_fails_verification(@TempDir Path tmp) throws Exception {
        Signing.sign(Signing.release(release(tmp)), unsignedApk(tmp), tmp.resolve("app.apk"));
        byte[] signed = Files.readAllBytes(tmp.resolve("app.apk"));
        int at = indexOf(signed, "payload".getBytes(StandardCharsets.UTF_8));
        assertThat(at).as("the fixture's plain-stored payload is findable").isGreaterThan(0);
        signed[at] = 'P';
        Files.write(tmp.resolve("tampered.apk"), signed);

        ApkVerifier.Result result = new ApkVerifier.Builder(
                        tmp.resolve("tampered.apk").toFile())
                .setMinCheckedPlatformVersion(1)
                .build()
                .verify();
        assertThat(result.isVerified()).isFalse();
    }

    // ---- fixtures -----------------------------------------------------------------------

    /** A release-configured IO whose store is a real keystore the plugin's own keytool call made. */
    private static FakeBuildIo release(Path tmp) throws Exception {
        Path keystore = DebugKeystore.ensure(
                Files.createDirectories(tmp.resolve("keys")), Path.of(System.getProperty("java.home")));
        return AndroidIo.packager(tmp, "app.apk")
                .config("signing.store-file", keystore.toAbsolutePath().toString())
                .config("signing.key-alias", DebugKeystore.ALIAS)
                .secret("signing.store-password", PASSWORD)
                .secret("signing.key-password", PASSWORD);
    }

    /** A genuine keystore of the named format ({@code JKS} or {@code PKCS12}), under any file name. */
    private static Path keystore(Path dir, String storeType, String fileName) throws Exception {
        Files.createDirectories(dir);
        Path store = dir.resolve(fileName);
        Path pass = Files.writeString(dir.resolve("pass"), PASSWORD);
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-storetype",
                storeType,
                "-keystore",
                store.toString(),
                "-storepass:file",
                pass.toString(),
                "-keypass:file",
                pass.toString(),
                "-alias",
                "upload",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "3650",
                "-dname",
                "CN=Upload,O=Example,C=US"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("keytool: %s", output).isZero();
        return store;
    }

    /** The smallest thing apksig will sign: a parseable binary manifest and one payload entry. */
    private static Path unsignedApk(Path tmp) throws Exception {
        Path apk = tmp.resolve("unsigned.apk");
        if (Files.isRegularFile(apk)) return apk;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(BinaryXml.manifest());
            zip.closeEntry();
            // STORED so the bytes are findable in the signed file — the tamper case edits them.
            byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
            ZipEntry note = new ZipEntry("assets/note.txt");
            note.setMethod(ZipEntry.STORED);
            note.setSize(payload.length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            note.setCrc(crc.getValue());
            zip.putNextEntry(note);
            zip.write(payload);
            zip.closeEntry();
        }
        return apk;
    }

    /** v1, v2, v3 as apksig's verifier reports them. */
    private static List<Boolean> schemes(Path apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk.toFile())
                .setMinCheckedPlatformVersion(1)
                .build()
                .verify();
        assertThat(result.getErrors()).isEmpty();
        return List.of(
                result.isVerifiedUsingV1Scheme(), result.isVerifiedUsingV2Scheme(), result.isVerifiedUsingV3Scheme());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
