// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        FakePackageIo io = release(tmp);
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
        FakePackageIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(text(baseZip, "manifest/AndroidManifest.xml")).isEqualTo("proto-manifest");
        assertThat(text(baseZip, "resources.pb")).isEqualTo("proto-table");
    }

    /** AGP's precedence again: the app's own assets beat a dependency's at the same path. */
    @Test
    void the_modules_own_asset_beats_a_dependencys_at_the_same_path(@TempDir Path tmp) throws Exception {
        FakePackageIo io = release(tmp);
        Path baseZip = tmp.resolve("base.zip");

        AabPackager.assembleBase(io, protoLink(tmp), dexDir(tmp), baseZip);

        assertThat(text(baseZip, "assets/config.json")).isEqualTo("from-the-module");
    }

    /** Whatever the link chose to compress stays that way through the relocation. */
    @Test
    void the_compression_method_of_a_relocated_entry_survives(@TempDir Path tmp) throws Exception {
        FakePackageIo io = release(tmp);
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
        FakePackageIo first = release(tmp.resolve("run-1"));
        Path a = tmp.resolve("a.zip");
        AabPackager.assembleBase(first, protoLink(tmp.resolve("run-1")), dexDir(tmp.resolve("run-1")), a);
        FakePackageIo second = release(tmp.resolve("run-2"));
        Path b = tmp.resolve("b.zip");
        AabPackager.assembleBase(second, protoLink(tmp.resolve("run-2")), dexDir(tmp.resolve("run-2")), b);

        assertThat(Files.readAllBytes(b)).isEqualTo(Files.readAllBytes(a));
        assertThat(times(a))
                .isNotEmpty()
                .allSatisfy((name, time) -> assertThat(time).as("%s", name).isEqualTo(PINNED));
    }

    /** An empty dex directory would assemble a bundle with no code in it. */
    @Test
    void a_dex_directory_with_no_dex_files_in_it_is_refused(@TempDir Path tmp) throws Exception {
        FakePackageIo io = release(tmp);
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
        FakePackageIo io = release(tmp);
        Path packaged = Files.createDirectories(io.step("android-res").resolve("packaged"));
        Files.copy(protoLink(tmp), packaged.resolve("resources.ap_")); // the binary link only
        FakePackageIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex");

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
        FakePackageIo io = release(tmp);
        Path packaged = Files.createDirectories(io.step("android-res").resolve("packaged"));
        Files.copy(protoLink(tmp), packaged.resolve("resources-proto.ap_"));
        FakePackageIo.write(io.step("android-dex").resolve("dex/classes.dex"), "dex");

        assertThatThrownBy(() -> AabPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bundletool");
    }

    // ---- fixtures -----------------------------------------------------------------------

    /** A release app's dependency and asset surface; the packagers read nothing else from it. */
    private static FakePackageIo release(Path root) throws Exception {
        FakePackageIo io = new FakePackageIo(Files.createDirectories(root), "app-1.0.0.aab");
        io.config("build-type", "release");
        FakePackageIo.write(io.moduleDir().resolve("assets/config.json"), "from-the-module");
        Path aar = io.aar("widgets.aar");
        FakePackageIo.write(aar.resolve("assets/config.json"), "from-the-dependency");
        FakePackageIo.write(aar.resolve("assets/aar-only.txt"), "only here");
        FakePackageIo.write(aar.resolve("jni/arm64-v8a/libnative.so"), "ELF");
        return io;
    }

    private static Path dexDir(Path root) throws Exception {
        Path dex = Files.createDirectories(root.resolve("dex-out"));
        FakePackageIo.write(dex.resolve("classes.dex"), "dex-one");
        FakePackageIo.write(dex.resolve("classes2.dex"), "dex-two");
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
