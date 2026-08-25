// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.android.apksig.ApkVerifier;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The APK, assembled and signed end to end over {@link FakePackageIo}. Everything the packager
 * needs is a file some earlier step wrote, so the whole body runs here with no Android SDK, no
 * aapt2 and no device — only {@code keytool} (to mint the fixture keystore) and apksig, which the
 * plugin bundles anyway.
 *
 * <p>The cases sign with a <em>release</em> identity on purpose. {@code Signing.debugKeystore}
 * resolves {@code DebugKeystore.stableDir()} with no seam, so exercising the debug arm from a test
 * would write into the developer's real {@code ~/.android} — see {@link DebugKeystoreTest}, which
 * covers that generation against an explicit directory instead.
 */
class ApkPackagerTest {

    /** Epoch second 318_211_200 — {@code DeterministicZip.PINNED}. */
    private static final LocalDateTime PINNED = LocalDateTime.of(1980, 2, 1, 0, 0);

    private static final String KEY_PASSWORD = DebugKeystore.PASSWORD;

    /**
     * The APK's contents: everything aapt2 linked, every dex the dex step emitted, the merged
     * assets and the dependencies' native libraries. Each of the four arrives by a different route,
     * and three of them are silent when they go missing — an APK short an asset or a {@code .so}
     * installs fine and crashes on the device.
     */
    @Test
    void the_apk_carries_the_linked_resources_every_dex_the_assets_and_the_native_libs(@TempDir Path tmp)
            throws Exception {
        FakePackageIo io = app(tmp);

        ApkPackager.produce(io);

        assertThat(names(io.artifactPath()))
                .contains(
                        "AndroidManifest.xml",
                        "resources.arsc",
                        "res/layout/main.xml",
                        "classes.dex",
                        "classes2.dex",
                        "assets/config.json",
                        "assets/aar-only.txt",
                        "lib/arm64-v8a/libnative.so");
    }

    /**
     * {@code resources.arsc} must stay uncompressed: the platform mmaps it out of the APK, and
     * since Android 11 the installer rejects a compressed one outright. It arrives STORED from
     * aapt2 and the packager's job is to not lose that on the way through. Native libraries are
     * STORED for the same family of reason — apksig page-aligns uncompressed {@code .so} entries so
     * the loader can map them directly.
     */
    @Test
    void the_compression_method_of_every_entry_survives_the_copy(@TempDir Path tmp) throws Exception {
        FakePackageIo io = app(tmp);

        ApkPackager.produce(io);

        Map<String, Integer> methods = methods(io.artifactPath());
        assertThat(methods).containsEntry("resources.arsc", ZipEntry.STORED);
        assertThat(methods).containsEntry("lib/arm64-v8a/libnative.so", ZipEntry.STORED);
        assertThat(methods)
                .as("everything else is worth compressing")
                .containsEntry("res/layout/main.xml", ZipEntry.DEFLATED)
                .containsEntry("classes.dex", ZipEntry.DEFLATED)
                .containsEntry("assets/config.json", ZipEntry.DEFLATED);
    }

    /** AGP's precedence: the module's own assets win a path conflict with a dependency's. */
    @Test
    void the_modules_own_asset_beats_a_dependencys_at_the_same_path(@TempDir Path tmp) throws Exception {
        FakePackageIo io = app(tmp);

        ApkPackager.produce(io);

        assertThat(text(io.artifactPath(), "assets/config.json")).isEqualTo("from-the-module");
    }

