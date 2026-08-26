// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AAR a library module publishes, driven end to end over {@link FakePackageIo} and asserted by
 * reading the archive. No Android SDK is involved: an AAR is a zip of things earlier steps already
 * produced, which is exactly why the packager is worth pinning — it is the one artifact jk hands to
 * other people's builds, and the only thing that reads it is Gradle or another jk.
 *
 * <p>{@link AarClassesJarTest} pins what {@code classes.jar} may contain and
 * {@link ArchiveTimestampTest} pins its entry timestamps. This covers the layout around it.
 */
class AarPackagerTest {

    @Test
    void the_aar_carries_the_manifest_the_classes_jar_the_symbols_and_the_raw_resources(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = library(tmp);

        AarPackager.produce(io);

        assertThat(entries(io.artifactPath()).keySet())
                .containsExactlyInAnyOrder(
                        "AndroidManifest.xml",
                        "classes.jar",
                        "R.txt",
                        "res/values/strings.xml",
                        "res/drawable-hdpi/ic.xml");
        assertThat(text(io.artifactPath(), "AndroidManifest.xml")).contains("com.example.lib");
        assertThat(text(io.artifactPath(), "R.txt")).contains("int string app_name");
    }

    /**
     * Resource entries are {@code /}-separated whatever the host separator is, and the qualifier
     * directory is part of the entry name — flatten it and every non-default configuration
     * collapses onto the default one.
     */
    @Test
    void resource_entries_keep_their_qualifier_directory(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = library(tmp);

        AarPackager.produce(io);

        assertThat(entries(io.artifactPath()).keySet())
                .filteredOn(name -> name.startsWith("res/"))
                .allSatisfy(name -> assertThat(name).doesNotContain("\\"))
                .contains("res/drawable-hdpi/ic.xml");
    }

    /**
     * The sibling {@code .jar} is the host-classpath view of the same module — what a workspace
     * sibling compiles against, and what the engine caches alongside the AAR. It is the same bytes
     * as the AAR's own {@code classes.jar}, written once; two spellings of "the library's classes"
     * that could disagree would be two artifacts for one thing.
     */
    @Test
    void the_conventional_classes_jar_is_written_beside_the_aar_from_the_same_bytes(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = library(tmp);

        AarPackager.produce(io);

        Path sibling = io.artifactPath().resolveSibling("lib-1.0.0.jar");
        assertThat(sibling).exists();
        assertThat(Files.readAllBytes(sibling))
                .isEqualTo(entries(io.artifactPath()).get("classes.jar"));
        assertThat(entries(sibling).keySet())
                .as("R classes are the consumer's to regenerate, never the library's to ship")
                .containsExactly("com/example/lib/Widget.class");
    }

    /** Without a merged manifest there is no AAR to write, and saying so beats a zip nobody can use. */
    @Test
    void an_aar_without_the_merged_manifest_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = AndroidIo.packager(tmp, "lib-1.0.0.aar");
        Files.createDirectories(io.step("android-manifest")); // ran, produced nothing

        assertThatThrownBy(() -> AarPackager.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("merged manifest");
        assertThat(io.artifactPath()).doesNotExist();
    }

    /** A library need not have resources at all; the manifest and the classes are the minimum. */
    @Test
    void a_library_with_no_resources_still_packages(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = AndroidIo.packager(tmp, "lib-1.0.0.aar");
        FakeBuildIo.write(io.step("android-manifest").resolve("merged/AndroidManifest.xml"), MANIFEST);
        FakeBuildIo.write(io.classesDir().resolve("com/example/lib/Widget.class"), "class");

        AarPackager.produce(io);

        assertThat(entries(io.artifactPath()).keySet()).containsExactlyInAnyOrder("AndroidManifest.xml", "classes.jar");
    }

    /**
     * The AAR is content-addressed by the engine and consumed by other builds, so identical inputs
     * must give identical bytes. Two chances to leak nondeterminism are covered by building it
     * twice in two directories — the order the classes and resources are walked in, and any path
     * that reached the archive — and the third, the wall clock, by demanding the pinned instant on
     * every entry of both the AAR and the {@code classes.jar} nested inside it. Byte equality alone
     * would not catch the clock, because two runs a millisecond apart share a DOS timestamp.
     */
    @Test
    void two_runs_over_the_same_inputs_produce_the_same_aar(@TempDir Path tmp) throws Exception {
        FakeBuildIo first = library(tmp.resolve("run-1"));
        AarPackager.produce(first);
        FakeBuildIo second = library(tmp.resolve("run-2"));
        AarPackager.produce(second);

        assertThat(Files.readAllBytes(second.artifactPath()))
                .as("the AAR is a function of its inputs and nothing else")
                .isEqualTo(Files.readAllBytes(first.artifactPath()));
        assertThat(entryTimes(Files.readAllBytes(first.artifactPath())))
                .isNotEmpty()
                .allSatisfy(time -> assertThat(time).isEqualTo(PINNED));
        assertThat(entryTimes(entries(first.artifactPath()).get("classes.jar")))
                .as("the jar nested inside the AAR is pinned too")
                .isNotEmpty()
                .allSatisfy(time -> assertThat(time).isEqualTo(PINNED));
    }

    // ---- fixtures -----------------------------------------------------------------------

    /** Epoch second 318_211_200 — {@code DeterministicZip.PINNED}, what every jk archive stamps. */
    private static final LocalDateTime PINNED = LocalDateTime.of(1980, 2, 1, 0, 0);

    private static final String MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.lib"/>
            """;

    /** A library module as the earlier steps leave it: merged manifest, linked symbols, raw res. */
    private static FakeBuildIo library(Path root) throws Exception {
        FakeBuildIo io = AndroidIo.packager(Files.createDirectories(root), "lib-1.0.0.aar");
        io.config("library", Boolean.TRUE);
        FakeBuildIo.write(io.step("android-manifest").resolve("merged/AndroidManifest.xml"), MANIFEST);
        Path res = io.step("android-res");
        FakeBuildIo.write(res.resolve("packaged/R.txt"), "int string app_name 0x7f0f0000\n");
        FakeBuildIo.write(res.resolve("raw-res/values/strings.xml"), "<resources/>");
        FakeBuildIo.write(res.resolve("raw-res/drawable-hdpi/ic.xml"), "<vector/>");
        FakeBuildIo.write(io.classesDir().resolve("com/example/lib/Widget.class"), "class");
        FakeBuildIo.write(io.classesDir().resolve("com/example/lib/R.class"), "generated");
        return io;
    }

    /** Entry name → bytes, in encounter order. */
    private static Map<String, byte[]> entries(Path archive) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                out.put(entry.getName(), drain(in));
            }
        }
        return out;
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    private static String text(Path archive, String entry) throws Exception {
        return new String(entries(archive).get(entry), StandardCharsets.UTF_8);
    }

    /** Every entry's stored local timestamp, in encounter order. */
    private static List<LocalDateTime> entryTimes(byte[] archive) throws Exception {
        List<LocalDateTime> times = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                times.add(entry.getTimeLocal());
            }
        }
        return times;
    }
}
