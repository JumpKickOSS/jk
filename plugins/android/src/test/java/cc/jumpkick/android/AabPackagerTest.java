// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AAB base module — the layout bundletool demands, built from the proto-format aapt2 link.
 *
 * <p>bundletool is a fetched JVM tool with a real dependency graph, so the {@code build-bundle}
 * fork itself is out of reach here. What is reachable is the part that decides the bundle's shape:
 * every entry of the proto link is relocated under one of five prefixes, and getting one wrong
 * produces a zip bundletool builds happily and Play rejects on upload — the slowest possible place
 * to learn about it. So the relocation is asserted directly, and the {@code produce} arms that
 * refuse a build before it ever forks bundletool are asserted through {@code produce}.
 */
class AabPackagerTest {

    /** Epoch second 318_211_200 — {@code DeterministicZip.PINNED}. */
    private static final LocalDateTime PINNED = LocalDateTime.of(1980, 2, 1, 0, 0);

    /**
     * bundletool's base-module contract: the proto manifest moves under {@code manifest/}, the
     * resource table and the compiled resources keep their names, dex moves under {@code dex/}, and
     * everything else the link emitted is application {@code root/} content — a file left at the
     * top level is neither, and bundletool has no prefix that means "as-is".
     */
    @Test
    void every_entry_of_the_proto_link_lands_under_its_bundletool_prefix(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(entries(baseZip).keySet())
                .containsExactlyInAnyOrder(
                        "manifest/AndroidManifest.xml",
                        "resources.pb",
                        "res/layout/main.xml",
                        "root/META-INF/services/example",
                        "root/kotlin/kotlin.kotlin_builtins",
                        "dex/classes.dex",
                        "dex/classes2.dex",
                        "assets/config.json",
                        "assets/aar-only.txt",
                        "lib/arm64-v8a/libnative.so");
    }

    /** The manifest is only renamed, never rewritten: it is already protobuf by this point. */
    @Test
    void the_proto_manifest_is_moved_not_transformed(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(text(baseZip, "manifest/AndroidManifest.xml")).isEqualTo("proto-manifest");
        assertThat(text(baseZip, "resources.pb")).isEqualTo("proto-table");
    }

    /** AGP's precedence again: the app's own assets beat a dependency's at the same path. */
    @Test
    void the_modules_own_asset_beats_a_dependencys_at_the_same_path(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(text(baseZip, "assets/config.json")).isEqualTo("from-the-module");
    }

    /** Whatever the link chose to compress stays that way through the relocation. */
    @Test
    void the_compression_method_of_a_relocated_entry_survives(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(methods(baseZip))
                .containsEntry("resources.pb", ZipEntry.STORED)
                .containsEntry("manifest/AndroidManifest.xml", ZipEntry.DEFLATED)
                .containsEntry("dex/classes.dex", ZipEntry.DEFLATED);
    }

    /**
     * The base module is an input to bundletool, which is an input to the artifact the action cache
     * keys on, so it has to be a function of its inputs alone.
     */
    @Test
    void two_assemblies_of_the_same_inputs_produce_the_same_base_module(@TempDir Path tmp) throws Exception {
        FakeBuildIo first = release(tmp.resolve("run-1"));
        Path a = tmp.resolve("a.zip");
        AabPackager.assembleBase(first, protoLink(tmp.resolve("run-1")), dexDir(tmp.resolve("run-1")), a);
        FakeBuildIo second = release(tmp.resolve("run-2"));
        Path b = tmp.resolve("b.zip");
        AabPackager.assembleBase(second, protoLink(tmp.resolve("run-2")), dexDir(tmp.resolve("run-2")), b);

        assertThat(Files.readAllBytes(b)).isEqualTo(Files.readAllBytes(a));
        assertThat(times(a))
                .isNotEmpty()
                .allSatisfy((name, time) -> assertThat(time).as("%s", name).isEqualTo(PINNED));
    }