    /**
     * The output is an installable APK, not merely a zip with the right names in it. apksig's own
     * verifier is the only thing that can say so: it re-derives the v1 digests from the entries and
     * checks the v2 block against the archive's actual bytes, so it fails if the packager wrote the
     * signature over content it then changed. v3 rides a release identity and not a debug one.
     */
    @Test
    void the_signed_apk_verifies_under_v1_v2_and_v3(@TempDir Path tmp) throws Exception {
        FakePackageIo io = app(tmp);

        ApkPackager.produce(io);

        // The fixture manifest declares no <uses-sdk>, so the minimum platform version is 1 and
        // every scheme is in play. Naming it explicitly also keeps the verifier off the manifest
        // parse, which is the signer's job to have already done.
        ApkVerifier.Result result = new ApkVerifier.Builder(io.artifactPath().toFile())
                .setMinCheckedPlatformVersion(1)
                .build()
                .verify();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.isVerified()).isTrue();
        assertThat(result.isVerifiedUsingV1Scheme())
                .as("v1: the JAR signature old devices read")
                .isTrue();
        assertThat(result.isVerifiedUsingV2Scheme())
                .as("v2: the whole-file APK signature")
                .isTrue();
        assertThat(result.isVerifiedUsingV3Scheme())
                .as("v3 (key rotation) is applied for a release identity")
                .isTrue();
    }

    /**
     * Byte reproducibility, which for an APK is not free: the archive is assembled from a zip copy,
     * a directory walk and two maps, then run through a signer. Two builds of the same inputs must
     * still be the same file, because the artifact cache keys on it and because a reproducible APK
     * is the only way anyone can check that a published binary came from the published source.
     *
     * <p>The clock is covered separately from the ordering: every entry the packager itself wrote
     * carries the pinned 1980 instant, which byte equality between two runs milliseconds apart
     * could not establish on its own — DOS timestamps have two-second resolution.
     */
    @Test
    void two_builds_of_the_same_inputs_produce_the_same_apk(@TempDir Path tmp) throws Exception {
        Path keys = tmp.resolve("keys"); // one identity: a second key would differ for the wrong reason
        FakePackageIo first = app(tmp.resolve("run-1"), keys);
        ApkPackager.produce(first);
        FakePackageIo second = app(tmp.resolve("run-2"), keys);
        ApkPackager.produce(second);

        assertThat(Files.readAllBytes(second.artifactPath()))
                .as("same inputs, same key, same bytes")
                .isEqualTo(Files.readAllBytes(first.artifactPath()));
        assertThat(times(first.artifactPath()))
                .as("every entry, including the signature files apksig copies the manifest's time onto")
                .isNotEmpty()
                .allSatisfy((name, time) -> assertThat(time).as("%s", name).isEqualTo(PINNED));
    }

    /** R8's mapping/seeds/usage land at the stable {@code target/r8/} the retrace tooling reads. */
    @Test
    void the_r8_retrace_artifacts_are_copied_beside_the_target_tree(@TempDir Path tmp) throws Exception {
        FakePackageIo io = app(tmp);
        FakePackageIo.write(io.step("android-r8").resolve("mapping/mapping.txt"), "com.example.App -> a.a:\n");
        FakePackageIo.write(io.step("android-r8").resolve("mapping/seeds.txt"), "com.example.App\n");
        Files.createDirectories(io.step("android-r8").resolve("dex"));
        Files.copy(
                io.step("android-dex").resolve("dex/classes.dex"),
                io.step("android-r8").resolve("dex/classes.dex"));

        ApkPackager.produce(io);

        Path r8 = io.moduleDir().resolve("target/r8");
        assertThat(r8.resolve("mapping.txt")).exists().content().contains("a.a:");
        assertThat(r8.resolve("seeds.txt")).exists();
    }

    /** A minified build dexes through R8, so its output is the one the APK must be built from. */
    @Test
    void the_r8_output_is_preferred_over_the_plain_dex_output(@TempDir Path tmp) throws Exception {
        FakePackageIo io = app(tmp);
        Files.createDirectories(io.step("android-r8").resolve("dex"));

        assertThat(ApkPackager.dexOutput(io)).isEqualTo(io.step("android-r8").resolve("dex"));
    }

    @Test
    void a_build_with_no_dex_output_at_all_is_refused(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app-1.0.0.apk");
        resourcePackage(io.step("android-res").resolve("packaged/resources.ap_"));

        assertThatThrownBy(() -> ApkPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no dex output");
    }

    @Test
    void a_build_with_no_linked_resources_is_refused(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app-1.0.0.apk");
        FakePackageIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex");

        assertThatThrownBy(() -> ApkPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resources.ap_");
    }

    /** An empty dex directory passes the "did the step run" check and must not become an empty APK. */
    @Test
    void a_dex_directory_with_no_dex_files_in_it_is_refused(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app-1.0.0.apk");
        resourcePackage(io.step("android-res").resolve("packaged/resources.ap_"));
        Files.createDirectories(io.step("android-dex").resolve("dex"));

        assertThatThrownBy(() -> ApkPackager.produce(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no .dex files");
    }

    // ---- fixtures -----------------------------------------------------------------------

    /**
     * A debug-shaped app after the resource and dex steps, plus one AAR dependency and a release
     * signing identity. The keystore is minted by the plugin's own {@code keytool} generation into
     * the temp dir, so the fixture and the product agree on what a keystore looks like.
     */
    private static FakePackageIo app(Path root) throws Exception {
        return app(root, root.resolve("keys"));
    }

    private static FakePackageIo app(Path root, Path keysDir) throws Exception {
        FakePackageIo io = new FakePackageIo(Files.createDirectories(root), "app-1.0.0.apk");
        resourcePackage(io.step("android-res").resolve("packaged/resources.ap_"));
        FakePackageIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex-one");
        FakePackageIo.write(io.step("android-dex").resolve("dex/classes2.dex"), "dex-two");
        FakePackageIo.write(io.moduleDir().resolve("assets/config.json"), "from-the-module");

        Path aar = io.aar("widgets.aar");
        FakePackageIo.write(aar.resolve("assets/config.json"), "from-the-dependency");
        FakePackageIo.write(aar.resolve("assets/aar-only.txt"), "only here");
        FakePackageIo.write(aar.resolve("jni/arm64-v8a/libnative.so"), "ELF");

        Path keystore =
                DebugKeystore.ensure(Files.createDirectories(keysDir), Path.of(System.getProperty("java.home")));
        io.config("signing.store-file", keystore.toAbsolutePath().toString())
                .config("signing.key-alias", DebugKeystore.ALIAS)
                .secret("signing.store-password", KEY_PASSWORD)
                .secret("signing.key-password", KEY_PASSWORD);
        return io;
    }

    /**
     * An aapt2-shaped {@code resources.ap_}: the binary manifest and the resource table, the table
     * STORED as aapt2 leaves it, plus one linked resource file.
     */
    private static void resourcePackage(Path out) throws Exception {
        Files.createDirectories(out.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            deflated(zip, "AndroidManifest.xml", BinaryXml.manifest());
            stored(zip, "resources.arsc", "resource-table".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "res/layout/main.xml", "binary-layout".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deflated(ZipOutputStream zip, String name, byte[] body) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setTimeLocal(PINNED);
        zip.putNextEntry(entry);
        zip.write(body);
        zip.closeEntry();
    }

    private static void stored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setTimeLocal(PINNED);
        entry.setSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    // ---- reading the result -------------------------------------------------------------

    private static Map<String, byte[]> entries(Path archive) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                out.put(entry.getName(), drain(in));
            }
        }
        return out;
    }

    private static Iterable<String> names(Path archive) throws Exception {
        return entries(archive).keySet();
    }

    private static String text(Path archive, String entry) throws Exception {
        return new String(entries(archive).get(entry), StandardCharsets.UTF_8);
    }

    /**
     * Entry name → compression method. Read from the <em>central directory</em>: a
     * {@link ZipInputStream} reports the local header, which for a streamed entry may not carry the
     * real method at all.
     */
    private static Map<String, Integer> methods(Path archive) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream().forEach(entry -> out.put(entry.getName(), entry.getMethod()));
        }
        return out;
    }

    private static Map<String, LocalDateTime> times(Path archive) throws Exception {
        Map<String, LocalDateTime> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(archive)))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                out.put(entry.getName(), entry.getTimeLocal());
            }
        }
        return out;
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }
}