    /**
     * The debug arm of the bundle signer, end to end through a real jarsigner fork: no
     * {@code [android.signing.*]} config, so the stable debug identity signs — generated under the
     * configured {@code debug-store-dir}, where the keystore must materialize. An AAB is a signed
     * jar, so apksig's verifier reports exactly the v1 (JAR) scheme and neither APK scheme.
     *
     * <p>Revert caveat: with the {@code debug-store-dir} read dropped, this run resolves the real
     * stable dir — read-only when {@code ~/.android/debug.keystore} exists, generating one there
     * when absent — which is exactly the defect the seam removes. The temp-dir assertion is what
     * goes red.
     */
    @Test
    void the_debug_identity_signs_the_bundle_under_the_configured_store_dir(@TempDir Path tmp) throws Exception {
        Path storeDir = tmp.resolve("android-home");
        FakeBuildIo io = release(tmp);
        io.config("debug-store-dir", storeDir.toString());
        Path unsigned = tmp.resolve("unsigned.aab");
        // Not bundletool's layout: the manifest sits at the top level because apksig's verifier —
        // used below only to read which schemes signed the jar — refuses an archive without one
        // there. The base-module layout has its own tests above; this one is about the signing arm.
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsigned))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(BinaryXml.manifest());
            zip.closeEntry();
        }

        AabPackager.signBundle(io, unsigned, io.artifactPath());

        assertThat(DebugKeystore.path(storeDir))
                .as("the debug identity generates under the redirected store dir")
                .isRegularFile();
        // Platform 24, not 1: jarsigner signs with SHA-384 digests, which the v1 scheme only
        // admits on modern API levels — and an AAB never installs on anything older anyway.
        ApkVerifier.Result result = new ApkVerifier.Builder(io.artifactPath().toFile())
                .setMinCheckedPlatformVersion(24)
                .build()
                .verify();
        assertThat(result.isVerifiedUsingV1Scheme())
                .as("an AAB carries the JAR signature")
                .isTrue();
        assertThat(result.isVerifiedUsingV2Scheme()).isFalse();
        assertThat(result.isVerifiedUsingV3Scheme()).isFalse();
    }

    /** An empty dex directory would assemble a bundle with no code in it. */
    @Test
    void a_dex_directory_with_no_dex_files_in_it_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path emptyDex = Files.createDirectories(tmp.resolve("empty-dex"));

        assertThatThrownBy(() -> AabPackager.assembleBase(io, protoLink(tmp), emptyDex, tmp.resolve("base.zip")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no .dex files");
    }

    /**
     * A release build links twice — binary for the APK path, proto for the bundle. If only the
     * binary link is there the module was not built as a release, and saying which link is missing
     * beats bundletool's own error about a zip that does not look like a base module.
     */
    @Test
    void a_build_without_the_proto_resource_link_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path packaged = Files.createDirectories(io.step("android-res").resolve("packaged"));
        Files.copy(protoLink(tmp), packaged.resolve("resources.ap_")); // the binary link only
        FakeBuildIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex");

        assertThatThrownBy(() -> AabPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proto resource package");
    }

    /**
     * bundletool arrives as a manifest-declared step dependency the engine fetches. Absent, the
     * build stops with the artifact's name in the message rather than an NPE from a fork.
     */
    @Test
    void a_build_with_no_bundletool_artifact_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path packaged = Files.createDirectories(io.step("android-res").resolve("packaged"));
        Files.copy(protoLink(tmp), packaged.resolve("resources-proto.ap_"));
        FakeBuildIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex");

        assertThatThrownBy(() -> AabPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bundletool");
    }

    /**
     * The base module and the unsigned bundle are intermediates in a scratch directory that exists
     * only until the signed artifact does — on the refused build too, which is the one a developer
     * repeats.
     */
    @Test
    void a_refused_bundle_leaves_no_scratch_directory_behind(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = release(tmp);
        Path packaged = Files.createDirectories(io.step("android-res").resolve("packaged"));
        Files.copy(protoLink(tmp), packaged.resolve("resources-proto.ap_"));
        Files.createDirectories(io.step("android-dex").resolve("dex")); // the step ran; no dex came out
        io.extra("bundletool", Files.createDirectories(tmp.resolve("bundletool")));
        Set<Path> before = scratchDirs();

        assertThatThrownBy(() -> AabPackager.produce(io)).hasMessageContaining("no .dex files");

        assertThat(scratchDirs()).isEqualTo(before);
    }

    /** Every {@code jk-aab-*} directory in the JVM's temp dir right now. */
    private static Set<Path> scratchDirs() throws IOException {
        Path tmpdir = Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")));
        try (var children = Files.list(tmpdir)) {
            return children.filter(p -> p.getFileName().toString().startsWith("jk-aab-"))
                    .collect(Collectors.toSet());
        }
    }

    // ---- fixtures -----------------------------------------------------------------------

    /** A release app's dependency and asset surface; the packagers read nothing else from it. */
    private static FakeBuildIo release(Path root) throws Exception {
        FakeBuildIo io = AndroidIo.packager(Files.createDirectories(root), "app-1.0.0.aab");
        io.config("build-type", "release");
        FakeBuildIo.write(io.moduleDir().resolve("assets/config.json"), "from-the-module");
        Path aar = io.container("widgets.aar");
        FakeBuildIo.write(aar.resolve("assets/config.json"), "from-the-dependency");
        FakeBuildIo.write(aar.resolve("assets/aar-only.txt"), "only here");
        FakeBuildIo.write(aar.resolve("jni/arm64-v8a/libnative.so"), "ELF");
        return io;
    }

    private static Path dexDir(Path root) throws Exception {
        Path dex = Files.createDirectories(root.resolve("dex-out"));
        FakeBuildIo.write(dex.resolve("classes.dex"), "dex-one");
        FakeBuildIo.write(dex.resolve("classes2.dex"), "dex-two");
        return dex;
    }

    /** {@code aapt2 link --proto-format} output: the proto manifest, the table, res, and extras. */
    private static Path protoLink(Path root) throws Exception {
        Path out = Files.createDirectories(root).resolve("resources-proto.ap_");
        if (Files.isRegularFile(out)) return out;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            deflated(zip, "AndroidManifest.xml", "proto-manifest");
            stored(zip, "resources.pb", "proto-table");
            deflated(zip, "res/layout/main.xml", "proto-layout");
            deflated(zip, "META-INF/services/example", "service");
            deflated(zip, "kotlin/kotlin.kotlin_builtins", "builtins");
        }
        return out;
    }

    private static void deflated(ZipOutputStream zip, String name, String body) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setTimeLocal(PINNED);
        zip.putNextEntry(entry);
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void stored(ZipOutputStream zip, String name, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

    private static String text(Path archive, String entry) throws Exception {
        return new String(entries(archive).get(entry), StandardCharsets.UTF_8);
    }

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
